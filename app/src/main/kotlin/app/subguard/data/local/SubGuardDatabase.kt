package app.subguard.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        SubscriptionEntity::class,
        DetectionEventEntity::class,
        ReminderLogEntity::class,
        ServiceCatalogEntity::class,
    ],
    version = 1,
    // スキーマ JSON を app/schemas/ に出力してリポジトリにコミットする。
    // これがないと自動マイグレーションもマイグレーションテストも書けない。
    exportSchema = true,
)
@TypeConverters(SubGuardConverters::class)
abstract class SubGuardDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun detectionEventDao(): DetectionEventDao
    abstract fun reminderLogDao(): ReminderLogDao
    abstract fun serviceCatalogDao(): ServiceCatalogDao

    companion object {
        const val NAME = "subguard.db"
    }
}

/**
 * enum を文字列で保存する。序数ではなく名前を使うのは、
 * 後から enum に値を挿入したときに既存データの意味が変わらないようにするため。
 */
class SubGuardConverters {
    @TypeConverter
    fun toSource(value: String): SubscriptionSource = SubscriptionSource.valueOf(value)

    @TypeConverter
    fun fromSource(value: SubscriptionSource): String = value.name

    @TypeConverter
    fun toStatus(value: String): DetectionStatus = DetectionStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(value: DetectionStatus): String = value.name
}
