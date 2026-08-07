package app.subguard.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import app.subguard.data.local.ReminderLogDao
import app.subguard.data.local.ReminderLogEntity
import app.subguard.data.local.SubscriptionDao
import app.subguard.data.notification.ReminderNotifier
import app.subguard.data.remote.CatalogRepository
import app.subguard.data.remote.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant

/**
 * 締切前のリマインダーを実際に出す Worker。
 *
 * `@HiltWorker` + `@AssistedInject` で注入する。あわせて manifest 側で
 * WorkManager の自動初期化を無効化しておくこと（消し忘れると実行時にクラッシュする）。
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subscriptionDao: SubscriptionDao,
    private val reminderLogDao: ReminderLogDao,
    private val notifier: ReminderNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val subscriptionId = inputData.getLong(KEY_SUBSCRIPTION_ID, -1L)
        val offsetHours = inputData.getInt(KEY_OFFSET_HOURS, -1)
        val targetDate = inputData.getLong(KEY_TARGET_DATE, -1L)

        if (subscriptionId < 0 || offsetHours < 0 || targetDate < 0) return Result.failure()

        val subscription = subscriptionDao.findById(subscriptionId) ?: return Result.success()

        // 解約済みになっていれば何もしない。
        if (!subscription.isActive) return Result.success()

        // 更新日が編集されていたら、この Work は古い予定に対するもの。
        // 新しい日付の Work は別途登録されているので、ここでは何もしない。
        if (subscription.renewalDate != targetDate) return Result.success()

        // 二重送信の防止。Work が再実行されることは普通にある。
        val alreadyFired = reminderLogDao.countFired(subscriptionId, offsetHours, targetDate) > 0
        if (alreadyFired) return Result.success()

        notifier.notifyReminder(subscription, offsetHours)

        reminderLogDao.insert(
            ReminderLogEntity(
                subscriptionId = subscriptionId,
                offsetHours = offsetHours,
                targetDate = targetDate,
                firedAt = Instant.now().toEpochMilli(),
                workRequestId = id.toString(),
            ),
        )
        return Result.success()
    }

    companion object {
        const val KEY_SUBSCRIPTION_ID = "subscription_id"
        const val KEY_OFFSET_HOURS = "offset_hours"
        const val KEY_TARGET_DATE = "target_date"
    }
}

/**
 * 全サブスクのリマインダーを登録し直す。
 *
 * 日次で回すほか、**アプリ起動時にも呼ぶ**。起動は最も確実に Work を再武装できる機会。
 */
@HiltWorker
class ReconcileRemindersWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subscriptionDao: SubscriptionDao,
    private val reminderLogDao: ReminderLogDao,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = Instant.now()
        subscriptionDao.activeSubscriptions().forEach { subscription ->
            scheduler.schedule(subscription, now)
            // 更新日が変わっていれば、古い日付のログは意味を失うので消す。
            // 残しておくと、次の周期で「もう送った」と誤判定されかねない。
            reminderLogDao.purgeStale(subscription.id, subscription.renewalDate)
        }
        return Result.success()
    }
}

/**
 * 解約 URL カタログを同期する。
 *
 * **失敗してもアプリは動く。** 前回同期したカタログ、それもなければ assets 同梱版で動作する。
 */
@HiltWorker
class CatalogSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val catalogRepository: CatalogRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val result = catalogRepository.sync()) {
        is SyncResult.Updated, SyncResult.UpToDate -> Result.success()

        // 配信側のスキーマが新しすぎる場合、再試行しても直らない。
        // アプリの更新が必要なので、ここで諦める。
        is SyncResult.SchemaTooNew -> Result.success()

        is SyncResult.Failed -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
