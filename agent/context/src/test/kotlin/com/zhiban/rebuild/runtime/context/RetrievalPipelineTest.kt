package com.zhiban.rebuild.runtime.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalPipelineTest {
    private fun item(id: String, summary: String = id) = RetrievalCandidate(id, "test", id, summary)

    @Test fun rrfRewardsCandidatesSupportedByMultiplePaths() {
        val merged = reciprocalRankFusion(
            listOf(
                RetrievalPath.STRUCTURED to listOf(item("contact-1"), item("contact-2")),
                RetrievalPath.FTS to listOf(item("contact-2", "richer summary"), item("contact-3")),
            ),
        )
        assertEquals("contact-2", merged.first().candidate.id)
        assertEquals(setOf(RetrievalPath.STRUCTURED, RetrievalPath.FTS), merged.first().contributingPaths)
        assertEquals("richer summary", merged.first().candidate.summary)
    }

    @Test fun rrfDeduplicatesWithinAPathAndUsesStableTieBreak() {
        val merged = reciprocalRankFusion(
            listOf(
                RetrievalPath.FTS to listOf(item("b"), item("b"), item("a")),
            ),
        )
        assertEquals(listOf("b", "a"), merged.map { it.candidate.id })
        assertTrue(merged.first().score > merged.last().score)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rrfRejectsUnboundedLimit() {
        reciprocalRankFusion(emptyList(), limit = 101)
    }

    @Test fun degradationReasonsMergeWithoutDuplicates() {
        val result = ContextRetrievalResult(emptyList(), 0, listOf("memory_fts:failure"), 0)
            .withDegradations(listOf("memory_approved:failure", "memory_fts:failure"))

        assertEquals(listOf("memory_fts:failure", "memory_approved:failure"), result.degradationPath)
    }
}
