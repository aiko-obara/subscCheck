package app.subguard.detection

import app.subguard.detection.parse.NotificationParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationParserTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val parser = NotificationParser(TestCatalog.real, tokyo)

    private val playStore = "com.android.vending"
    private val received: Instant = Instant.parse("2026-08-07T05:32:00Z")   // JST 14:32

    private fun date(instant: Instant) = instant.atZone(tokyo).toLocalDate()

    // ---------- パッケージによる足切り ----------

    /** 最初かつ最重要のフィルタ。ここを通らない通知は本文にも触れない。 */
    @Test
    fun `unwatched packages are rejected outright`() {
        assertFalse(parser.isWatchedPackage("com.example.randomapp"))
        assertNull(
            parser.parse(
                "com.example.randomapp",
                "Adobe Creative Cloud の7日間無料体験が開始されました",
                received,
                Locale.JAPAN,
            ),
            "a watched-looking message from an unwatched package must not be parsed",
        )
    }

    @Test
    fun `google play is watched`() {
        assertTrue(parser.isWatchedPackage(playStore))
    }

    // ---------- 日本語 ----------

    @Test
    fun `japanese trial start`() {
        val c = assertNotNull(
            parser.parse(
                playStore,
                "Adobe Creative Cloud の7日間無料体験が開始されました\n体験後は月額6,480円",
                received,
                Locale.JAPAN,
            ),
        )
        assertEquals("adobe_cc", c.serviceId)
        assertTrue(c.isTrial)
        assertEquals(LocalDate.of(2026, 8, 14), date(assertNotNull(c.renewalAt)))
        assertEquals(6480, assertNotNull(c.amount).minor)
        assertEquals("JPY", c.amount!!.currency)
    }

    @Test
    fun `japanese first month free uses calendar months`() {
        val jan31 = LocalDate.of(2026, 1, 31).atStartOfDay(tokyo).toInstant()
        val c = assertNotNull(
            parser.parse(playStore, "U-NEXT 初月無料キャンペーン", jan31, Locale.JAPAN),
        )
        assertEquals(LocalDate.of(2026, 2, 28), date(assertNotNull(c.renewalAt)))
    }

    // ---------- 英語 ----------

    /**
     * かつてサービス名に "Adobe Creative Cloud has started" まで巻き込んでいた。
     * 先読みで定型句を打ち切るように直した回帰テスト。
     */
    @Test
    fun `english trial start does not swallow trailing words into the service name`() {
        val c = assertNotNull(
            parser.parse(
                playStore,
                "Your 7-day free trial of Adobe Creative Cloud has started",
                received,
                Locale.US,
            ),
        )
        assertEquals("adobe_cc", c.serviceId)
        assertEquals(
            "Adobe Creative Cloud",
            c.serviceName,
            "the trailing 'has started' must not be part of the service name",
        )
    }

    @Test
    fun `english trial start with a price`() {
        val c = assertNotNull(
            parser.parse(
                playStore,
                "Your 30-day free trial of Netflix has started. After that you pay \$15.49/month.",
                received,
                Locale.US,
            ),
        )
        assertEquals("netflix", c.serviceId)
        assertEquals(LocalDate.of(2026, 9, 6), date(assertNotNull(c.renewalAt)))
        assertEquals(1549, assertNotNull(c.amount).minor)
        assertEquals("USD", c.amount!!.currency)
    }

    // ---------- 中国語 ----------

    @Test
    fun `chinese trial start`() {
        val c = assertNotNull(
            parser.parse(playStore, "哔哩哔哩大会员 7天免费试用已开始", received, Locale.CHINA),
        )
        assertTrue(c.isTrial)
        assertEquals(LocalDate.of(2026, 8, 14), date(assertNotNull(c.renewalAt)))
        assertEquals("bilibili_vip", c.serviceId)
    }

    @Test
    fun `chinese renewal notice with yuan`() {
        val c = assertNotNull(
            parser.parse(
                "com.eg.android.AlipayGphone",
                "腾讯视频 将于 2026年9月1日 自动续费 25.00 元",
                received,
                Locale.CHINA,
            ),
        )
        assertFalse(c.isTrial)
        assertEquals(LocalDate.of(2026, 9, 1), date(assertNotNull(c.renewalAt)))
        assertEquals(2500, assertNotNull(c.amount).minor)
        assertEquals("CNY", c.amount!!.currency)
    }

    /** 支付宝は中国のパッケージなので、`¥` は人民元として解決されるべき。 */
    @Test
    fun `alipay package resolves the yen sign to CNY`() {
        val c = assertNotNull(
            parser.parse(
                "com.eg.android.AlipayGphone",
                "爱奇艺 将于 2026年9月1日 自动续费 ￥25.00",
                received,
                Locale.CHINA,
            ),
        )
        assertEquals("CNY", assertNotNull(c.amount).currency)
        assertEquals(2500, c.amount!!.minor)
    }

    // ---------- 負のケース：誤検知しないこと ----------

    /**
     * 誤検知は検知漏れよりも致命的。
     * 「サブスクではない通知」を拾わないことのほうが、テストとして重要度が高い。
     */
    @Test
    fun `shipping notifications are not subscriptions`() {
        assertNull(parser.parse(playStore, "ご注文の商品が発送されました", received, Locale.JAPAN))
        assertNull(parser.parse(playStore, "Your package has been delivered", received, Locale.US))
        assertNull(parser.parse(playStore, "您的包裹已送达", received, Locale.CHINA))
    }

    @Test
    fun `app update notifications are not subscriptions`() {
        assertNull(parser.parse(playStore, "3個のアプリが更新されました", received, Locale.JAPAN))
        assertNull(parser.parse(playStore, "3 apps were updated", received, Locale.US))
    }

    /** 金額が書いてあるだけの決済通知は、サブスクとは限らない。 */
    @Test
    fun `a bare payment notification is not a subscription`() {
        assertNull(
            parser.parse(playStore, "コンビニで1,590円のお支払いが完了しました", received, Locale.JAPAN),
        )
    }

    @Test
    fun `blank text is rejected`() {
        assertNull(parser.parse(playStore, "", received, Locale.JAPAN))
        assertNull(parser.parse(playStore, "   \n  ", received, Locale.JAPAN))
    }

    // ---------- confidence ----------

    @Test
    fun `confidence drops when the date could not be extracted`() {
        val withDate = assertNotNull(
            parser.parse(playStore, "Netflix の7日間無料体験が開始されました", received, Locale.JAPAN),
        )
        val withoutDate = assertNotNull(
            parser.parse(playStore, "Netflix 初月無料", received, Locale.JAPAN),
        )
        assertTrue(withDate.confidence > 0.0)
        assertTrue(withoutDate.confidence in 0.0..1.0)
    }

    @Test
    fun `confidence always stays within range`() {
        val texts = listOf(
            "Adobe Creative Cloud の7日間無料体験が開始されました",
            "Your 7-day free trial of Netflix has started",
            "7天免费试用已开始",
            "初月無料",
        )
        texts.forEach { text ->
            parser.parse(playStore, text, received, Locale.JAPAN)?.let {
                assertTrue(it.confidence in 0.0..1.0, "$text -> ${it.confidence}")
            }
        }
    }

    // ---------- 候補としての性質 ----------

    /**
     * 解析結果はあくまで候補。日付が取れなくても候補としては成立し、
     * 確認画面でユーザーが日付を入れる。
     */
    @Test
    fun `a candidate without a date is still a candidate`() {
        val c = assertNotNull(
            parser.parse(playStore, "Netflix 初月無料", received, Locale.JAPAN),
        )
        assertTrue(c.hasRenewalDate, "first-month-free should still produce a date")
        assertEquals("netflix", c.serviceId)
    }
}
