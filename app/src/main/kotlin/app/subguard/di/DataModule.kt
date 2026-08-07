package app.subguard.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import app.subguard.data.local.DatabasePassphrase
import app.subguard.data.local.DetectionEventDao
import app.subguard.data.local.ReminderLogDao
import app.subguard.data.local.ServiceCatalogDao
import app.subguard.data.local.SubGuardDatabase
import app.subguard.data.local.SubscriptionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphrase: DatabasePassphrase,
    ): SubGuardDatabase {
        // SQLCipher はネイティブライブラリのロードが必要。
        System.loadLibrary("sqlcipher")

        val key = passphrase.getOrCreate()
        return Room.databaseBuilder(context, SubGuardDatabase::class.java, SubGuardDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(key))
            // fallbackToDestructiveMigration は使わない。
            // ユーザーのサブスクデータが消えることは、このアプリでは
            // 「課金を防げなかった」と同義の致命的な障害。
            .build()
    }

    @Provides
    @Singleton
    fun providePassphrase(@ApplicationContext context: Context) = DatabasePassphrase(context)

    @Provides fun provideSubscriptionDao(db: SubGuardDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun provideDetectionEventDao(db: SubGuardDatabase): DetectionEventDao = db.detectionEventDao()
    @Provides fun provideReminderLogDao(db: SubGuardDatabase): ReminderLogDao = db.reminderLogDao()
    @Provides fun provideServiceCatalogDao(db: SubGuardDatabase): ServiceCatalogDao = db.serviceCatalogDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /**
     * カタログ取得専用の OkHttp。
     *
     * インターセプタもロガーも足さない。**このクライアントが触るのは
     * 公開された JSON 2 本だけ**であり、それ以外の用途に使い回さない。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
