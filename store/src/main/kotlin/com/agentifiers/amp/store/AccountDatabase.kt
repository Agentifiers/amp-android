package com.agentifiers.amp.store

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.agentifiers.amp.model.AccountId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One account's tables. Cache tables hold raw wire JSON and may be rebuilt from the server; the
 * outbox, drafts and ledgers hold user work, so a schema change to them needs a real migration.
 */
interface AccountDatabase {
    val threadCache: ThreadCache
    val summaries: SummaryCache
    val catalog: CatalogCache
    val changes: ChangesCache
    val outbox: Outbox
    val drafts: Drafts
    val commentDrafts: CommentDrafts
    val watchList: WatchList
    val snoozes: SnoozeLedger
    val colors: ColorLedger
    val pushSubscriptions: PushSubscriptions
    val ledger: NotificationLedger
}

/** One Room instance per account file, shared by :sync and :notify so both see the same invalidations. */
interface AccountDatabases {
    /** Opens `amp-<accountId>.db` on first use and returns the same instance after that. */
    fun forAccount(accountId: AccountId): AccountDatabase

    /** Closes the account's database if open and deletes its file. Deleting an absent account is a no-op. */
    suspend fun delete(accountId: AccountId)
}

private val SAFE_FILE_ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

/** The account id becomes a file name, so it must not name a path. */
internal fun databaseFileName(accountId: AccountId): String {
    require(SAFE_FILE_ID.matches(accountId.raw)) { "account id is not a safe file name: ${accountId.raw}" }
    return "amp-${accountId.raw}.db"
}

@Database(
    entities = [
        SummaryRow::class, MessageRow::class, SequencedRow::class, SnapshotRow::class, CursorRow::class,
        CatalogRow::class, ChangeSnapshotRow::class, BlobRow::class, FileOrderRow::class,
        OutboxRow::class, DraftRow::class, CommentDraftRow::class, WatchRow::class, SnoozeRow::class,
        PushSubRow::class, LedgerRow::class, ColorRow::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class AccountRoomDatabase : RoomDatabase(), AccountDatabase {
    abstract override val threadCache: ThreadCacheDao
    abstract override val summaries: SummaryDao
    abstract override val catalog: CatalogDao
    abstract override val changes: ChangesDao
    abstract override val outbox: OutboxDao
    abstract override val drafts: DraftDao
    abstract override val commentDrafts: CommentDraftDao
    abstract override val watchList: WatchDao
    abstract override val snoozes: SnoozeDao
    abstract override val colors: ColorDao
    abstract override val pushSubscriptions: PushSubscriptionDao
    abstract override val ledger: LedgerDao
}

internal class RoomAccountDatabases(private val context: Context) : AccountDatabases {
    private val open = HashMap<AccountId, AccountRoomDatabase>()

    override fun forAccount(accountId: AccountId): AccountDatabase = synchronized(open) {
        open.getOrPut(accountId) {
            Room.databaseBuilder(context, AccountRoomDatabase::class.java, databaseFileName(accountId))
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }

    override suspend fun delete(accountId: AccountId) {
        val name = databaseFileName(accountId)
        withContext(Dispatchers.IO) {
            synchronized(open) { open.remove(accountId) }?.close()
            context.deleteDatabase(name)
        }
    }
}
