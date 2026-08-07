package app.subguard.detection

import app.subguard.detection.parse.AmountParser
import app.subguard.detection.parse.AmountResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AmountParserTest {

    private val parser = AmountParser(TestCatalog.real.detectionPatterns)

    private fun found(text: String, language: String, region: String? = null): AmountResult.Found {
        val r = parser.parse(text, language, regionHint = region)
        return assertIs<AmountResult.Found>(r, "expected Found for '$text', got $r")
    }

    // ---------- 通貨が確定するケース ----------

    @Test
    fun `japanese yen with suffix`() {
        val m = found("1,590円 が請求されます", "ja").money
        assertEquals(1590, m.minor)
        assertEquals("JPY", m.currency)
    }

    @Test
    fun `us dollar with symbol`() {
        val m = found("You were charged \$15.49 today", "en").money
        assertEquals(1549, m.minor)
        assertEquals("USD", m.currency)
    }

    @Test
    fun `us dollar with iso code`() {
        val m = found("USD 9.99 per month", "en").money
        assertEquals(999, m.minor)
        assertEquals("USD", m.currency)
    }

    @Test
    fun `chinese yuan with suffix`() {
        val m = found("已扣费 68.00 元", "zh").money
        assertEquals(6800, m.minor)
        assertEquals("CNY", m.currency)
    }

    // ---------- ¥ の曖昧性 ----------

    /**
     * `¥` は日本円と人民元の両方で使われる。
     * 記号だけでは決まらないので、文脈で確定させる。
     */
    @Test
    fun `yen sign resolves to JPY for a japanese locale`() {
        val r = found("¥1,590 のお支払いが完了しました", "ja")
        assertEquals("JPY", r.money.currency)
        assertEquals(1590, r.money.minor)
        assertTrue(r.ambiguous, "resolution came from context, so it must be flagged")
    }

    @Test
    fun `yen sign resolves to CNY for a chinese locale`() {
        val r = found("￥25.00 已支付", "zh")
        assertEquals("CNY", r.money.currency)
        assertEquals(2500, r.money.minor)
    }

    /** パッケージの地域は端末の言語より強い手がかり。 */
    @Test
    fun `region hint beats the device language`() {
        val r = found("¥25.00", "en", region = "CN")
        assertEquals("CNY", r.money.currency)
    }

    /**
     * 同じ文に確定トークンがあれば、そちらが勝つ。
     * 端末が日本語でも「元」と書いてあれば人民元。
     */
    @Test
    fun `explicit token beats the locale`() {
        val r = found("12元 が引き落とされました", "ja")
        assertEquals("CNY", r.money.currency)
        assertEquals(1200, r.money.minor)
    }

    /**
     * 通貨を決められないときは **推測で登録しない**。
     * 候補を返して、確認画面でユーザーに選ばせる。
     */
    @Test
    fun `unresolvable currency returns candidates instead of guessing`() {
        val r = parser.parse("¥12.50", "en")
        val amb = assertIs<AmountResult.Ambiguous>(r)
        assertEquals(setOf("JPY", "CNY"), amb.amountMinorByCurrency.keys)

        // 通貨ごとに桁数が違うので、それぞれ正しく解釈されていること
        assertEquals(13, amb.amountMinorByCurrency["JPY"]!!.minor)   // 0 桁なので丸め
        assertEquals(1250, amb.amountMinorByCurrency["CNY"]!!.minor) // 2 桁なのでそのまま
    }

    /**
     * 曖昧な候補を別通貨として解釈し直すとき、生文字列から作り直していること。
     * Money を経由して往復させると `¥12.50` の小数が落ちて 13 → 1300 になる。
     */
    @Test
    fun `re-interpretation does not lose fractional digits`() {
        val r = parser.parse("¥12.50", "en")
        val amb = assertIs<AmountResult.Ambiguous>(r)
        assertEquals(
            1250,
            amb.amountMinorByCurrency["CNY"]!!.minor,
            "fraction was lost by round-tripping through JPY",
        )
    }

    // ---------- 見つからないケース ----------

    @Test
    fun `no amount returns NotFound`() {
        assertIs<AmountResult.NotFound>(parser.parse("ご注文の商品が発送されました", "ja"))
        assertIs<AmountResult.NotFound>(parser.parse("Your package has been delivered", "en"))
    }

    /**
     * 「無料」を金額ゼロとして拾うパターンは意図的に置いていない。
     * 拾うと「7日間無料体験後 ¥1,590/月」で 0 円が勝ってしまう。
     * トライアル中かどうかは DetectionCandidate.isTrial で表す。
     */
    @Test
    fun `trial wording does not shadow the real price`() {
        val m = found("7日間無料体験後、月額1,590円", "ja").money
        assertEquals(1590, m.minor, "the actual price must win over the word 無料")
    }
}
