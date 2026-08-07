package app.subguard.data.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.subguard.data.local.SubscriptionEntity
import app.subguard.detection.model.Urgency
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * リマインダーのスケジューリング。
 *
 * ## AlarmManager の exact alarm を使わない理由
 *
 *  - Android 12 以降 `SCHEDULE_EXACT_ALARM` は Play の制限対象で、
 *    リマインダーアプリでの利用は審査で問題になり得る
 *  - 24 時間前・3 時間前の通知に秒単位の精度は要らない
 *  - WorkManager は端末再起動をまたいで復元される。AlarmManager は
 *    BOOT_COMPLETED を自前で受けて再登録する必要があり、壊れる箇所が増える
 *
 * 精度が要らないのに制限の強い API を選ぶ理由がない。
 */
@Singleton
class ReminderScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    /**
     * 1 件のサブスクについて、全オフセットの Work を登録し直す。
     *
     * [ExistingWorkPolicy.REPLACE] を使うのが要点。ユーザーが更新日を編集したとき、
     * 古い Work が残っていると誤ったタイミングで通知が飛ぶ。同じ一意名で上書きすることで
     * 「1 サブスク × 1 オフセット = 常に 1 本」を保証する。
     */
    fun schedule(subscription: SubscriptionEntity, now: Instant = Instant.now()) {
        val renewalAt = Instant.ofEpochMilli(subscription.renewalDate)

        Urgency.REMINDER_OFFSETS_HOURS.forEach { offsetHours ->
            val fireAt = renewalAt.minus(Duration.ofHours(offsetHours))
            val delay = Duration.between(now, fireAt)

            val name = uniqueWorkName(subscription.id, offsetHours)

            if (delay.isNegative || delay.isZero) {
                // すでに過ぎている枠は登録しない。
                // 残っている古い Work があれば消しておく（更新日を先送りされた場合など）。
                workManager.cancelUniqueWork(name)
                return@forEach
            }

            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        ReminderWorker.KEY_SUBSCRIPTION_ID to subscription.id,
                        ReminderWorker.KEY_OFFSET_HOURS to offsetHours.toInt(),
                        ReminderWorker.KEY_TARGET_DATE to subscription.renewalDate,
                    ),
                )
                // 制約は付けない。ネットワークもバッテリーも要らない処理なので、
                // 付けると発火が不必要に遅れるだけ。
                .addTag(TAG_REMINDER)
                .build()

            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /** 解約済み・削除されたサブスクの Work を消す。 */
    fun cancel(subscriptionId: Long) {
        Urgency.REMINDER_OFFSETS_HOURS.forEach { offsetHours ->
            workManager.cancelUniqueWork(uniqueWorkName(subscriptionId, offsetHours))
        }
    }

    /**
     * 日次の再武装。
     *
     * Work の取りこぼしは必ず起きる（OS のプロセス強制終了、Work の期限切れ、
     * アプリ更新によるキャンセルなど）。保険として毎日全件を登録し直す。
     * [ReminderScheduler.schedule] は冪等なので、何度呼んでも安全。
     */
    fun ensureReconcileWorker() {
        val request = PeriodicWorkRequestBuilder<ReconcileRemindersWorker>(1, TimeUnit.DAYS)
            .addTag(TAG_RECONCILE)
            .build()

        workManager.enqueueUniquePeriodicWork(
            RECONCILE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** カタログ同期も日次で回す。失敗してもアプリの動作には影響しない。 */
    fun ensureCatalogSyncWorker() {
        val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(CatalogSyncWorker.constraints())
            .addTag(TAG_CATALOG_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            CATALOG_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val TAG_REMINDER = "reminder"
        const val TAG_RECONCILE = "reconcile"
        const val TAG_CATALOG_SYNC = "catalog-sync"

        const val RECONCILE_WORK_NAME = "reconcile-reminders"
        const val CATALOG_SYNC_WORK_NAME = "catalog-sync"

        fun uniqueWorkName(subscriptionId: Long, offsetHours: Long): String =
            "reminder-$subscriptionId-$offsetHours"
    }
}
