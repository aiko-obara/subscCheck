package app.subguard.detection

import app.subguard.detection.parse.DateParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DateParserTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val parser = DateParser(tokyo)

    private fun localDateOf(instant: Instant): LocalDate =
        instant.atZone(tokyo).toLocalDate()

    // ---------- 絶対日付 ----------

    @Test
    fun `parses japanese absolute date`() {
        val at = parser.parseAbsolute("2026年9月1日", "ja")
        assertEquals(LocalDate.of(2026, 9, 1), localDateOf(assertNotNull(at)))
    }

    @Test
    fun `parses slash and hyphen forms`() {
        assertEquals(
            LocalDate.of(2026, 9, 1),
            localDateOf(assertNotNull(parser.parseAbsolute("2026/09/01", "ja"))),
        )
        assertEquals(
            LocalDate.of(2026, 9, 1),
            localDateOf(assertNotNull(parser.parseAbsolute("2026-09-01", "en"))),
        )
    }

    @Test
    fun `parses chinese absolute date`() {
        val at = parser.parseAbsolute("2026年9月1日", "zh")
        assertEquals(LocalDate.of(2026, 9, 1), localDateOf(assertNotNull(at)))
    }

    @Test
    fun `rejects impossible dates instead of rolling over`() {
        assertNull(parser.parseAbsolute("2026年2月30日", "ja"))
        assertNull(parser.parseAbsolute("2026-13-01", "en"))
    }

    // ---------- 米国式と欧州式の曖昧性 ----------

    /**
     * `01/09/2026` は米国式なら 1 月 9 日、欧州式なら 9 月 1 日。
     * ロケールが決まれば解釈できる。
     */
    @Test
    fun `numeric slash date follows the locale`() {
        val us = parser.parseNumericSlashDate("01/09/2026", Locale.US)
        assertEquals(LocalDate.of(2026, 1, 9), localDateOf(assertNotNull(us)))

        val uk = parser.parseNumericSlashDate("01/09/2026", Locale.UK)
        assertEquals(LocalDate.of(2026, 9, 1), localDateOf(assertNotNull(uk)))
    }

    /**
     * 国が分からず、どちらの解釈も成立してしまう場合は **null を返す**。
     * 曖昧なまま登録すると、ユーザーは 1 か月ずれた通知を受け取ることになる。
     */
    @Test
    fun `ambiguous numeric date without a country returns null`() {
        assertNull(parser.parseNumericSlashDate("01/09/2026", Locale.ENGLISH))
    }

    /** 片方の解釈しか成立しないなら、国が不明でも決まる。 */
    @Test
    fun `unambiguous numeric date resolves without a country`() {
        val at = parser.parseNumericSlashDate("25/09/2026", Locale.ENGLISH)
        assertEquals(LocalDate.of(2026, 9, 25), localDateOf(assertNotNull(at)))
    }

    // ---------- 年のない日付 ----------

    @Test
    fun `month-day without a year never resolves to the past`() {
        val today = LocalDate.now(tokyo)
        val at = assertNotNull(parser.parseAbsolute("1月1日", "ja"))
        val parsed = localDateOf(at)
        assert(!parsed.isBefore(today)) { "expected $parsed to be today or later ($today)" }
    }

    @Test
    fun `parses english month names`() {
        val at = assertNotNull(parser.parseAbsolute("Sep 1", "en"))
        assertEquals(9, localDateOf(at).monthValue)
        assertEquals(1, localDateOf(at).dayOfMonth)
    }

    // ---------- 相対表現 ----------

    @Test
    fun `trial days are added to the received time`() {
        val received = Instant.parse("2026-08-07T05:32:00Z")   // JST 14:32
        val end = parser.fromTrialDays(3, received)
        assertEquals(LocalDate.of(2026, 8, 10), localDateOf(end))
        // 時刻はそのまま保つ
        assertEquals(14, end.atZone(tokyo).hour)
        assertEquals(32, end.atZone(tokyo).minute)
    }

    /**
     * 「初月無料」は 30 日固定ではなく 1 か月。
     * 1/31 の 1 か月後は 2/28（うるう年は 2/29）で、30 日足すと 3/2 になってしまう。
     */
    @Test
    fun `first month free uses calendar months, not 30 days`() {
        val received = LocalDate.of(2026, 1, 31).atStartOfDay(tokyo).toInstant()
        val end = parser.fromTrialMonths(1, received)
        assertEquals(LocalDate.of(2026, 2, 28), localDateOf(end))

        val leap = LocalDate.of(2028, 1, 31).atStartOfDay(tokyo).toInstant()
        assertEquals(LocalDate.of(2028, 2, 29), localDateOf(parser.fromTrialMonths(1, leap)))
    }

    /**
     * タイムゾーンをまたぐと日付が 1 日ずれる。
     * UTC で保存し、端末のゾーンで解釈するという前提が守られているかを確認する。
     */
    @Test
    fun `same instant lands on different dates in different zones`() {
        // JST では 8/8 の 08:00、UTC ではまだ 8/7 の 23:00
        val instant = Instant.parse("2026-08-07T23:00:00Z")

        val jst = DateParser(ZoneId.of("Asia/Tokyo"))
        val utc = DateParser(ZoneId.of("UTC"))

        val fromJst = jst.fromTrialDays(0, instant).atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()
        val fromUtc = utc.fromTrialDays(0, instant).atZone(ZoneId.of("UTC")).toLocalDate()

        assertEquals(LocalDate.of(2026, 8, 8), fromJst)
        assertEquals(LocalDate.of(2026, 8, 7), fromUtc)
    }
}
