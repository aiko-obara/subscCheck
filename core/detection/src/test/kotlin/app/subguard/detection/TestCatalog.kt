package app.subguard.detection

import app.subguard.detection.catalog.Catalog

/**
 * テストは **実データの `data/cancel_urls.json`** を読む。
 *
 * テスト用のミニカタログを別に持たない。持つと、パターンを直したのにテストは
 * 古いコピーを見ていた、という事故が起きる。ビルドスクリプトが実ファイルを
 * テストリソースにコピーしている（core/detection/build.gradle.kts）。
 */
object TestCatalog {
    val real: Catalog by lazy {
        val text = checkNotNull(
            TestCatalog::class.java.getResourceAsStream("/catalog/cancel_urls.json"),
        ) { "cancel_urls.json missing from test resources — check copyCatalogForTest" }
            .bufferedReader()
            .use { it.readText() }
        Catalog.parse(text)
    }
}
