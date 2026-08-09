package com.zhiban.rebuild.data.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scenario tests for the contact-merge path that the conversational review dialog drives
 * (agent creates a name-only stub from chat, user merges it into the real imported contact).
 *
 * These pin two defects in [ContactAgentDataRepository.confirmContactMerge] +
 * single-hop `resolveCanonicalContactId`:
 *  1. A canonical that already has sources can itself become a source (asymmetric guard),
 *     producing a broken chain A→B→C where A resolves to the now-hidden B instead of C.
 *  2. Re-merging an already-merged source silently REPLACEs its link (A B→C) with no error.
 */
@RunWith(AndroidJUnit4::class)
class ContactMergeChainTest {
    private lateinit var db: AgentDatabase
    private lateinit var repository: ContactAgentDataRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        repository = ContactAgentDataRepository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun insert(id: String, name: String) = db.contactDao().insert(
        ContactEntity(id, name, name, null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 2),
    )

    @Test fun cannotMergeAContactThatAlreadyHasSourcesAsSource() = runBlocking {
        // A→B already merged (B is canonical). B must NOT then be merged away as a source into C,
        // because that strands A pointing at the hidden B (single-hop resolveCanonicalContactId
        // would return B, not C). The merge must be rejected.
        insert("a", "张老师")
        insert("b", "张总")
        insert("c", "张董")
        repository.confirmContactMerge(canonicalContactId = "b", sourceContactId = "a", reason = "同一人", nowEpochMs = 10)

        expectMergeRejected {
            repository.confirmContactMerge(canonicalContactId = "c", sourceContactId = "b", reason = "又同一人", nowEpochMs = 11)
        }
    }

    @Test fun chainedMergeResolvesSourceToUltimateCanonical() = runBlocking {
        // Even if a chain A→B→C somehow exists (legacy data), resolving A must follow the chain
        // to the ultimate canonical C, not stop at the hidden intermediate B.
        insert("a", "张老师")
        insert("b", "张总")
        insert("c", "张董")
        val identities = db.contactIdentityDao()
        identities.upsertMergeLink(com.zhiban.rebuild.data.contact.ContactMergeLinkEntity("a", "b", "r1", true, 10, null))
        identities.upsertMergeLink(com.zhiban.rebuild.data.contact.ContactMergeLinkEntity("b", "c", "r2", true, 11, null))

        assertEquals("c", db.relationshipEdgeDao().resolveCanonicalContactId("a"))
    }

    @Test fun remergingAnAlreadyMergedSourceIsRejectedNotSilentlyRepointed() = runBlocking {
        // A is already merged into B. Merging A again into C must fail loudly rather than
        // REPLACE-overwrite the link (which silently moves A from B to C with no audit).
        insert("a", "张老师")
        insert("b", "张总")
        insert("c", "张董")
        repository.confirmContactMerge(canonicalContactId = "b", sourceContactId = "a", reason = "同一人", nowEpochMs = 10)

        expectMergeRejected {
            repository.confirmContactMerge(canonicalContactId = "c", sourceContactId = "a", reason = "换主", nowEpochMs = 11)
        }
        // The original link must be intact.
        assertEquals("b", db.contactIdentityDao().activeMergeLink("a")?.canonicalContactId)
    }

    @Test fun undoConfirmedMergeRestoresSourceVisibilityAndClearsLink() = runBlocking {
        // The conversational review dialog's undo must restore the merged-away source: after undo,
        // the source resolves to itself again and the active link is gone.
        insert("a", "张老师")
        insert("b", "张总")
        repository.confirmContactMerge(canonicalContactId = "b", sourceContactId = "a", reason = "同一人", nowEpochMs = 10)
        assertEquals("b", db.relationshipEdgeDao().resolveCanonicalContactId("a"))

        assertTrue(repository.undoContactMerge("a", nowEpochMs = 11))

        assertEquals("a", db.relationshipEdgeDao().resolveCanonicalContactId("a"))
        assertNull(db.contactIdentityDao().activeMergeLink("a"))
    }

    @Test fun undoConfirmedMergeOnNonMergedContactReturnsFalse() = runBlocking {
        // Undo with nothing to undo must report failure (0 rows), not claim success.
        insert("a", "张老师")
        assertFalse(repository.undoContactMerge("a", nowEpochMs = 11))
    }

    private suspend fun expectMergeRejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
            return
        } catch (other: Throwable) {
            android.util.Log.e("MergeProbe", "unexpected throwable: ${other.javaClass.name}: ${other.message}")
            fail("expected IllegalArgumentException but got ${other.javaClass.name}: ${other.message}")
        }
        fail("expected IllegalArgumentException rejecting the merge, but it succeeded")
    }
}
