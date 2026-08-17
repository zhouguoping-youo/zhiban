package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.data.ilink.network.IlinkBindStatus
import com.zhiban.rebuild.data.ilink.network.IlinkBotTransport
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** UI-facing states of the QR bind flow, collected by the settings screen. */
sealed interface IlinkBindUiState {
    /** Show this QR image so the user can scan it with WeChat. */
    data class ShowQrcode(val qrcodeImgUrl: String?, val attempt: Int) : IlinkBindUiState

    /** Code on screen, waiting for the user to scan it. */
    data object WaitingScan : IlinkBindUiState

    /** Scanned; waiting for the user to confirm on their phone. */
    data object Scanned : IlinkBindUiState

    /** Confirmed and credentials saved; the channel is ready. */
    data class Bound(val binding: IlinkBotBinding) : IlinkBindUiState

    /** Terminal failure carrying a safe reason code (no sensitive detail). */
    data class Failed(val reasonCode: String) : IlinkBindUiState
}

/**
 * Drives the WeChat iLink QR bind handshake: fetch a code, poll scan/confirm state, refresh the
 * code on expiry, and persist credentials once the user confirms on their phone.
 *
 * The flow is cold and cancellable — leaving the settings screen cancels collection, which cancels
 * the in-flight poll cleanly (no `runCatching` swallow of `CancellationException`).
 */
@Singleton
internal class IlinkBotBindingController @Inject constructor(
    private val transport: IlinkBotTransport,
    private val gate: IlinkOutboundGate,
    private val credentialStore: IlinkBotCredentialStore,
) {
    fun bind(): Flow<IlinkBindUiState> = flow {
        try {
            val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
            var confirmed: IlinkBotBinding? = null
            var attempt = 0
            while (confirmed == null && attempt < MAX_QR_REFRESH && System.currentTimeMillis() < deadline) {
                attempt += 1
                confirmed = pollOneQrcode(attempt, deadline) { emit(it) }
            }
            emit(confirmed?.let(IlinkBindUiState::Bound) ?: IlinkBindUiState.Failed("ILINK_BIND_TIMEOUT"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProviderFailure) {
            emit(IlinkBindUiState.Failed(failure.code))
        } catch (_: Exception) {
            emit(IlinkBindUiState.Failed("ILINK_BIND_UNAVAILABLE"))
        }
    }

    /** Fetch one QR code and poll it until confirm/expiry. Returns the binding on confirm, else null. */
    private suspend fun pollOneQrcode(attempt: Int, deadline: Long, emit: suspend (IlinkBindUiState) -> Unit): IlinkBotBinding? {
        gate.requireBindAllowed("ilink-bind-qrcode-${System.currentTimeMillis()}")
        val qrcode = transport.getBotQrcode()
        emit(IlinkBindUiState.ShowQrcode(qrcode.qrcodeImgUrl, attempt))
        emit(IlinkBindUiState.WaitingScan)
        var scanned = false
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            when (val status = pollStatus(qrcode.qrcode)) {
                is IlinkBindStatus.Confirmed -> {
                    credentialStore.saveBinding(status.session, System.currentTimeMillis())
                    return credentialStore.bindingInfo()
                }

                is IlinkBindStatus.Expired -> return null

                is IlinkBindStatus.Scanned -> if (!scanned) {
                    scanned = true
                    emit(IlinkBindUiState.Scanned)
                }

                is IlinkBindStatus.Waiting -> Unit
            }
        }
        return null
    }

    private suspend fun pollStatus(qrcode: String): IlinkBindStatus {
        gate.requireBindAllowed("ilink-bind-status-${System.currentTimeMillis()}")
        return transport.getQrcodeStatus(qrcode)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val TOTAL_TIMEOUT_MS = 180_000L
        const val MAX_QR_REFRESH = 3
    }
}
