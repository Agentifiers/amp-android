package com.agentifiers.amp.store

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The outbox and drafts hold user work, so every schema version gets a real migration, never a destructive fallback. */
internal val MIGRATIONS: Array<Migration> = arrayOf(
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN reason TEXT")
        }
    },
)
