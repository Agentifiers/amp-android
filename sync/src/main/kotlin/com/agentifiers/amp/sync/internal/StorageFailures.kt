package com.agentifiers.amp.sync.internal

import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

private const val STORE_TAG = "AmpStore"

/** The engine scopes' handler: a SQLite failure is logged, anything else still reaches the thread's uncaught handler. */
internal val StorageFailureHandler = CoroutineExceptionHandler { _, failure ->
    if (failure is SQLiteException) {
        Log.e(STORE_TAG, "storage failed in an engine coroutine", failure)
    } else {
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler?.uncaughtException(thread, failure)
    }
}

/** [read], or null when SQLite fails it, so an unreadable cache reads as an empty one. */
internal suspend fun <T : Any> readOrAbsent(what: String, read: suspend () -> T?): T? = try {
    read()
} catch (failure: SQLiteException) {
    Log.w(STORE_TAG, "$what is unreadable; reading it as absent", failure)
    null
}
