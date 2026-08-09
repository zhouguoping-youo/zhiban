package com.zhiban.rebuild.runtime.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCredentialVaultTest {
    @Test fun canaryIsCiphertextBoundToRefAndVersionAndCanBeDeleted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreCredentialVault(context)
        val ref = "test.credential.canary"
        val secret = "sk-CANARY-DO-NOT-LEAK".toByteArray()
        runCatching { vault.delete(ref, 7) }
        vault.provision(ref, 7, secret)
        val recovered = vault.withCredential(ref, 7) { it.copyOf() }
        assertEquals("sk-CANARY-DO-NOT-LEAK", recovered.decodeToString())
        val stored = context.getSharedPreferences(
            "runtime_provider_credentials",
            Context.MODE_PRIVATE,
        ).all.values.joinToString()
        assertFalse(stored.contains("CANARY"))
        assertFalse(stored.contains("sk-"))
        assertTrue(runCatching { vault.withCredential(ref, 8) { Unit } }.isFailure)
        vault.delete(ref, 7)
        assertTrue(runCatching { vault.withCredential(ref, 7) { Unit } }.isFailure)
        recovered.fill(0)
        secret.fill(0)
        Unit
    }
}
