package app.subguard.detection.parse

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * 通知テキストから「いつ課金されるか」を求める。
 *
 * 絶対日付（`2026年9月1日`）と相対表現（`3日間無料`）の両方を扱う。
 *
 * **タイムゾーンの扱いに注意。** 保存は UTC epoch millis だが、
 * 計算と表示は必ず端末のタイムゾーンで行う。`Instant` と `ZonedDateTime` を
 * 混同すると日付が 1 日ずれた通知が飛ぶ。これは実際に起こりやすい。
 */
class DateParser(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * 絶対日付を解釈する。時刻が書かれていない場合は**その日の 0 時**とする。
     *
     * @param raw 正規表現で切り出した日付文字列（`2026年9月1日` / `2026/09/01` など）
     */
    fun parseAbsolute(raw: String, language: String): Instant? {
        val normalized = raw
            .replace('年', '-')
            .replace('月', '-')
            .replace('日', ' ')
            .replace('/', '-')
            .replace(Regex("\\s+"), "")
            .trim('-', ' ')

        // yyyy-M-d が取れる形にそろえてから解釈する
        val m = ISO_LIKE.find(normalized) ?: return parseWithoutYear(raw, language)
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null

        return runCatching {
            LocalDate.of(year, month, day).atStartOfDay(zone).toInstant()
        }.getOrNull()
    }

    /**
     * 年が書かれていない日付（`9月1日` / `Sep 1`）を解釈する。
     * **過去にならないよう、必要なら翌年に送る。**
     */
    private fun parseWithoutYear(raw: String, language: String): Instant? {
        val today = LocalDate.now(zone)

        MONTH_DAY_CJK.find(raw)?.let { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return null
            val day = m.groupValues[2].toIntOrNull() ?: return null
            return atNextOccurrence(month, day, today)
        }

        if (language == "en") {
            ENGLISH_MONTH_DAY.find(raw)?.let { m ->
                val month = MONTH_NAMES[m.groupValues[1].lowercase().take(3)] ?: return null
                val day = m.groupValues[2].toIntOrNull() ?: return null
                return atNextOccurrence(month, day, today)
            }
        }
        return null
    }

    private fun atNextOccurrence(month: Int, day: Int, today: LocalDate): Instant? = runCatching {
        var date = LocalDate.of(today.year, month, day)
        if (date.isBefore(today)) date = date.plusYears(1)
        date.atStartOfDay(zone).toInstant()
    }.getOrNull()

    /**
     * 英語圏の `01/09/2026` のような数値だけの日付を解釈する。
     *
     * **米国式（1月9日）と欧州式（9月1日）で意味が逆になる。**
     * ロケールから判定し、判定できない、または両方の解釈が有効な場合は null を返す。
     * 曖昧なまま登録すると、ユーザーは 1 か月ずれた通知を受け取ることになる。
     */
    fun parseNumericSlashDate(raw: String, locale: Locale): Instant? {
        val m = NUMERIC_SLASH.find(raw) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: return null

        val monthFirst = locale.country == "US"
        val (month, day) = if (monthFirst) a to b else b to a

        // どちらの解釈でも成立してしまい、かつ結果が違うなら曖昧。
        val bothPlausible = a in 1..12 && b in 1..12 && a != b
        if (bothPlausible && locale.country.isEmpty()) return null

        return runCatching {
            LocalDate.of(year, month, day).atStartOfDay(zone).toInstant()
        }.getOrNull()
    }

    /**
     * 相対表現から終了日時を求める。`3日間無料` を受信した時刻に足す。
     *
     * 時刻はそのまま保持する（`受信 14:32` + 3 日 = `3 日後の 14:32`）。
     * 実際の課金時刻は分からないので、受信時刻を基準にするのが最も無難。
     */
    fun fromTrialDays(days: Int, receivedAt: Instant): Instant =
        receivedAt.atZone(zone).plusDays(days.toLong()).toInstant()

    /**
     * 「初月無料」「first month free」から終了日時を求める。
     *
     * **`plusDays(30)` ではなく [ZonedDateTime.plusMonths] を使う。**
     * 月の長さは 28〜31 日と違うため、30 日固定では最大 1 日ずれる。
     * 1/31 に受信した「初月無料」は 2/28（うるう年なら 2/29）になる。
     */
    fun fromTrialMonths(months: Int, receivedAt: Instant): Instant =
        receivedAt.atZone(zone).plusMonths(months.toLong()).toInstant()

    private companion object {
        val ISO_LIKE = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
        val MONTH_DAY_CJK = Regex("""(\d{1,2})月(\d{1,2})日""")
        val NUMERIC_SLASH = Regex("""(\d{1,2})[/\-](\d{1,2})[/\-](\d{2,4})""")
        val ENGLISH_MONTH_DAY = Regex(
            """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})\b""",
            RegexOption.IGNORE_CASE,
        )
        val MONTH_NAMES = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )
    }
}
