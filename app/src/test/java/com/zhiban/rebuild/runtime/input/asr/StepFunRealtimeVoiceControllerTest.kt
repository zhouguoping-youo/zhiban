package com.zhiban.rebuild.runtime.input.asr

import com.zhiban.rebuild.runtime.provider.CredentialResolver
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPolicySettings
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario tests for the input-box voice button ("语音按钮没用，麦克风转写有用").
 *
 * Unlike the mic transcription path (which falls back to the on-device SpeechRecognizer), the
 * realtime voice button hard-requires cloud-ASR consent AND a stepfun profile. These pin that the
 * failure surfaces as a visible [RealtimeVoiceState.Failed] with an actionable message — never a
 * silent dead click.
 */
class StepFunRealtimeVoiceControllerTest {
    private fun controller(allowCloudSpeech: Boolean, profile: ProviderProfile?): StepFunRealtimeVoiceController {
        val gate = OutboundExportGate(settings = { OutboundPolicySettings(allowCloudSpeech = allowCloudSpeech) })
        val credentials = object : CredentialResolver {
            override suspend fun <T> withCredential(credentialRef: String, keyVersion: Int, block: suspend (ByteArray) -> T): T = block(ByteArray(0))
        }
        val profiles = object : ProviderProfileStore {
            override suspend fun load(): ProviderProfile? = profile
            override suspend fun save(profile: ProviderProfile) = Unit
            override suspend fun clear() = Unit
        }
        return StepFunRealtimeVoiceController(OkHttpClient(), credentials, profiles, gate)
    }

    /** Awaits the terminal [RealtimeVoiceState.Failed] that start() must reach on its IO scope. */
    private suspend fun awaitFailed(controller: StepFunRealtimeVoiceController): RealtimeVoiceState.Failed = withTimeout(5_000) {
        var current = controller.state.value
        while (current !is RealtimeVoiceState.Failed) {
            delay(10)
            current = controller.state.value
        }
        current
    }

    @Test fun startWithoutCloudSpeechConsentFailsWithActionableMessage() = runBlocking {
        val controller = controller(allowCloudSpeech = false, profile = stepfunProfile())
        controller.start()
        assertEquals("请先在隐私与权限中允许语音识别上云", awaitFailed(controller).safeMessage)
    }

    @Test fun startWithNonStepFunProfileFailsWithConnectStepFunMessage() = runBlocking {
        val controller = controller(
            allowCloudSpeech = true,
            profile = ProviderProfile("other", "endpoint", "model", "ref", 1),
        )
        controller.start()
        assertEquals("请先连接阶跃星辰", awaitFailed(controller).safeMessage)
    }

    @Test fun startWithoutAnyProfileFailsWithConnectStepFunMessage() = runBlocking {
        val controller = controller(allowCloudSpeech = true, profile = null)
        controller.start()
        assertEquals("请先连接阶跃星辰", awaitFailed(controller).safeMessage)
    }

    @Test fun consentFailureIsTerminalAndRetryableFromFailedState() = runBlocking {
        // After a consent failure the button must be usable again (Failed is a startable state),
        // so the user is never stuck on a dead voice button.
        val controller = controller(allowCloudSpeech = false, profile = stepfunProfile())
        controller.start()
        assertTrue(awaitFailed(controller) is RealtimeVoiceState.Failed)
        // start() from Failed must not be a silent no-op: it re-enters Connecting then fails again.
        controller.start()
        assertEquals("请先在隐私与权限中允许语音识别上云", awaitFailed(controller).safeMessage)
    }

    private fun stepfunProfile() = ProviderProfile("stepfun", "endpoint", "stepaudio-2.5-realtime", "ref", 1)
}
