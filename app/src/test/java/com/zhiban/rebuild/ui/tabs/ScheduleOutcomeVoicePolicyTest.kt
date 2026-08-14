package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleOutcomeVoicePolicyTest {
    @Test
    fun availableStepFunAsrDoesNotProduceFallbackMessage() {
        assertNull(scheduleOutcomeVoiceUnavailableMessage(CloudAsrAvailability.AVAILABLE))
    }

    @Test
    fun missingProviderExplainsHowToRecoverWithoutGoogleFallback() {
        assertEquals(
            "请先在“我的”中连接模型服务",
            scheduleOutcomeVoiceUnavailableMessage(CloudAsrAvailability.PROVIDER_NOT_CONFIGURED),
        )
    }

    @Test
    fun networkFailureKeepsVoiceResultRetryable() {
        assertEquals(
            "网络不稳定，请重试或键盘输入",
            scheduleOutcomeAsrFailureMessage("ASR_NETWORK_FAILURE"),
        )
    }
}
