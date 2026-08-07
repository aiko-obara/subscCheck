package app.subguard.detection.model

import java.time.Instant

/**
 * 通知から検知したサブスクの候補。
 *
 * **これはまだ「サブスク」ではない。** `detection_events` に PENDING として積まれ、
 * ユーザーが確認画面で承認して初めて `subscriptions` に入る。
 *
 * 自動で登録しないのは、誤りのコストが非対称だから。
 *  - 検知漏れ … 手動登録すれば済む。不便だが回復可能
 *  - 誤検知   … 存在しないサブスクの通知が届く。アプリへの信頼が即座に失われる
 *
 * 詳細は docs/notification-detection.md を参照。
 */
data class DetectionCandidate(
    /** 通知元パッケージ名。 */
    val packageName: String,

    /** 一致したパターンの ID。ユーザー報告の調査に使う。 */
    val matchedPatternId: String,

    /** 通知を受け取った時刻。 */
    val detectedAt: Instant,

    /** 抽出したサービス名。取れなければ null。 */
    val serviceName: String? = null,

    /** カタログと突き合わせて特定できたサービス ID。 */
    val serviceId: String? = null,

    /** 抽出した金額。通貨を確定できなかった場合は null。 */
    val amount: Money? = null,

    /** 通貨が曖昧で確定できなかったときの候補。UI がユーザーに選ばせる。 */
    val ambiguousAmounts: Map<String, Money> = emptyMap(),

    /** 課金・更新が始まる時刻。 */
    val renewalAt: Instant? = null,

    /** 無料体験の検知か。 */
    val isTrial: Boolean = false,

    /** 0.0〜1.0。低い場合は確認画面で警告文を足す。 */
    val confidence: Double = 0.0,
) {
    /**
     * 確認画面をそのまま出せるだけの情報がそろっているか。
     * 更新日が取れていない候補は、ユーザーに日付を入力してもらう必要がある。
     */
    val hasRenewalDate: Boolean get() = renewalAt != null

    /** 通貨が曖昧なまま残っているか。UI は通貨選択を出す。 */
    val needsCurrencyChoice: Boolean get() = amount == null && ambiguousAmounts.isNotEmpty()
}
