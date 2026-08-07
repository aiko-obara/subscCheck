package app.subguard.data.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.subguard.MainActivity
import app.subguard.R
import app.subguard.detection.model.DetectionCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「新しいサブスクを検知しました」の通知。
 *
 * この通知の役割は**確認画面へ連れて行くこと**であって、登録の完了を告げることではない。
 * 文言も「検知しました。確認してください」であり、「登録しました」ではない。
 */
@Singleton
class DetectionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyDetected(eventId: Long, candidate: DetectionCandidate) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val serviceName = candidate.serviceName
            ?: context.getString(R.string.detection_unknown_service)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_DETECTION_EVENT_ID, eventId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, Channels.DETECTION)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(R.string.detection_title, serviceName))
            .setContentText(context.getString(R.string.detection_body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            // ロック画面には出さない。検知した瞬間は緊急でないうえ、
            // 加入しようとしているサービス名が他人に見えるのは避けたい。
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        runCatching { manager.notify(DETECTION_NOTIFICATION_BASE + eventId.toInt(), notification) }
    }

    private companion object {
        const val DETECTION_NOTIFICATION_BASE = 1_000_000
    }
}
