package app.subguard.detection

import app.subguard.detection.catalog.Catalog
import app.subguard.detection.catalog.PatternKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 実データそのものの検証。
 * ここが落ちるということは、リリースすればユーザーの端末で壊れるということ。
 */
class CatalogTest {

    private val catalog = TestCatalog.real

    @Test
    fun `real catalog parses`() {
        assertTrue(catalog.services.isNotEmpty())
        assertTrue(catalog.detectionPatterns.isNotEmpty())
        assertTrue(catalog.listenerPackages.isNotEmpty())
    }

    @Test
    fun `schema version is supported`() {
        assertTrue(
            catalog.schemaVersion <= Catalog.SUPPORTED_SCHEMA_VERSION,
            "catalog schema_version=${catalog.schemaVersion} exceeds " +
                "SUPPORTED_SCHEMA_VERSION=${Catalog.SUPPORTED_SCHEMA_VERSION}",
        )
    }

    @Test
    fun `service ids are unique`() {
        val dupes = catalog.services.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate service ids: $dupes")
    }

    @Test
    fun `pattern ids are unique`() {
        val dupes = catalog.detectionPatterns.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate pattern ids: $dupes")
    }

    /**
     * 解約 URL がないサービスは、必ずアプリ内解約の手順を持つこと。
     * どちらもないと、ユーザーは詳細画面で何もできない。
     */
    @Test
    fun `services without a cancel url provide manual steps`() {
        val broken = catalog.services.filter {
            it.cancelUrl == null && it.manualSteps.isNullOrEmpty()
        }
        assertTrue(broken.isEmpty(), "no cancel path at all: ${broken.map { it.id }}")
    }

    /** 手順は 3 言語すべてで用意する。1 言語でも欠けるとその言語のユーザーが詰む。 */
    @Test
    fun `manual steps cover all three languages`() {
        catalog.services.forEach { service ->
            val steps = service.manualSteps ?: return@forEach
            listOf("ja", "en", "zh").forEach { lang ->
                assertTrue(
                    !steps[lang].isNullOrEmpty(),
                    "${service.id}: manual_steps missing '$lang'",
                )
            }
        }
    }

    @Test
    fun `service names cover all three languages`() {
        catalog.services.forEach { service ->
            listOf("ja", "en", "zh").forEach { lang ->
                assertNotNull(service.names[lang], "${service.id}: names missing '$lang'")
            }
        }
    }

    @Test
    fun `cancel urls are https`() {
        catalog.services.mapNotNull { it.cancelUrl }.forEach { url ->
            assertTrue(url.startsWith("https://"), "not https: $url")
        }
    }

    // ---------- パターン ----------

    /** 全パターンが実際にコンパイルできること。壊れた正規表現は起動時例外になる。 */
    @Test
    fun `every pattern compiles`() {
        catalog.detectionPatterns.forEach { pattern ->
            runCatching { pattern.compiled }
                .onFailure { fail("${pattern.id} failed to compile: ${it.message}") }
        }
    }

    /** AMOUNT パターンは通貨と `amount` グループを必ず持つ。 */
    @Test
    fun `amount patterns declare a currency and capture an amount`() {
        catalog.detectionPatterns
            .filter { it.kind == PatternKind.AMOUNT }
            .forEach { pattern ->
                assertNotNull(pattern.currency, "${pattern.id}: AMOUNT needs a currency")
                assertEquals(3, pattern.currency!!.length, "${pattern.id}: ISO 4217 code expected")
                assertTrue(
                    pattern.regex.contains("(?<amount>"),
                    "${pattern.id}: AMOUNT must capture a named group 'amount'",
                )
            }
    }

    /** TRIAL_START は日数か月数のどちらかで期間を出せること。 */
    @Test
    fun `trial patterns can produce a duration`() {
        catalog.detectionPatterns
            .filter { it.kind == PatternKind.TRIAL_START }
            .forEach { pattern ->
                val hasDays = pattern.regex.contains("(?<trialDays>")
                val hasMonths = pattern.relativeMonths != null
                assertTrue(
                    hasDays || hasMonths,
                    "${pattern.id}: neither trialDays nor relative_months",
                )
            }
    }

    /** RENEWAL_NOTICE は日付を取れること。 */
    @Test
    fun `renewal patterns capture a date`() {
        catalog.detectionPatterns
            .filter { it.kind == PatternKind.RENEWAL_NOTICE }
            .forEach { pattern ->
                assertTrue(
                    pattern.regex.contains("(?<date>"),
                    "${pattern.id}: RENEWAL_NOTICE must capture a named group 'date'",
                )
            }
    }

    /**
     * 曖昧フラグが立てられるのは `¥` を含むパターンだけ。
     * 立て忘れると人民元が日本円として登録される。
     */
    @Test
    fun `only yen-symbol patterns are marked ambiguous`() {
        catalog.detectionPatterns.filter { it.ambiguousSymbol }.forEach { pattern ->
            assertTrue(
                pattern.regex.contains("¥") || pattern.regex.contains("￥"),
                "${pattern.id}: marked ambiguous but has no yen sign",
            )
        }
        catalog.detectionPatterns
            .filter { (it.regex.contains("¥") || it.regex.contains("￥")) }
            .forEach { pattern ->
                assertTrue(
                    pattern.ambiguousSymbol,
                    "${pattern.id}: contains a yen sign but is not marked ambiguous",
                )
            }
    }

    @Test
    fun `confidence stays within range`() {
        catalog.detectionPatterns.forEach {
            assertTrue(it.confidence in 0.0..1.0, "${it.id}: confidence ${it.confidence}")
        }
    }
}
