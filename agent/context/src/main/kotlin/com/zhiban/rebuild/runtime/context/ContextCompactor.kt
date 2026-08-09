package com.zhiban.rebuild.runtime.context

data class CompactionLineage(
    val sourceBlockIds: List<String>,
    val sourceSequenceRange: LongRange,
    val sourceProducerVersions: Set<String>,
    val summaryProducerVersion: String,
    val summarySchemaVersion: Int,
    val canonicalHash: String,
)

data class CompactionResult(val summary: ContextBlock, val archivedOriginals: List<ContextBlock>, val lineage: CompactionLineage)

class ContextCompactor {
    fun compact(
        originals: List<ContextBlock>,
        summaryId: String,
        summaryText: String,
        summaryTokens: Int,
        producerVersion: String,
        summarySchemaVersion: Int = 1,
    ): CompactionResult {
        require(originals.isNotEmpty())
        require(summarySchemaVersion > 0)
        require(originals.zipWithNext().all { (a, b) -> a.provenance.sourceSequence <= b.provenance.sourceSequence })
        val first = originals.first().provenance.sourceSequence
        val last = originals.last().provenance.sourceSequence
        val canonicalSources = originals.joinToString("") { block ->
            val p = block.provenance
            listOf(
                block.id, block.content, block.layer.name, block.trust.name, block.sensitivity.name,
                block.kind.name, block.atomicGroupId.orEmpty(), block.tokenCost.toString(), p.sourceType,
                p.sourceId, p.digest, p.sourceSequence.toString(), p.producerVersion, p.schemaVersion.toString(),
            )
                .joinToString("") { value -> "${value.toByteArray().size}:$value" }
        }
        val canonicalSummary = listOf(
            summaryId,
            summaryText,
            summaryTokens.toString(),
            producerVersion,
            summarySchemaVersion.toString(),
        )
            .joinToString("") { value -> "${value.toByteArray().size}:$value" }
        val canonical = canonicalSources + canonicalSummary
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        val lineage =
            CompactionLineage(
                originals.map {
                    it.id
                },
                first..last,
                originals.map {
                    it.provenance.producerVersion
                }.toSet(),
                producerVersion,
                summarySchemaVersion,
                hash,
            )
        val summary = ContextBlock(
            summaryId,
            ContextLayer.CONTEXT,
            summaryText,
            TrustLevel.UNTRUSTED_MEMORY,
            originals.maxOf { it.sensitivity },
            summaryTokens,
            ContextProvenance(
                "compaction",
                summaryId,
                last,
                producerVersion,
                digest = lineage.canonicalHash,
                schemaVersion = summarySchemaVersion,
            ),
            kind = ContextKind.SUMMARY,
        )
        return CompactionResult(summary, originals.toList(), lineage)
    }
}
