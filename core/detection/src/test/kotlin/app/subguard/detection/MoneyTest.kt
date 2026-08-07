package app.subguard.detection

import app.subguard.detection.model.Money
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `JPY has no fraction digits`() {
        val m = Money.ofMajor("1,590", "JPY")!!
        assertEquals(1590, m.minor)
        assertEquals(0, m.fractionDigits)
        assertEquals("1590", m.toMajor().toPlainString())
    }

    @Test
    fun `USD keeps two fraction digits`() {
        val m = Money.ofMajor("15.49", "USD")!!
        assertEquals(1549, m.minor)
        assertEquals(2, m.fractionDigits)
    }

    @Test
    fun `CNY keeps two fraction digits`() {
        val m = Money.ofMajor("68.00", "CNY")!!
        assertEquals(6800, m.minor)
    }

    /**
     * 同じ数字でも通貨が違えば別の金額になる。
     * これが Float 1 本で持てない理由そのもの。
     */
    @Test
    fun `same digits differ by currency`() {
        assertEquals(12, Money.ofMajor("12", "JPY")!!.minor)
        assertEquals(1200, Money.ofMajor("12", "CNY")!!.minor)
    }

    @Test
    fun `full-width and half-width separators are both accepted`() {
        assertEquals(1590, Money.ofMajor("1,590", "JPY")!!.minor)
        assertEquals(1590, Money.ofMajor("1，590", "JPY")!!.minor)
        assertEquals(1590, Money.ofMajor(" 1590 ", "JPY")!!.minor)
    }

    /** 通知の表記が通貨の桁数と食い違うことは実際にある。例外にせず丸める。 */
    @Test
    fun `extra fraction digits are rounded rather than rejected`() {
        assertEquals(13, Money.ofMajor("12.50", "JPY")!!.minor)
        assertEquals(12, Money.ofMajor("12.49", "JPY")!!.minor)
    }

    @Test
    fun `garbage returns null instead of throwing`() {
        assertNull(Money.ofMajor("", "JPY"))
        assertNull(Money.ofMajor("abc", "JPY"))
        assertNull(Money.ofMajor("--", "USD"))
    }

    @Test
    fun `addition requires matching currency`() {
        val a = Money(1000, "JPY")
        val b = Money(590, "JPY")
        assertEquals(Money(1590, "JPY"), a + b)

        val fail = runCatching { Money(1000, "JPY") + Money(100, "USD") }
        assertTrue(fail.isFailure, "different currencies must not be addable")
    }

    /**
     * 表記はロケールに従う。ここで検証したいのは
     * 「日本語 UI で米ドルを表示しても ¥ にならない」こと。
     * 記号そのものは JDK の CLDR 版で揺れるため、数値部分だけを見る。
     */
    @Test
    fun `formatting follows the locale, not the string resources`() {
        val jpy = Money(1590, "JPY")
        val usd = Money(1549, "USD")

        assertTrue(jpy.format(Locale.JAPAN).contains("1,590"))
        assertTrue(usd.format(Locale.US).contains("15.49"))

        // 日本語ロケールで米ドルを表示しても、金額が円として解釈されてはいけない
        val usdInJapanese = usd.format(Locale.JAPAN)
        assertTrue(usdInJapanese.contains("15.49"), "actual: $usdInJapanese")
    }

    @Test
    fun `currency code must be ISO 4217 shaped`() {
        assertTrue(runCatching { Money(1, "JP") }.isFailure)
        assertTrue(runCatching { Money(1, "JAPAN") }.isFailure)
    }
}
