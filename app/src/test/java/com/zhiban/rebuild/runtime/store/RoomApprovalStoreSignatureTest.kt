package com.zhiban.rebuild.runtime.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomApprovalStoreSignatureTest {
    @Test
    fun constructorKeepsDependenciesBoundedAndUsesARequestObjectForCompletion() {
        val constructor = RoomApprovalStore::class.java.declaredConstructors.single { !it.isSynthetic }

        assertTrue(constructor.parameterCount <= 8)
        assertEquals("kotlin.jvm.functions.Function2", constructor.parameterTypes.last().name)
    }
}
