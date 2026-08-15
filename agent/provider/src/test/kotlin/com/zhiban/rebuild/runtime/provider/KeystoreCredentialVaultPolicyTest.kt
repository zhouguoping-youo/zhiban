package com.zhiban.rebuild.runtime.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreCredentialVaultPolicyTest {
    @Test fun missingKeystoreEntryRemovesOnlyItsUnrecoverableWrappedCredential() {
        var removals = 0

        val failure = runCatching {
            handleCredentialKeyReadFailure(CredentialKeyNotFoundException()) { removals++ }
        }.exceptionOrNull()

        assertEquals(1, removals)
        assertEquals("CREDENTIAL_NOT_FOUND", failure?.message)
    }

    @Test fun transientKeystoreFailurePreservesWrappedCredentialForRetry() {
        var removals = 0
        val transient = IllegalStateException("keystore service temporarily unavailable")

        val failure = runCatching {
            handleCredentialKeyReadFailure(transient) { removals++ }
        }.exceptionOrNull()

        assertEquals(0, removals)
        assertEquals("CREDENTIAL_TEMPORARILY_UNAVAILABLE", failure?.message)
        assertTrue(failure?.cause === transient)
    }
}
