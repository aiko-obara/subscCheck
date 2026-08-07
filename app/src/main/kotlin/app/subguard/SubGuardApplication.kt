package app.subguard

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.subguard.data.notification.NotificationChannelInitializer
import app.subguard.data.work.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class SubGuardApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var channels: NotificationChannelInitializer
    @Inject lateinit var scheduler: ReminderScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * WorkManager を自前で初期化する。
     *
     * manifest 側で `WorkManagerInitializer` を `tools:node="remove"` しておくこと。
     * 消し忘れると、既定のファクトリで Worker が生成されて注入が効かず、実行時に落ちる。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // チャネル名は作成時のロケールで固定されるため、起動のたびに作り直して
        // 現在の言語に合わせる。
        channels.ensureChannels()

        scope.launch {
            // 起動は最も確実に Work を再武装できる機会。日次の定期実行に加えてここでも回す。
            scheduler.ensureReconcileWorker()
            scheduler.ensureCatalogSyncWorker()
        }
    }
}
