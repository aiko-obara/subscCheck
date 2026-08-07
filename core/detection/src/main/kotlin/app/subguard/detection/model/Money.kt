package app.subguard.detection.model

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * 金額。**マイナー単位の整数**と ISO 4217 通貨コードで持つ。
 *
 * `Float`/`Double` を使わないのは、
 *  - 通貨ごとに小数桁が違う（JPY は 0 桁、USD/CNY は 2 桁）ため単独の数値では復元できない
 *  - 加算で誤差が出る
 *  - 通貨コードが失われると ¥1,590 と $1,590 を区別できない
 * ため。詳細は docs/data-model.md を参照。
 *
 * 例: ¥1,590 → Money(1590, "JPY") / $15.49 → Money(1549, "USD")
 */
data class Money(
    val minor: Long,
    val currency: String,
) {
    init {
        require(currency.length == 3) { "currency must be an ISO 4217 code: $currency" }
    }

    /** この通貨の小数桁数。JPY なら 0、USD なら 2。 */
    val fractionDigits: Int
        get() = runCatching { Currency.getInstance(currency).defaultFractionDigits }
            .getOrDefault(2)
            .coerceAtLeast(0)

    /** メジャー単位の値。¥1,590 なら 1590、$15.49 なら 15.49。 */
    fun toMajor(): BigDecimal = BigDecimal.valueOf(minor).movePointLeft(fractionDigits)

    /**
     * ロケールに応じた通貨表記を返す。
     *
     * 通貨記号を文字列リソースに埋め込まず必ずここを通すこと。
     * 埋め込むと、日本語 UI で米国のサブスクを表示したときに `¥15.49` になる。
     */
    fun format(locale: Locale): String {
        val cur = runCatching { Currency.getInstance(currency) }.getOrNull()
            ?: return "$minor $currency"
        return NumberFormat.getCurrencyInstance(locale)
            .apply {
                this.currency = cur
                minimumFractionDigits = cur.defaultFractionDigits.coerceAtLeast(0)
                maximumFractionDigits = cur.defaultFractionDigits.coerceAtLeast(0)
            }
            .format(toMajor())
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) {
            "cannot add different currencies: $currency + ${other.currency}"
        }
        return Money(minor + other.minor, currency)
    }

    companion object {
        /**
         * メジャー単位の文字列からつくる。`"1,590"` `"15.49"` のような
         * 通知から抜き出した生文字列を想定している。
         *
         * 桁数が通貨の既定より多い場合は切り捨てず [java.math.RoundingMode.HALF_UP] で丸める。
         * 通知の金額表記が通貨の桁数と食い違うことは実際にあるため、例外にはしない。
         */
        fun ofMajor(raw: String, currency: String): Money? {
            val normalized = raw.replace(",", "")
                .replace("，", "")   // 全角カンマ
                .replace(" ", "")
                .trim()
            val decimal = normalized.toBigDecimalOrNull() ?: return null
            val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }
                .getOrDefault(2)
                .coerceAtLeast(0)
            val minor = decimal
                .movePointRight(digits)
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .toLong()
            return Money(minor, currency)
        }

        /** 金額ゼロ（無料体験中など）。 */
        fun zero(currency: String) = Money(0, currency)
    }
}
