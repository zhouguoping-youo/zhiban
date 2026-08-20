package com.zhiban.rebuild.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * androidTest 共享测试替身（androidTest 无 mockk，仅在 testImplementation）。
 *
 * - [FakeProviderAdapter] / [FakeProviderProfileStore]：Provider 面替身，不真连任何模型。
 * - [FakeOutreachGenerator]：继承覆写 generateDraft 返回固定文案，不真正调 LLM。
 * - [buildCompletionRepository]：组装 ContactCompletionRepository（handoff 结果可注入；
 *   隔离 prefs 名，不读不写真机 agent_controls）。
 *
 * 供 AgentSuggestionRepositoryTest / MessageContactCompletionCoordinatorTest 等共用，
 * 避免每个测试文件复制一份 private fake。
 */
internal class FakeProviderAdapter : ProviderAdapter {
    override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = CapabilitySnapshot(
        profileDigest = "test",
        modalities = setOf("text"),
        features = setOf("chat"),
        maxContextTokens = 8192,
        maxOutputTokens = 1024,
        observedAtEpochMs = 0L,
        expiresAtEpochMs = Long.MAX_VALUE,
    )

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flowOf(ModelEvent.Final("stop"))

    override fun cancel(requestId: String): Boolean = true
}

internal class FakeProviderProfileStore : ProviderProfileStore {
    private var profile: ProviderProfile? = null

    override suspend fun load(): ProviderProfile? = profile

    override suspend fun save(profile: ProviderProfile) {
        this.profile = profile
    }

    override suspend fun clear() {
        profile = null
    }
}

/** 不真正调 LLM 的起草器：继承覆写 generateDraft 返回固定文案。 */
internal class FakeOutreachGenerator : ContactCompletionOutreachGenerator(FakeProviderAdapter(), FakeProviderProfileStore()) {
    override suspend fun generateDraft(contactName: String, fields: List<ContactProfileField>, businessContext: String?, requestKey: String): String? =
        "您好，我是周国平本人的知伴AI助手，发现您的资料还不全，方便补充手机号吗？"
}

/**
 * 组装补全仓库：handoff 结果可注入（true=微信预填拉起成功，false=微信不可达）。
 * prefs 用隔离名（agent_controls_test_*），测试互不干扰也不碰真机配置。
 */
internal fun buildCompletionRepository(database: AgentDatabase, handoffSucceeds: Boolean = true): ContactCompletionRepository {
    val controls = AgentControlStore(
        ApplicationProvider.getApplicationContext(),
        "agent_controls_test_${System.currentTimeMillis()}",
    )
    return ContactCompletionRepository(
        database,
        CompletionHandoff { _, _, _ -> handoffSucceeds },
        FakeOutreachGenerator(),
        controls,
    )
}
