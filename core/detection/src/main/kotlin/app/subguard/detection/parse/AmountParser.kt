package app.subguard.detection.parse

import app.subguard.detection.catalog.DetectionPattern
import app.subguard.detection.catalog.PatternKind
import app.subguard.detection.model.Money

/**
 * 通知テキストから金額を取り出す。
 *
 * 正規表現はカタログ（データ）から来るが、**通貨の確定はコードで行う**。
 * `¥` が日本円と人民元の両方で使われる以上、記号だけでは決まらないため。
 */
class AmountParser(
    private val patterns: List<DetectionPattern>,
) {
    private val amountPatterns = patterns.filter { it.kind == PatternKind.AMOUNT }

    /**
     * @param text          通知本文
     * @param language      端末の言語コード（`ja` / `en` / `zh`）
     * @param packageName   通知元パッケージ。地域の手がかりに使う
     * @param regionHint    パッケージから引ける地域（`JP` / `US` / `CN`）。不明なら null
     */
    fun parse(
        text: String,
        language: String,
        packageName: String = "",
        regionHint: String? = null,
    ): AmountResult {
        val candidates = mutableListOf<AmountCandidate>()

        for (pattern in amountPatterns) {
            if (!pattern.appliesTo(packageName, language)) continue
            val match = pattern.compiled.find(text) ?: continue
            val currency = pattern.currency ?: continue

            val raw = match.groupOrNull("amount") ?: continue
            val money = Money.ofMajor(raw, currency) ?: continue

            candidates += AmountCandidate(
                // 生文字列を保持する。曖昧な `¥` を後から別通貨として解釈し直すとき、
                // Money 経由で往復させると小数が丸められて情報が落ちる
                // （`¥12.50` を JPY で解釈すると 13 になり、CNY に直しても 12.50 に戻らない）。
                raw = raw,
                money = money,
                pattern = pattern,
                ambiguous = pattern.ambiguousSymbol,
                matchStart = match.range.first,
            )
        }

        if (candidates.isEmpty()) return AmountResult.NotFound

        // 曖昧でないものが 1 つでもあればそれを採る。
        // 「¥12.00（曖昧）」と「12元（確定）」が同じ文に出たら後者が正しい。
        candidates.firstOrNull { !it.ambiguous }?.let {
            return AmountResult.Found(it.money, ambiguous = false)
        }

        // 以降はすべて曖昧な記号（¥）。文脈から通貨を推定する。
        val best = candidates.minByOrNull { it.matchStart }!!
        val resolved = resolveAmbiguousCurrency(text, language, regionHint)
            ?: return AmountResult.Ambiguous(
                // 桁数が通貨で違うため、必ず生文字列から解釈し直す
                amountMinorByCurrency = AMBIGUOUS_CURRENCIES.mapNotNull { code ->
                    Money.ofMajor(best.raw, code)?.let { code to it }
                }.toMap(),
            )

        val money = Money.ofMajor(best.raw, resolved) ?: return AmountResult.NotFound
        return AmountResult.Found(money, ambiguous = true)
    }

    /**
     * `¥` の通貨を文脈から決める。決められなければ null を返し、
     * 呼び出し側はユーザーに選ばせる（推測で登録しない）。
     *
     * 優先順位:
     *  1. 同じテキスト内の明示的なトークン（`円` / `元` / ISO コード）
     *  2. 通知元パッケージの地域
     *  3. 端末の言語
     */
    private fun resolveAmbiguousCurrency(
        text: String,
        language: String,
        regionHint: String?,
    ): String? {
        EXPLICIT_CURRENCY_TOKENS.forEach { (token, code) ->
            if (text.contains(token, ignoreCase = true)) return code
        }
        when (regionHint) {
            "JP" -> return "JPY"
            "CN" -> return "CNY"
        }
        return when (language) {
            "ja" -> "JPY"
            "zh" -> "CNY"
            else -> null
        }
    }

    private fun MatchResult.groupOrNull(name: String): String? =
        runCatching { groups[name]?.value }.getOrNull()

    private data class AmountCandidate(
        val raw: String,
        val money: Money,
        val pattern: DetectionPattern,
        val ambiguous: Boolean,
        val matchStart: Int,
    )

    private companion object {
        val AMBIGUOUS_CURRENCIES = listOf("JPY", "CNY")

        /** テキスト中にあれば通貨が確定するトークン。順序に意味がある。 */
        val EXPLICIT_CURRENCY_TOKENS = listOf(
            "円" to "JPY",
            "JPY" to "JPY",
            "元" to "CNY",
            "CNY" to "CNY",
            "人民币" to "CNY",
        )
    }
}

/** [AmountParser] の結果。 */
sealed interface AmountResult {
    /** 金額が取れた。[ambiguous] が true なら記号が曖昧で文脈から推定した。 */
    data class Found(val money: Money, val ambiguous: Boolean) : AmountResult

    /**
     * 金額の数値は取れたが通貨を確定できなかった。
     * **推測で登録せず、確認画面でユーザーに選ばせる。**
     */
    data class Ambiguous(val amountMinorByCurrency: Map<String, Money>) : AmountResult

    /** 金額が見つからなかった。 */
    data object NotFound : AmountResult
}
