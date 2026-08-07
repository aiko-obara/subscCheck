package app.subguard.detection.model

import java.time.Duration
import java.time.Instant

/**
 * 締切までの残り時間による緊急度。
 *
 * **この値を永続化しないこと。** 時刻経過で必ず陳腐化し、
 * 「更新するための定期処理」という不要な複雑さを呼び込む。
 * `renewal_date` と現在時刻から毎回導出する。算出コストはゼロに等しい。
 */
enum class Urgency {
    /** 7 日より先。通知は出さない。 */
    SAFE,

    /** 24 時間以内。`reminder_normal` チャネル。 */
    SOON,

    /** 3 時間以内。`reminder_urgent` チャネル。 */
    CRITICAL,
    ;

    companion object {
        val SOON_THRESHOLD: Duration = Duration.ofHours(24)
        val CRITICAL_THRESHOLD: Duration = Duration.ofHours(3)

        /**
         * リマインダーを送るオフセット。締切の何時間前に通知するか。
         * `ReminderScheduler` はこの値をそのまま使う。
         */
        val REMINDER_OFFSETS_HOURS: List<Long> = listOf(24, 3)
    }
}

/**
 * 締切までの残り時間から緊急度を求める。
 *
 * 締切を過ぎている場合も [Urgency.CRITICAL] を返す。「過ぎたので安全」ではなく
 * 「もう課金されているかもしれない」状態であり、ユーザーに最も強く見せるべきだから。
 */
fun urgencyOf(renewalAt: Instant, now: Instant): Urgency {
    val left = Duration.between(now, renewalAt)
    return when {
        left <= Urgency.CRITICAL_THRESHOLD -> Urgency.CRITICAL
        left <= Urgency.SOON_THRESHOLD -> Urgency.SOON
        else -> Urgency.SAFE
    }
}
