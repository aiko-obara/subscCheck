package app.subguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    /**
     * 一覧の唯一のクエリ。**締切が近い順で固定**。
     *
     * 「登録順」「五十音順」といった選択肢は出さない。並び替えの自由より、
     * 開いたら一番上が一番危ない、という一貫性のほうがこのアプリでは価値が高い。
     */
    @Query("SELECT * FROM subscriptions WHERE is_active = 1 ORDER BY renewal_date ASC")
    fun observeActive(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY is_active DESC, renewal_date ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    fun observeById(id: Long): Flow<SubscriptionEntity?>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun findById(id: Long): SubscriptionEntity?

    /** 無料枠の判定に使う。 */
    @Query("SELECT COUNT(*) FROM subscriptions WHERE is_active = 1")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM subscriptions WHERE is_active = 1")
    fun observeActiveCount(): Flow<Int>

    /** リマインダーの再武装対象。 */
    @Query("SELECT * FROM subscriptions WHERE is_active = 1")
    suspend fun activeSubscriptions(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(subscription: SubscriptionEntity): Long

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    /**
     * 解約済みにする。**行は削除しない。**
     *
     * 履歴を残すことで「守れた金額」の集計に使えるようにするためと、
     * 誤操作からの復帰を可能にするため。
     */
    @Query("UPDATE subscriptions SET is_active = 0, updated_at = :now WHERE id = :id")
    suspend fun deactivate(id: Long, now: Long)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DetectionEventDao {

    @Query("SELECT * FROM detection_events WHERE status = 'PENDING' ORDER BY detected_at DESC")
    fun observePending(): Flow<List<DetectionEventEntity>>

    @Query("SELECT COUNT(*) FROM detection_events WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM detection_events WHERE id = :id")
    suspend fun findById(id: Long): DetectionEventEntity?

    @Insert
    suspend fun insert(event: DetectionEventEntity): Long

    @Query("UPDATE detection_events SET status = :status, subscription_id = :subscriptionId WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DetectionStatus, subscriptionId: Long?)

    /**
     * 同じ通知が短時間に繰り返し届いても二重に積まない。
     *
     * 通知は再投稿されることがあり（進捗更新など）、そのたびに候補が増えると
     * ユーザーは同じ確認を何度もさせられる。
     */
    @Query(
        """
        SELECT COUNT(*) FROM detection_events
        WHERE package_name = :packageName
          AND matched_pattern_id = :patternId
          AND status = 'PENDING'
          AND detected_at > :since
        """,
    )
    suspend fun countRecentDuplicates(
        packageName: String,
        patternId: String,
        since: Long,
    ): Int

    /** 古い DISMISSED は掃除する。溜めても使い道がない。 */
    @Query("DELETE FROM detection_events WHERE status = 'DISMISSED' AND detected_at < :before")
    suspend fun purgeDismissedBefore(before: Long)
}

@Dao
interface ReminderLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: ReminderLogEntity): Long

    /**
     * この枠の通知をすでに送ったか。
     * 挿入が一意制約で無視された場合（戻り値 -1）と合わせて二重送信を防ぐ。
     */
    @Query(
        """
        SELECT COUNT(*) FROM reminder_log
        WHERE subscription_id = :subscriptionId
          AND offset_hours = :offsetHours
          AND target_date = :targetDate
        """,
    )
    suspend fun countFired(subscriptionId: Long, offsetHours: Int, targetDate: Long): Int

    /** 更新日が変わったら、その日付以外のログは意味を失うので消す。 */
    @Query("DELETE FROM reminder_log WHERE subscription_id = :subscriptionId AND target_date != :keepTargetDate")
    suspend fun purgeStale(subscriptionId: Long, keepTargetDate: Long)

    @Query("SELECT * FROM reminder_log WHERE subscription_id = :subscriptionId ORDER BY fired_at DESC")
    suspend fun historyOf(subscriptionId: Long): List<ReminderLogEntity>
}

@Dao
interface ServiceCatalogDao {

    @Query("SELECT * FROM service_catalog")
    suspend fun all(): List<ServiceCatalogEntity>

    @Query("SELECT * FROM service_catalog WHERE service_id = :id")
    suspend fun findById(id: String): ServiceCatalogEntity?

    @Query(
        """
        SELECT * FROM service_catalog
        WHERE name_en LIKE '%' || :query || '%'
           OR name_ja LIKE '%' || :query || '%'
           OR name_zh LIKE '%' || :query || '%'
           OR keywords LIKE '%' || :query || '%'
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 10): List<ServiceCatalogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ServiceCatalogEntity>)

    @Query("DELETE FROM service_catalog")
    suspend fun deleteAll()

    /**
     * カタログを丸ごと置き換える。**必ずトランザクション内で行う。**
     *
     * 削除と挿入が別トランザクションだと、途中で失敗したときに
     * カタログが空のまま残る。全ユーザーの解約 URL が消えるのが最悪のケース。
     */
    @Transaction
    suspend fun replaceAll(entries: List<ServiceCatalogEntity>) {
        deleteAll()
        insertAll(entries)
    }
}
