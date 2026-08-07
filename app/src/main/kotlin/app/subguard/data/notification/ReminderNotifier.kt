package app.subguard.data.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.subguard.MainActivity
import app.subguard.R
import app.subguard.data.local.SubscriptionEntity
import app.subguard.detection.model.Money
import app.subguard.detection.model.Urgency
import app.subguard.detection.model.urgencyOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 締切前のリマインダー通知を組み立てる。
 *
 * 「解約ページへ」からは **アプリを経由せず**ブラウザを開く。
 * ホーム画面を挟むと、そこで気が変わったり別の操作に流れたりする。
 * ユーザーがやりたいのは解約であって、アプリを見ることではない。
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyReminder(subscription: SubscriptionEntity, offsetHours: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val renewalAt = Instant.ofEpochMilli(subscription.renewalDate)
        val urgency = urgencyOf(renewalAt, Instant.now())

        val channelId = when (urgency) {
            Urgency.CRITICAL -> Channels.REMINDER_URGENT
            else -> Channels.REMINDER_NORMAL
        }

        val locale = Locale.getDefault()
        val amount = Money(subscription.amountMinor, subscription.currency).format(locale)

        val title = when (offsetHours) {
            3 -> context.getString(R.string.reminder_title_hours, subscription.serviceName, 3)
            else -> context.getString(R.string.reminder_title_tomorrow, subscription.serviceName)
        }
        val body = context.getString(R.string.reminder_body, amount)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openDetail(subscription.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(
                if (urgency == Urgency.CRITICAL) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )

        // ロック画面での情報の出し方。
        //
        // 内容を全部出せば到達力は最大になるが、他人に画面を見られると加入サービスが漏れる。
        // 設定で切り替えられるようにし、隠す側を選んだときは公開版に差し替える。
        // 既定値はユーザーテストで決める（docs/reminder-and-navigation.md）。
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        builder.setPublicVersion(
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle(context.getString(R.string.reminder_public_title))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build(),
        )

        subscription.cancelUrl?.let { url ->
            builder.addAction(
                0,
                context.getString(R.string.action_open_cancel_page),
                ReminderActionReceiver.openCancelPageIntent(context, subscription.id, url),
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.action_keep_subscription),
            ReminderActionReceiver.dismissIntent(context, subscription.id),
        )

        runCatching {
            manager.notify(subscription.id.toInt(), builder.build())
        }
        // POST_NOTIFICATIONS が剥奪された直後は SecurityException が飛びうる。
        // 通知を出せないこと自体は致命的でないので、クラッシュさせない。
    }

    private fun openDetail(subscriptionId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            subscriptionId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
