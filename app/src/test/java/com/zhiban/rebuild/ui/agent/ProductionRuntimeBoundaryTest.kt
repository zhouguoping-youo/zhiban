package com.zhiban.rebuild.ui.agent

import com.zhiban.rebuild.BuildConfig
import com.zhiban.rebuild.di.AgentDataModule
import com.zhiban.rebuild.runtime.spi.RuntimeV2FeatureFlag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionRuntimeBoundaryTest {
    @Test
    fun `production conversation view model has no legacy runtime or routing flag dependency`() {
        val parameterTypes = AgentConversationViewModel::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asIterable() }

        assertFalse(parameterTypes.any { it.name == LEGACY_RUNTIME_CLASS })
        assertFalse(parameterTypes.contains(RuntimeV2FeatureFlag::class.java))
        assertFalse(AgentConversationViewModel::class.java.declaredMethods.any { it.name == "selectMode" })
    }

    @Test
    fun `this build routes the real app through runtime v2`() {
        assertTrue(BuildConfig.RUNTIME_V2_ENABLED)
    }

    @Test
    fun `production hilt module does not provide legacy runtime`() {
        val legacyProviders = AgentDataModule::class.java.declaredMethods.filter {
            it.returnType.name ==
                LEGACY_RUNTIME_CLASS
        }
        assertTrue(legacyProviders.isEmpty())
    }

    private companion object {
        const val LEGACY_RUNTIME_CLASS = "com.zhiban.rebuild.agent.AgentRuntime"
    }
}
