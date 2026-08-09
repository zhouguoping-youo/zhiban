package com.zhiban.rebuild.runtime.input

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
internal class AttachmentStagingStartup @Inject constructor(private val stager: AppPrivateAttachmentStager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun start() {
        scope.launch { stager.purgeExpired() }
    }
    internal suspend fun runOnce(): Int = stager.purgeExpired()
}
