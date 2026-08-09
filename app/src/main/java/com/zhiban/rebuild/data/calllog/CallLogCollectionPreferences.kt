package com.zhiban.rebuild.data.calllog

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.callLogCollectionDataStore by preferencesDataStore("call_log_collection")

@Singleton
class CallLogCollectionPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val enabledKey = booleanPreferencesKey("enabled")
    private val hangupNoteEnabledKey = booleanPreferencesKey("hangup_note_enabled")
    private val cursorKey = longPreferencesKey("last_modified_cursor")

    val enabled: Flow<Boolean> = context.callLogCollectionDataStore.data.map { it[enabledKey] ?: false }
    val hangupNoteEnabled: Flow<Boolean> = context.callLogCollectionDataStore.data.map {
        it[hangupNoteEnabledKey]
            ?: false
    }

    suspend fun isEnabled(): Boolean = enabled.first()
    suspend fun isHangupNoteEnabled(): Boolean = hangupNoteEnabled.first()
    suspend fun cursor(): Long = context.callLogCollectionDataStore.data.first()[cursorKey] ?: 0L

    suspend fun setEnabled(value: Boolean) {
        context.callLogCollectionDataStore.edit { it[enabledKey] = value }
    }

    suspend fun setHangupNoteEnabled(value: Boolean) {
        context.callLogCollectionDataStore.edit { it[hangupNoteEnabledKey] = value }
    }

    suspend fun advanceCursor(value: Long) {
        context.callLogCollectionDataStore.edit { values ->
            values[cursorKey] = maxOf(values[cursorKey] ?: 0L, value)
        }
    }
}
