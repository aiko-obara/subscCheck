package app.subguard.detection.parse

import app.subguard.detection.catalog.Catalog
import app.subguard.detection.catalog.CatalogService
import app.subguard.detection.catalog.DetectionPattern
import app.subguard.detection.catalog.PatternKind
import app.subguard.detection.model.DetectionCandidate
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 通知テキストからサブスクの候補を組み立てる。
 *
 * **このクラスは Android に一切依存しない。**
 * `NotificationListenerService` はテキストを取り出してここに渡すだけで、
 * 判定ロジックは全部こちら側にある。おかげでエミュレータなしの JVM テストで
 * 回帰を担保できる（このアプリで最も壊れやすい部分なので、これが効く）。
 */
class NotificationParser(
    private val catalog: Catalog,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val dateParser = DateParser(zone)
    private val amountParser = AmountParser(catalog.detectionPatterns)

    private val regionByPackage: Map<String, String> =
        catalog.listenerPackages
            .mapNotNull { pkg -> pkg.regions.firstOrNull()?.let { pkg.packageName to it } }
            .toMap()

    /** このパッケージの通知を見る必要があるか。**最初かつ最重要のフィルタ。** */
    fun isWatchedPackage(packageName: String): Boolean =
        packageName in catalog.watchedPackages

    /**
     * 通知を解析する。サブスクらしさが見つからなければ null。
     *
     * @param packageName 通知元パッケージ
     * @param text        `EXTRA_TITLE` / `EXTRA_TEXT` / `EXTRA_BIG_TEXT` などを連結したもの
     * @param receivedAt  通知を受け取った時刻
     * @param locale      端末のロケール
     */
    fun parse(
        packageName: String,
        text: String,
        receivedAt: Instant,
        locale: Locale = Locale.getDefault(),
    ): DetectionCandidate? {
        if (!isWatchedPackage(packageName)) return null
        if (text.isBlank()) return null

        val language = locale.language
        val regionHint = regionByPackage[packageName]

        // サブスクらしさの判定は TRIAL_START か RENEWAL_NOTICE の一致で行う。
        // 金額だけが取れても、それは単なる決済通知かもしれない。
        val trial = firstMatch(text, packageName, language, PatternKind.TRIAL_START)
        val renewal = firstMatch(text, packageName, language, PatternKind.RENEWAL_NOTICE)
        val primary = trial ?: renewal ?: return null

        val serviceName = primary.match.groupOrNull("service")?.let(::cleanServiceName)
        val service = serviceName?.let { findService(it, language) }
            ?: findServiceByKeyword(text, language)

        val renewalAt = resolveRenewalAt(primary, text, receivedAt, language, locale)

        val amountResult = amountParser.parse(text, language, packageName, regionHint)

        return DetectionCandidate(
            packageName = packageName,
            matchedPatternId = primary.pattern.id,
            detectedAt = receivedAt,
            serviceName = service?.displayName(language) ?: serviceName,
            serviceId = service?.id,
            amount = (amountResult as? AmountResult.Found)?.money,
            ambiguousAmounts = (amountResult as? AmountResult.Ambiguous)
                ?.amountMinorByCurrency.orEmpty(),
            renewalAt = renewalAt,
            isTrial = primary.pattern.kind == PatternKind.TRIAL_START,
            confidence = confidenceOf(primary.pattern, renewalAt != null, service != null),
        )
    }

    /** 更新日時を決める。トライアルの相対表現を絶対日付より優先する。 */
    private fun resolveRenewalAt(
        primary: Matched,
        text: String,
        receivedAt: Instant,
        language: String,
        locale: Locale,
    ): Instant? {
        // 1. `7日間無料` のような日数
        primary.match.groupOrNull("trialDays")?.toIntOrNull()?.let { days ->
            if (days in 1..MAX_TRIAL_DAYS) return dateParser.fromTrialDays(days, receivedAt)
        }

        // 2. `初月無料` のような月数（パターン側が月数を持つ）
        primary.pattern.relativeMonths?.let { months ->
            return dateParser.fromTrialMonths(months, receivedAt)
        }

        // 3. 絶対日付。TRIAL_START のパターンが日付を持たない場合も、
        //    同じ通知に RENEWAL_NOTICE の日付が入っていることがある。
        val dateRaw = primary.match.groupOrNull("date")
            ?: firstMatch(text, primary.packageName, language, PatternKind.RENEWAL_NOTICE)
                ?.match?.groupOrNull("date")

        if (dateRaw != null) {
            dateParser.parseAbsolute(dateRaw, language)?.let { return it }
            dateParser.parseNumericSlashDate(dateRaw, locale)?.let { return it }
        }
        return null
    }

    /**
     * confidence はパターン固有の値を土台に、取れた情報の量で補正する。
     * 確認画面はこの値で警告文の出し方を変える。
     */
    private fun confidenceOf(
        pattern: DetectionPattern,
        hasDate: Boolean,
        hasService: Boolean,
    ): Double {
        var score = pattern.confidence
        if (!hasDate) score -= 0.2       // 日付が取れないと確認画面で手入力が要る
        if (hasService) score += 0.1     // カタログと一致したなら確度が上がる
        return score.coerceIn(0.0, 1.0)
    }

    private fun firstMatch(
        text: String,
        packageName: String,
        language: String,
        kind: PatternKind,
    ): Matched? = catalog.detectionPatterns
        .asSequence()
        .filter { it.kind == kind && it.appliesTo(packageName, language) }
        .sortedByDescending { it.confidence }
        .mapNotNull { pattern ->
            pattern.compiled.find(text)?.let { Matched(pattern, it, packageName) }
        }
        .firstOrNull()

    private fun findService(name: String, language: String): CatalogService? {
        val needle = name.lowercase().trim()
        if (needle.isEmpty()) return null
        return catalog.services.firstOrNull { service ->
            service.names.values.any { it.equals(needle, ignoreCase = true) } ||
                service.keywords.any { it.equals(needle, ignoreCase = true) }
        } ?: catalog.services.firstOrNull { service ->
            service.keywords.any { needle.contains(it.lowercase()) }
        }
    }

    /** サービス名が取れなかったとき、本文中のキーワードから引き当てる。 */
    private fun findServiceByKeyword(text: String, language: String): CatalogService? {
        val haystack = text.lowercase()
        return catalog.services
            .asSequence()
            .mapNotNull { service ->
                val hit = service.keywords
                    .filter { it.length >= MIN_KEYWORD_LENGTH }
                    .firstOrNull { haystack.contains(it.lowercase()) }
                hit?.let { service to it.length }
            }
            // 長いキーワードほど誤爆しにくいので優先する
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * キャプチャしたサービス名から、周辺の定型句を落とす。
     *
     * 正規表現側で完全に絞り切るのは現実的でないので、ここで後処理する。
     */
    private fun cleanServiceName(raw: String): String =
        raw.trim()
            .removeSuffix("の")
            .trim(' ', '　', ':', '：', '-', '–', '"', '\'', '「', '」')
            .trim()

    private data class Matched(
        val pattern: DetectionPattern,
        val match: MatchResult,
        val packageName: String,
    )

    private fun MatchResult.groupOrNull(name: String): String? =
        runCatching { groups[name]?.value?.takeIf { it.isNotBlank() } }.getOrNull()

    private companion object {
        /** これを超える「無料日数」は解析ミスとみなす。`365日間無料` は通常あり得ない。 */
        const val MAX_TRIAL_DAYS = 180

        /** これより短いキーワードは本文中の偶然一致が多いので使わない。 */
        const val MIN_KEYWORD_LENGTH = 3
    }
}
