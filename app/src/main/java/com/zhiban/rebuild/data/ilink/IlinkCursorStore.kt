package com.zhiban.rebuild.data.ilink

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the opaque `get_updates_buf` cursor so inbound polling resumes where it left off across
 * app restarts (without it, every cold start would reprocess the same messages). The value is
 * treated as an opaque string: never parsed, never modified, echoed back verbatim.
 */
@Singleton
class IlinkCursorStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cursor(): String = prefs.getString(KEY_CURSOR, null).orEmpty()

    fun saveCursor(getUpdatesBuf: String) {
        check(prefs.edit().putString(KEY_CURSOR, getUpdatesBuf).commit()) { "ILINK_CURSOR_SAVE_FAILED" }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "ILINK_CURSOR_SAVE_FAILED" }
    }

    private companion object {
        const val PREFS = "ilink_bot_updates"
        const val KEY_CURSOR = "get_updates_buf"
    }
}
