package app.subguard.data.remote

import android.content.Context
import app.subguard.data.local.ServiceCatalogDao
import app.subguard.data.local.ServiceCatalogEntity
import app.subguard.detection.catalog.Catalog
import app.subguard.detection.catalog.CatalogService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 解約 URL カタログの供給元。
 *
 * 優先順位は「同期済み → assets 同梱版」。同期は失敗してよい設計にしてある
 * （ネットワークが死んでいてもアプリは動く）。
 */
@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogDao: ServiceCatalogDao,
    private val syncStore: CatalogSyncStore,
    private val client: CatalogHttpClient,
) {
    private val lock = Mutex()
    private var cached: Catalog? = null

    /**
     * 現在のカタログ。
     *
     * 検知パターンは DB に落とさず、常に JSON 側を正とする
     * （パターンはサービス一覧と違って検索も部分更新もしないため、
     * リレーショナルに持つ意味がない）。
     */
    suspend fun catalog(): Catalog = lock.withLock {
        cached?.let { return it }
        val loaded = syncStore.storedCatalogJson()?.let { json ->
            runCatching { Catalog.parse(json) }.getOrNull()
        } ?: bundledCatalog()
        cached = loaded
        loaded
    }

    /** assets に同梱したカタログ。初回起動時、まだ一度も同期していない状態で使う。 */
    private suspend fun bundledCatalog(): Catalog = withContext(Dispatchers.IO) {
        context.assets.open(BUNDLED_ASSET).bufferedReader().use { reader ->
            Catalog.parse(reader.readText())
        }
    }

    /**
     * カタログを同期する。
     *
     * ```
     * version.json を取得（ETag 付き）
     *   → 304 なら終了
     *   → version が進んでいなければ終了
     *   → schema_version がアプリの対応範囲外ならスキップ
     *   → 本体を取得してパース
     *   → パース成功時のみ、トランザクションで全置換
     * ```
     *
     * **パース失敗時は既存カタログを維持する。** 壊れた JSON を push してしまったときに
     * 全ユーザーのカタログが消えるのが最悪のケース。
     */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val localVersion = syncStore.version()

        val versionResponse = runCatching {
            client.fetchVersion(syncStore.versionEtag())
        }.getOrElse { return@withContext SyncResult.Failed(it) }

        when (versionResponse) {
            is HttpPayload.NotModified -> return@withContext SyncResult.UpToDate
            is HttpPayload.Body -> Unit
        }

        val remoteMeta = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(CatalogVersion.serializer(), versionResponse.text)
        }.getOrElse { return@withContext SyncResult.Failed(it) }

        syncStore.setVersionEtag(versionResponse.etag)

        if (remoteMeta.version <= localVersion) return@withContext SyncResult.UpToDate

        // 自分が理解できないスキーマは触らない。
        // 古いアプリが新しい JSON をパースして壊れるのを防ぐ。
        if (remoteMeta.schemaVersion > Catalog.SUPPORTED_SCHEMA_VERSION) {
            return@withContext SyncResult.SchemaTooNew(remoteMeta.schemaVersion)
        }

        val bodyResponse = runCatching {
            client.fetchCatalog(syncStore.catalogEtag())
        }.getOrElse { return@withContext SyncResult.Failed(it) }

        val body = when (bodyResponse) {
            is HttpPayload.NotModified -> return@withContext SyncResult.UpToDate
            is HttpPayload.Body -> bodyResponse
        }

        // パースが通ってから初めて書き込む。ここが順序の要。
        val parsed = runCatching { Catalog.parse(body.text) }
            .getOrElse { return@withContext SyncResult.Failed(it) }

        catalogDao.replaceAll(parsed.services.map { it.toEntity() })
        syncStore.setStoredCatalogJson(body.text)
        syncStore.setVersion(parsed.version)
        syncStore.setCatalogEtag(body.etag)

        lock.withLock { cached = parsed }
        SyncResult.Updated(from = localVersion, to = parsed.version)
    }

    private fun CatalogService.toEntity() = ServiceCatalogEntity(
        serviceId = id,
        nameEn = names["en"] ?: id,
        nameJa = names["ja"] ?: names["en"] ?: id,
        nameZh = names["zh"] ?: names["en"] ?: id,
        regions = regions.joinToString(","),
        cancelUrl = cancelUrl,
        cancelUrlOverrides = cancelUrlOverrides.takeIf { it.isNotEmpty() }
            ?.let { Json.encodeToString(it) },
        manualSteps = manualSteps?.takeIf { it.isNotEmpty() }
            ?.let { Json.encodeToString(it) },
        keywords = keywords.joinToString(","),
        icon = icon,
    )

    private companion object {
        const val BUNDLED_ASSET = "cancel_urls.json"
    }
}

@kotlinx.serialization.Serializable
data class CatalogVersion(
    val version: Int,
    @kotlinx.serialization.SerialName("schema_version") val schemaVersion: Int,
)

sealed interface SyncResult {
    data object UpToDate : SyncResult
    data class Updated(val from: Int, val to: Int) : SyncResult

    /** 配信側が新しすぎる。アプリの更新が要る。 */
    data class SchemaTooNew(val remoteSchemaVersion: Int) : SyncResult

    /** 通信・パースの失敗。**アプリの動作には影響させない。** */
    data class Failed(val cause: Throwable) : SyncResult
}
