package app.subguard.data.notification

import app.subguard.data.local.DetectionEventDao
import app.subguard.data.local.DetectionEventEntity
import app.subguard.data.remote.CatalogRepository
import app.subguard.detection.model.DetectionCandidate
import app.subguard.detection.parse.NotificationParser
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通知テキストを候補に変えて保存するところまでを受け持つ。
 *
 * `NotificationListenerService` は OS がいつでも起動・破棄するため、
 * **状態をサービス側に持たせない**。ここは Singleton で、カタログは毎回
 * リポジトリから取り直す（内部でキャッシュされている）。
 */
@Singleton
class DetectionPipeline @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val detectionEventDao: DetectionEventDao,
    private val notifier: DetectionNotifier,
) {
    private val parserLock = Mutex()
    private var cachedParser: NotificationParser? = null
    private var cachedCatalogVersion: Int = -1

    /** リスナー接続時に呼ぶ。最初の通知が来る前にカタログを載せておく。 */
    suspend fun warmUp() {
        runCatching { parser() }
    }

    /**
     * 監視対象のパッケージか。
     *
     * **同期メソッドである必要がある。** `onNotificationPosted` はメインスレッドで、
     * ここで待たされると ANR になる。
     *
     * カタログ未ロード時は **false を返す（fail closed）**。「判定できないので念のため見る」
     * に倒すと、許可リスト外アプリの通知本文を一瞬でも読むことになり、
     * 「許可リストにないパッケージの通知テキストに一切触れない」という不変条件が崩れる。
     * 取りこぼしのコストは、この不変条件を破るコストより遥かに小さい。
     *
     * ロードは [warmUp] が `onListenerConnected` で済ませるので、
     * 実際に取りこぼす窓は接続直後のごく短い間だけ。
     */
    fun isWatchedPackage(packageName: String): Boolean =
        cachedParser?.isWatchedPackage(packageName) ?: false

    /**
     * 通知を解析して、サブスクらしければ PENDING の候補として保存する。
     *
     * **ここで `subscriptions` には書き込まない。** ユーザーが確認画面で承認して
     * 初めてサブスクになる。誤検知のコストは検知漏れより遥かに高い
     * （docs/notification-detection.md）。
     */
    suspend fun process(packageName: String, text: String, postedAtMillis: Long) {
        val parser = runCatching { parser() }.getOrNull() ?: return
        if (!parser.isWatchedPackage(packageName)) return

        // 正規表現に時間制限をかける。Kotlin の Regex 自体はタイムアウトを持たないので、
        // コルーチン側で打ち切る。悪意ある通知テキストによる ReDoS を、
        // 通知経由で他アプリから仕掛けられる余地を残さない。
        val candidate = withTimeoutOrNull(PARSE_TIMEOUT_MILLIS) {
            parser.parse(
                packageName = packageName,
                text = text,
                receivedAt = Instant.ofEpochMilli(postedAtMillis),
                locale = Locale.getDefault(),
            )
        } ?: return

        // 同じ通知の再投稿で候補が増えないようにする。
        // 進捗更新などで通知は繰り返し投稿されるため、これがないと
        // ユーザーは同じ確認を何度もさせられる。
        val duplicates = detectionEventDao.countRecentDuplicates(
            packageName = candidate.packageName,
            patternId = candidate.matchedPatternId,
            since = postedAtMillis - DUPLICATE_WINDOW_MILLIS,
        )
        if (duplicates > 0) return

        val id = detectionEventDao.insert(candidate.toEntity())
        notifier.notifyDetected(id, candidate)

        // この時点で text はもう参照されない。保存もログ出力もしていない。
    }

    /** カタログが更新されていればパーサを作り直す。 */
    private suspend fun parser(): NotificationParser = parserLock.withLock {
        val catalog = catalogRepository.catalog()
        val cached = cachedParser
        if (cached != null && cachedCatalogVersion == catalog.version) return cached

        NotificationParser(catalog).also {
            cachedParser = it
            cachedCatalogVersion = catalog.version
        }
    }

    private fun DetectionCandidate.toEntity() = DetectionEventEntity(
        packageName = packageName,
        matchedPatternId = matchedPatternId,
        detectedAt = detectedAt.toEpochMilli(),
        serviceId = serviceId,
        serviceName = serviceName,
        amountMinor = amount?.minor,
        currency = amount?.currency,
        renewalDate = renewalAt?.toEpochMilli(),
        isTrial = isTrial,
        confidence = confidence,
    )

    private companion object {
        /** 1 件の解析にかけてよい上限。これを超えるのはパターンの書き方が悪い。 */
        const val PARSE_TIMEOUT_MILLIS = 2_000L

        /** この時間内の同一パターン・同一パッケージの検知は重複とみなす。 */
        const val DUPLICATE_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    }
}
