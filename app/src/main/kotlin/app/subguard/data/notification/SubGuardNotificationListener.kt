package app.subguard.data.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知を読み取ってサブスクの候補を拾う。
 *
 * ## このクラスが守る不変条件
 *
 * コードレビューのチェックリストとして使うこと。破ると Play のポリシー違反になり、
 * 発覚した時点でアプリが削除される（docs/policy-compliance.md）。
 *
 *  1. 許可リストにないパッケージの通知テキストに、一切触れない
 *  2. 通知本文を DB / SharedPreferences / ファイルに書かない
 *  3. 通知本文をログに出さない —— **デバッグビルドでも**
 *  4. 通知本文をネットワークに送らない
 *
 * 3 は特に破りやすい。調査中に足した `Log.d(TAG, text)` がそのままリリースに乗るのが典型。
 * このファイルに `Log` を import しないことで、機械的に防いでいる。
 */
@AndroidEntryPoint
class SubGuardNotificationListener : NotificationListenerService() {

    @Inject lateinit var pipeline: DetectionPipeline

    /**
     * サービスの寿命に紐づくスコープ。
     *
     * `onNotificationPosted` はメインスレッドで呼ばれるため、
     * 正規表現の実行をここでやると ANR になる。必ず別スレッドへ逃がす。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 権限が付与されて OS がバインドした時点で呼ばれる。
     *
     * 初期化は `onCreate` ではなくここに置く。`onCreate` はサービス生成時で、
     * まだリスナーとして接続されていない可能性がある。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch { pipeline.warmUp() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // --- 1. パッケージで足切り。最初かつ最重要のフィルタ ---
        //
        // 通知は一度に大量に飛んでくる（接続直後は特に多い）。
        // ここを最初に置くことで、対象外アプリの本文には触れずに捨てられる。
        val packageName = sbn.packageName ?: return
        if (!pipeline.isWatchedPackage(packageName)) return

        // --- 2. テキスト抽出 ---
        val text = extractText(sbn.notification) ?: return

        // --- 3. 解析はワーカースレッドへ ---
        val postedAt = sbn.postTime
        scope.launch {
            pipeline.process(packageName, text, postedAt)
        }
    }

    /**
     * 通知から解析対象のテキストを組み立てる。
     *
     * `EXTRA_BIG_TEXT` は展開時の全文で、`EXTRA_TEXT` より情報量が多いことが多い。
     * 両方を入れておき、パターン側で拾わせる。
     */
    private fun extractText(notification: Notification?): String? {
        val extras = notification?.extras ?: return null
        val parts = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).map { it.toString().trim() }.filter { it.isNotEmpty() }.distinct()

        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
