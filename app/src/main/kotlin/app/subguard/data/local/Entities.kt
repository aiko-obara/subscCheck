package app.subguard.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 登録元。自動検知の承認によるものか、手入力か。 */
enum class SubscriptionSource { AUTO, MANUAL }

/** 検知イベントの状態。 */
enum class DetectionStatus { PENDING, CONFIRMED, DISMISSED }

/**
 * ユーザーが管理しているサブスク。
 *
 * `renewal_date` に索引を張っているのは、一覧クエリが常にこの列でソートするため
 * （ソート UI を持たない設計なので、クエリはこの 1 本しかない）。
 */
@Entity(
    tableName = "subscriptions",
    indices = [Index("renewal_date"), Index("is_active")],
)
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** カタログのサービス ID。手入力の独自サービスは null。 */
    @ColumnInfo(name = "service_id") val serviceId: String? = null,

    @ColumnInfo(name = "service_name") val serviceName: String,

    /** 解約ページ直行 URL。不明なら null（UI はボタンを出さず手順を表示する）。 */
    @ColumnInfo(name = "cancel_url") val cancelUrl: String? = null,

    /** 次回更新／トライアル終了日時。**UTC epoch millis**。 */
    @ColumnInfo(name = "renewal_date") val renewalDate: Long,

    /**
     * 金額（マイナー単位）。¥1,590 なら 1590、$15.49 なら 1549。
     * 浮動小数を使わない理由は docs/data-model.md を参照。
     */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,

    /** ISO 4217。`amount_minor` はこの通貨の小数桁で解釈する。 */
    @ColumnInfo(name = "currency") val currency: String,

    @ColumnInfo(name = "is_trial") val isTrial: Boolean = false,
    @ColumnInfo(name = "trial_end_date") val trialEndDate: Long? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,

    @ColumnInfo(name = "source") val source: SubscriptionSource = SubscriptionSource.MANUAL,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "icon_ref") val iconRef: String? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * 通知から検知した候補。
 *
 * **通知の本文そのものは保存しない。** 抽出済みの `x_*` フィールドだけを持つ。
 * 決済通知の本文には口座末尾やカード下 4 桁が含まれることがあり、保存しなければ漏れない。
 * これはプライバシー方針であると同時に、Play の通知アクセスポリシーへの適合要件でもある。
 */
@Entity(
    tableName = "detection_events",
    indices = [Index("status"), Index("detected_at")],
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscription_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class DetectionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "matched_pattern_id") val matchedPatternId: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    @ColumnInfo(name = "status") val status: DetectionStatus = DetectionStatus.PENDING,

    @ColumnInfo(name = "subscription_id", index = true) val subscriptionId: Long? = null,

    // --- 抽出済みフィールド。ここに入るのは構造化された値だけ ---
    @ColumnInfo(name = "x_service_id") val serviceId: String? = null,
    @ColumnInfo(name = "x_service_name") val serviceName: String? = null,
    @ColumnInfo(name = "x_amount_minor") val amountMinor: Long? = null,
    @ColumnInfo(name = "x_currency") val currency: String? = null,
    @ColumnInfo(name = "x_renewal_date") val renewalDate: Long? = null,
    @ColumnInfo(name = "x_is_trial") val isTrial: Boolean = false,

    @ColumnInfo(name = "confidence") val confidence: Double = 0.0,
)

/**
 * 発火済みリマインダーの記録。二重通知の防止と、
 * 「通知が来なかった」というユーザー報告の調査に使う。
 */
@Entity(
    tableName = "reminder_log",
    indices = [Index(value = ["subscription_id", "offset_hours", "target_date"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscription_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReminderLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "subscription_id") val subscriptionId: Long,

    /** 締切の何時間前の枠か（24 / 3）。 */
    @ColumnInfo(name = "offset_hours") val offsetHours: Int,

    /**
     * 対象となった更新日時。
     *
     * これを一意制約に含めているのは、ユーザーが更新日を編集した場合に
     * 「新しい日付に対する通知」を別物として扱うため。含めないと、
     * 日付を変えたのに「もう送った」と判定されて通知が飛ばなくなる。
     */
    @ColumnInfo(name = "target_date") val targetDate: Long,

    @ColumnInfo(name = "fired_at") val firedAt: Long,
    @ColumnInfo(name = "work_request_id") val workRequestId: String? = null,
)

/**
 * 同期した解約 URL カタログのローカルキャッシュ。
 *
 * 配列を文字列に押し込んでいるのは、これが**丸ごと置き換えるだけの読み取り専用データ**だから。
 * 正規化してテーブルを分けると同期処理が複雑になるだけで、得るものがない。件数も高々数百。
 */
@Entity(tableName = "service_catalog")
data class ServiceCatalogEntity(
    @PrimaryKey
    @ColumnInfo(name = "service_id") val serviceId: String,

    @ColumnInfo(name = "name_en") val nameEn: String,
    @ColumnInfo(name = "name_ja") val nameJa: String,
    @ColumnInfo(name = "name_zh") val nameZh: String,

    /** カンマ区切り。`JP,US,GLOBAL` */
    @ColumnInfo(name = "regions") val regions: String,

    @ColumnInfo(name = "cancel_url") val cancelUrl: String? = null,

    /** ロケール別 URL の JSON 文字列。 */
    @ColumnInfo(name = "cancel_url_overrides") val cancelUrlOverrides: String? = null,

    /** アプリ内解約の手順。言語コードをキーにした JSON 文字列。 */
    @ColumnInfo(name = "manual_steps") val manualSteps: String? = null,

    /** カンマ区切り。検知とオートコンプリートに使う。 */
    @ColumnInfo(name = "keywords") val keywords: String,

    @ColumnInfo(name = "icon") val icon: String? = null,
)
