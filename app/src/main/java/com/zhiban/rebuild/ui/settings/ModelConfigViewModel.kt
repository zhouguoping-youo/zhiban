package com.zhiban.rebuild.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.runtime.provider.ProviderConfigurationManager
import com.zhiban.rebuild.runtime.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.runtime.provider.TrustedProviderRegistry
import com.zhiban.rebuild.ui.chat.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_MODEL = ProviderConfigurationManager.DEFAULT_MODEL

data class ModelConfigUiState(
    val apiKey: String = "",
    val apiKeyVisible: Boolean = false,
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isApiKeyConfigured: Boolean = false,
    val savedTick: Boolean = false,
    val errorMessage: String? = null,
    val isChecking: Boolean = false,
    val healthMessage: String? = null,
    val providerId: String = ProviderConfigurationManager.DEFAULT_PROVIDER,
    val providerOptions: List<Pair<String, String>> = TrustedProviderRegistry.PRESETS.map {
        it.providerId to
            it.displayName
    },
    val modelOptions: List<String> = TrustedProviderRegistry().preset(
        ProviderConfigurationManager.DEFAULT_PROVIDER,
    ).models,
)

@HiltViewModel
class ModelConfigViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val providerEnvironment: ProviderEnvironmentManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelConfigUiState())
    val uiState: StateFlow<ModelConfigUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val legacyModel = preferencesManager.getModel()
                preferencesManager.consumeLegacyApiKey { legacyBytes ->
                    providerEnvironment.configureStepFun(legacyBytes, legacyModel)
                    val migrated = requireNotNull(providerEnvironment.activeProfile())
                    preferencesManager.saveModel(migrated.modelId)
                }
                val profile = providerEnvironment.activeProfile()
                val providerId = profile?.providerId ?: ProviderConfigurationManager.DEFAULT_PROVIDER
                val preset = TrustedProviderRegistry().preset(providerId)
                val model = profile?.modelId ?: legacyModel.takeIf { it in preset.models } ?: preset.defaultModel
                val prompt = preferencesManager.getSystemPrompt()
                _uiState.update {
                    it.copy(
                        apiKey = "",
                        model = model.ifBlank { DEFAULT_MODEL },
                        providerId = providerId,
                        providerOptions = TrustedProviderRegistry.PRESETS.map { p -> p.providerId to p.displayName },
                        modelOptions = preset.models,
                        systemPrompt = prompt,
                        isApiKeyConfigured = profile != null,
                        isLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "加载配置失败，请稍后重试。")
                }
            }
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update {
            it.copy(apiKey = value, savedTick = false, errorMessage = null)
        }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }
    }

    fun onModelChange(value: String) {
        _uiState.update { it.copy(model = value, savedTick = false) }
    }

    fun onSystemPromptChange(value: String) {
        _uiState.update { it.copy(systemPrompt = value, savedTick = false) }
    }

    fun save() {
        val current = _uiState.value
        if (current.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val requestedModel = current.model.trim().ifBlank { DEFAULT_MODEL }
                val newKey = current.apiKey.trim()
                val profile = if (newKey.isNotBlank()) {
                    val bytes = newKey.toByteArray(Charsets.UTF_8)
                    try {
                        providerEnvironment.configure(current.providerId, bytes, requestedModel)
                        requireNotNull(providerEnvironment.activeProfile())
                    } finally {
                        bytes.fill(0)
                    }
                } else {
                    require(providerEnvironment.activeProfile()?.providerId == current.providerId) {
                        "API_KEY_REQUIRED"
                    }
                    providerEnvironment.selectModel(requestedModel)
                    requireNotNull(providerEnvironment.activeProfile())
                }
                preferencesManager.clearLegacyApiKey()
                preferencesManager.saveNonSecretModelSettings(profile.modelId, current.systemPrompt)
                _uiState.update {
                    it.copy(
                        apiKey = "",
                        model = profile.modelId,
                        isSaving = false,
                        savedTick = true,
                        isApiKeyConfigured = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = providerConfigurationFailureMessage(
                            ProviderEnvironmentManager.safeConfigurationFailureCode(failure),
                        ),
                    )
                }
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            providerEnvironment.clear()
            preferencesManager.clearLegacyApiKey()
            _uiState.update {
                it.copy(apiKey = "", isApiKeyConfigured = false, savedTick = false)
            }
        }
    }

    fun checkConnection() {
        if (_uiState.value.isChecking) return
        _uiState.update { it.copy(isChecking = true, healthMessage = null, errorMessage = null) }
        viewModelScope.launch {
            val health = providerEnvironment.healthCheck(forceRefresh = true)
            _uiState.update {
                it.copy(
                    isChecking = false,
                    healthMessage = if (health.available) "连接正常" else "连接不可用（${health.safeFailureCode ?: "UNKNOWN"}）",
                )
            }
        }
    }
}

internal fun providerConfigurationFailureMessage(code: String): String = when (code) {
    "AUTHENTICATION_FAILED" -> "API Key 无效或已失效，请检查后重试。"
    "INSUFFICIENT_QUOTA" -> "阶跃星辰账户额度不足，请检查服务商账户。"
    "MODEL_NOT_AVAILABLE" -> "当前模型暂不可用，请稍后重试。"
    "NETWORK_OFFLINE" -> "当前设备无法连接网络，请检查网络或 DNS 后重试。"
    "TIMEOUT" -> "连接超时，请切换网络后重试。"
    "TLS_VERIFICATION_FAILED" -> "安全连接验证失败，请检查网络环境或更新知伴。"
    "RATE_LIMITED" -> "请求较多，请稍后重试。"
    else -> "暂时无法连接 AI 服务，请稍后重试。"
}
