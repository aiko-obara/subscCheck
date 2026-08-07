package app.subguard.data.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.subguard.ui.CancelPageLauncher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 通知アクションの受け口。
 *
 * BroadcastReceiver を挟むのは、**アプリの画面を経由せずに解約ページを開く**ため。
 * 通知から直接 Activity を出すとホーム画面が一瞬見えてしまい、そこで
 * ユーザーの意識が別の操作に逸れる。
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject lateinit var cancelPageLauncher: CancelPageLauncher

    override fun onReceive(context: Context, intent: Intent) {
        val subscriptionId = intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, -1L)
        if (subscriptionId >= 0) {
            NotificationManagerCompat.from(context).cancel(subscriptionId.toInt())
        }

        when (intent.action) {
            ACTION_OPEN_CANCEL_PAGE -> {
                val url = intent.getStringExtra(EXTRA_CANCEL_URL) ?: return
                cancelPageLauncher.open(context, url)
            }
            // 「継続する」。通知を消すだけで、リマインダー自体は解除しない。
            //
            // ここで is_active を落とさないのが重要。ユーザーが「継続」を選んだのは
            // 「今は解約しない」であって「もう通知は要らない」ではない。
            ACTION_DISMISS -> Unit
        }
    }

    companion object {
        const val ACTION_OPEN_CANCEL_PAGE = "app.subguard.OPEN_CANCEL_PAGE"
        const val ACTION_DISMISS = "app.subguard.DISMISS_REMINDER"

        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        const val EXTRA_CANCEL_URL = "cancel_url"

        fun openCancelPageIntent(
            context: Context,
            subscriptionId: Long,
            url: String,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            subscriptionId.toInt(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ACTION_OPEN_CANCEL_PAGE
                putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
                putExtra(EXTRA_CANCEL_URL, url)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun dismissIntent(context: Context, subscriptionId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                DISMISS_REQUEST_BASE + subscriptionId.toInt(),
                Intent(context, ReminderActionReceiver::class.java).apply {
                    action = ACTION_DISMISS
                    putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private const val DISMISS_REQUEST_BASE = 2_000_000
    }
}
