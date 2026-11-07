package com.payguard.bd

import android.content.Context
import androidx.room.*

@Entity(tableName = "queued_transactions", indices = [Index(value = ["trxId"], unique = true)])
data class QueuedTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val trxId: String,
    val amount: Double,
    val customerPhone: String,
    val simNumber: String,
    val sourceType: String,
    val senderId: String,
    val rawBody: String,
    val isSynced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface QueuedTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: QueuedTransaction): Long

    @Query("SELECT * FROM queued_transactions WHERE isSynced = 0 ORDER BY createdAt ASC")
    suspend fun getUnsynced(): List<QueuedTransaction>

    @Query("UPDATE queued_transactions SET isSynced = 1 WHERE trxId = :trxId")
    suspend fun markSynced(trxId: String)

    @Query("SELECT COUNT(*) FROM queued_transactions WHERE isSynced = 0")
    suspend fun getPendingCount(): Int
}

@Database(entities = [QueuedTransaction::class], version = 1, exportSchema = false)
abstract class OfflineQueueDatabase : RoomDatabase() {
    abstract fun dao(): QueuedTransactionDao

    companion object {
        @Volatile private var INSTANCE: OfflineQueueDatabase? = null

        fun get(context: Context): OfflineQueueDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    OfflineQueueDatabase::class.java,
                    "payguard_offline_queue.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
