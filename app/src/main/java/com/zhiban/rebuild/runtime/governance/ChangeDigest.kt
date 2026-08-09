package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.tool.sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val changeDigestJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

internal fun canonicalChangeDigest(value: FactEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmLeadEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmActivityEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmNextActionEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmOpportunityEntity): String = sha256(changeDigestJson.encodeToString(value))

/**
 * Rows written before the canonical JSON migration used data-class `toString()`.
 * Keep that digest readable so the migration never takes away a user's existing undo.
 */
internal fun changeDigestMatches(stored: String?, canonical: String, legacyValue: Any): Boolean =
    stored == canonical || stored == sha256(legacyValue.toString())
