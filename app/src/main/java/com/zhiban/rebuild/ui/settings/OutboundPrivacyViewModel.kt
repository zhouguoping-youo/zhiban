package com.zhiban.rebuild.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.runtime.governance.AppPrivateOutboundAuditStore
import com.zhiban.rebuild.runtime.governance.OutboundDataPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OutboundPrivacyState(
    val allowRedactedAutomaticPersonalContext: Boolean = true,
    val allowCloudSpeech: Boolean = true,
    val allowCloudLlm: Boolean = true,
    val allowRemoteMcp: Boolean = false,
    val allowRemoteEmbedding: Boolean = false,
    val auditCount: Int = 0,
    val blockedCount: Int = 0,
    val monthlyRedactedCount: Int = 0,
    val monthlyOmittedCount: Int = 0,
)

@HiltViewModel
class OutboundPrivacyViewModel @Inject constructor(private val preferences: OutboundDataPreferences, private val auditStore: AppPrivateOutboundAuditStore) :
    ViewModel() {
    private val _state = MutableStateFlow(
        OutboundPrivacyState(
            allowRedactedAutomaticPersonalContext =
                preferences.snapshot().allowRedactedAutomaticPersonalContext,
            allowCloudSpeech = preferences.snapshot().allowCloudSpeech,
            allowCloudLlm = preferences.snapshot().allowCloudLlm,
            allowRemoteMcp = preferences.snapshot().allowRemoteMcp,
            allowRemoteEmbedding = preferences.snapshot().allowRemoteEmbedding,
        ),
    )
    val state = _state.asStateFlow()

    fun setAllowCloudLlm(enabled: Boolean) {
        preferences.setAllowCloudLlm(enabled)
        _state.update { it.copy(allowCloudLlm = enabled) }
    }

    init {
        viewModelScope.launch {
            auditStore.records.combine(auditStore.monthlyProtectionCounts) { records, monthly -> records to monthly }
                .collect { (records, monthly) ->
                    _state.update {
                        it.copy(
                            auditCount = records.size,
                            blockedCount = records.count { record -> record.outcome.startsWith("BLOCKED_") },
                            monthlyRedactedCount = monthly.redacted,
                            monthlyOmittedCount = monthly.omitted,
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
