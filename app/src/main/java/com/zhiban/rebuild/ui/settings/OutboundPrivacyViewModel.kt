package com.zhiban.rebuild.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.runtime.governance.AppPrivateOutboundAuditStore
import com.zhiban.rebuild.runtime.governance.OutboundDataPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OutboundPrivacyState(
    val allowRedactedAutomaticPersonalContext: Boolean = true,
    val allowCloudSpeech: Boolean = true,
    val allowRemoteMcp: Boolean = false,
    val allowRemoteEmbedding: Boolean = false,
    val auditCount: Int = 0,
    val blockedCount: Int = 0,
    val redactedCount: Int = 0,
    val omittedCount: Int = 0,
)

@HiltViewModel
class OutboundPrivacyViewModel @Inject constructor(private val preferences: OutboundDataPreferences, private val auditStore: AppPrivateOutboundAuditStore) :
    ViewModel() {
    private val _state = MutableStateFlow(
        OutboundPrivacyState(
            allowRedactedAutomaticPersonalContext =
                preferences.snapshot().allowRedactedAutomaticPersonalContext,
            allowCloudSpeech = preferences.snapshot().allowCloudSpeech,
            allowRemoteMcp = preferences.snapshot().allowRemoteMcp,
            allowRemoteEmbedding = preferences.snapshot().allowRemoteEmbedding,
        ),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            auditStore.records.collect { records ->
                _state.update {
                    it.copy(
                        auditCount = records.size,
                        blockedCount = records.count { record -> record.outcome.startsWith("BLOCKED_") },
                        redactedCount = records.sumOf { record -> record.redactedMessageCount },
                        omittedCount = records.sumOf { record -> record.omittedMessageCount },
                    )
                }
            }
        }
    }

    fun setAllowRedactedAutomaticPersonalContext(enabled: Boolean) {
        preferences.setAllowRedactedAutomaticPersonalContext(enabled)
        _state.update { it.copy(allowRedactedAutomaticPersonalContext = enabled) }
    }

    fun setAllowCloudSpeech(enabled: Boolean) {
        preferences.setAllowCloudSpeech(enabled)
        _state.update { it.copy(allowCloudSpeech = enabled) }
    }

    fun setAllowRemoteMcp(enabled: Boolean) {
        preferences.setAllowRemoteMcp(enabled)
        _state.update { it.copy(allowRemoteMcp = enabled) }
    }

    fun setAllowRemoteEmbedding(enabled: Boolean) {
        preferences.setAllowRemoteEmbedding(enabled)
        _state.update { it.copy(allowRemoteEmbedding = enabled) }
    }

    fun clearAudit() {
        viewModelScope.launch { auditStore.clear() }
    }
}
