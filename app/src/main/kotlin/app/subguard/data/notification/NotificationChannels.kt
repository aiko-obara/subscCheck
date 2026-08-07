package app.subguard.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import app.subguard.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知チャネル。
 *
 * 緊急度ごとに分けているのは、ユーザーが「24 時間前は要らないが 3 時間前は欲しい」と
 * 設定できるようにするため。1 つにまとめると、切ると全部切れる。
 */
object Channels {
    /** 3 時間前。ヘッドアップ通知＋音。 */
    const val REMINDER_URGENT = "reminder_urgent"

    /** 24 時間前。 */
    const val REMINDER_NORMAL = "reminder_normal"

    /** 新しいサブスクを検知したとき。 */
    const val DETECTION = "detection"

    /** 通知アクセスが無効になった等の警告。 */
    const val SERVICE_STATUS = "service_status"
}

@Singleton
class NotificationChannelInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * チャネルを作る（既存なら名前と説明を更新する）。
     *
     * **起動のたびに呼ぶ。** チャネル名は作成時のロケールで固定され、
     * 端末の言語を変えても自動では追随しないため、毎回作り直して現在の言語に合わせる。
     * なお importance はユーザー設定が優先されるので、後から変えても反映されない。
     */
    fun ensureChannels() {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                Channels.REMINDER_URGENT,
                context.getString(R.string.channel_reminder_urgent_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_reminder_urgent_desc)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                Channels.REMINDER_NORMAL,
                context.getString(R.string.channel_reminder_normal_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_reminder_normal_desc)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                Channels.DETECTION,
                context.getString(R.string.channel_detection_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_detection_desc)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                Channels.SERVICE_STATUS,
                context.getString(R.string.channel_service_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_service_status_desc)
            },
        )
    }
}
