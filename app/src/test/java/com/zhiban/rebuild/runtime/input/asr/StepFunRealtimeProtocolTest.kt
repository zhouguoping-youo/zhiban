package com.zhiban.rebuild.runtime.input.asr

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StepFunRealtimeProtocolTest {
    @Test
    fun `session uses server VAD and bidirectional audio`() {
        val root = Json.parseToJsonElement(StepFunRealtimeProtocol.sessionUpdate()) as JsonObject
        val session = root["session"] as JsonObject
        val turnDetection = session["turn_detection"] as JsonObject

        assertEquals("session.update", (root["type"] as JsonPrimitive).content)
        assertEquals("server_vad", (turnDetection["type"] as JsonPrimitive).content)
        assertEquals("pcm16", (session["input_audio_format"] as JsonPrimitive).content)
        assertEquals("pcm16", (session["output_audio_format"] as JsonPrimitive).content)
        assertTrue(session["modalities"].toString().contains("\"audio\""))
    }

    @Test
    fun `barge in emits response cancel`() {
        val event = Json.parseToJsonElement(StepFunRealtimeProtocol.CANCEL_RESPONSE) as JsonObject
        assertEquals("response.cancel", (event["type"] as JsonPrimitive).content)
    }

    @Test
    fun `pcm level keeps silence flat and speech visibly active`() {
        assertEquals(0f, measurePcm16InputLevel(ByteArray(16), 16))

        val speech = ByteArray(16)
        for (index in speech.indices step 2) {
            speech[index] = 0x00
            speech[index + 1] = 0x10
        }
        assertTrue(measurePcm16InputLevel(speech, speech.size) > 0.6f)
    }

    @Test
    fun `weak network uses ping and bounded exponential reconnect`() {
        assertEquals(20_000, realtimeWebSocketClient(OkHttpClient()).pingIntervalMillis)
        assertEquals(RealtimeReconnectPlan(1, 1_000L), nextRealtimeReconnect(0))
        assertEquals(RealtimeReconnectPlan(2, 2_000L), nextRealtimeReconnect(1))
        assertEquals(RealtimeReconnectPlan(3, 4_000L), nextRealtimeReconnect(2))
        assertNull(nextRealtimeReconnect(3))
    }

    @Test
    fun `negative recorder read is treated as terminal microphone failure`() {
        assertTrue(isAudioReadFailure(android.media.AudioRecord.ERROR_INVALID_OPERATION))
        assertTrue(isAudioReadFailure(android.media.AudioRecord.ERROR_BAD_VALUE))
        assertTrue(!isAudioReadFailure(0))
    }

    @Test
    fun `stale realtime callbacks cannot mutate the replacement connection`() {
        assertTrue(isActiveRealtimeConnection(callbackGeneration = 4, currentGeneration = 4, sameSocket = true))
        assertTrue(!isActiveRealtimeConnection(callbackGeneration = 3, currentGeneration = 4, sameSocket = true))
        assertTrue(!isActiveRealtimeConnection(callbackGeneration = 4, currentGeneration = 4, sameSocket = false))
    }

    @Test
    fun `resource release reports a fixed degradation instead of swallowing a runtime failure`() {
        val degradations = mutableListOf<String>()

        val released = releaseRealtimeResource("audio:release", degradations::add) {
            throw IllegalStateException("native release failed")
        }

        assertFalse(released)
        assertEquals(listOf("audio:release"), degradations)
    }

    @Test
    fun `resource release always propagates cancellation`() {
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            releaseRealtimeResource("audio:release", {}) { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }
}
