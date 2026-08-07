package app.subguard.data.remote

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * カタログの取得だけを行う HTTP クライアント。
 *
 * **このアプリが行う唯一の外部通信。** GET のみ、認証なし、識別子なし、送信ボディなし。
 * これは「データを収集しない」とデータ安全性フォームで申告できることを意味する
 * （docs/policy-compliance.md）。
 */
@Singleton
class CatalogHttpClient @Inject constructor(
    private val okHttp: OkHttpClient,
) {
    /**
     * 数十バイトの軽量ファイル。本体を毎回落とさずに更新有無を判定するために置いている。
     */
    suspend fun fetchVersion(etag: String?): HttpPayload = get(VERSION_URL, etag)

    suspend fun fetchCatalog(etag: String?): HttpPayload = get(CATALOG_URL, etag)

    private fun get(url: String, etag: String?): HttpPayload {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                // ETag を送ることで、変更がなければ 304 が返りヘッダだけで済む。
                // GitHub Raw は ETag を返す。
                etag?.let { header("If-None-Match", it) }
            }
            .build()

        okHttp.newCall(request).execute().use { response ->
            if (response.code == 304) return HttpPayload.NotModified
            if (!response.isSuccessful) {
                throw IllegalStateException("GET $url failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("GET $url returned an empty body")
            return HttpPayload.Body(text = body, etag = response.header("ETag"))
        }
    }

    private companion object {
        const val BASE =
            "https://raw.githubusercontent.com/aiko-obara/subscCheck/main/data"
        const val VERSION_URL = "$BASE/version.json"
        const val CATALOG_URL = "$BASE/cancel_urls.json"
    }
}

sealed interface HttpPayload {
    data class Body(val text: String, val etag: String?) : HttpPayload
    data object NotModified : HttpPayload
}
