package com.zhiban.rebuild.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.calllog.CallLogCollectionPreferences
import com.zhiban.rebuild.data.calllog.CallLogSyncCoordinator
import com.zhiban.rebuild.data.calllog.CallLogSyncWorker
import com.zhiban.rebuild.data.calllog.CallStateMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CallCollectionState(
    val enabled: Boolean = false,
    val hangupNoteEnabled: Boolean = false,
    val syncing: Boolean = false,
    val lastResult: String? = null,
)

@HiltViewModel
class CallCollectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: CallLogCollectionPreferences,
    private val coordinator: CallLogSyncCoordinator,
    private val callStateMonitor: CallStateMonitor,
) : ViewModel() {
    private val _state = MutableStateFlow(CallCollectionState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.enabled.collectLatest { enabled -> _state.update { it.copy(enabled = enabled) } }
        }
        viewModelScope.launch {
            preferences.hangupNoteEnabled.collectLatest { enabled ->
                _state.update { it.copy(hangupNoteEnabled = enabled) }
            }
        }
    }

    fun setHangupNoteEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setHangupNoteEnabled(enabled)
            if (enabled) callStateMonitor.start() else callStateMonitor.stop()
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setEnabled(enabled)
            if (enabled) {
                CallLogSyncWorker.schedule(context)
                if (preferences.isHangupNoteEnabled()) callStateMonitor.start()
                syncNow()
            } else {
                CallLogSyncWorker.cancel(context)
                callStateMonitor.stop()
                _state.update { it.copy(lastResult = null) }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            if (_state.value.syncing) return@launch
            _state.update { it.copy(syncing = true) }
            val result = coordinator.syncNow()
            _state.update {
                it.copy(
                    syncing = false,
                    lastResult = result.degradationReason?.let { reason ->
                        when (reason) {
                            "call_log:permission" -> "权限不可用"
                            else -> "暂时无法同步"
                        }
                    } ?: "已同步 ${result.rowsWritten} 条",
                )
            }
        }
    }
}
