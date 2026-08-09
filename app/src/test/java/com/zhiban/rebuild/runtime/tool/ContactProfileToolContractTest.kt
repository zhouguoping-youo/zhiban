package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.runtime.governance.contactProfileFieldsDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactProfileToolContractTest {
    @Test
    fun `profile enrichment tool is registered and confirmation gated`() {
        val spec = RuntimeToolCatalog.production().requireRegistered("contact.profile.proposeUpdate")

        assertEquals(RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED, spec.risk)
        assertTrue(spec.providerDefinitionJson.contains("只补充空缺字段"))
        assertTrue(spec.providerDefinitionJson.contains("evidenceSummary"))
    }

    @Test
    fun `undo guard digest changes when an enriched field changes`() {
        val enriched = contact(company = "平凯星辰(北京)科技有限公司", title = "技术工程师")
        val fields = listOf("company", "title")

        assertNotEquals(
            contactProfileFieldsDigest(enriched, fields),
            contactProfileFieldsDigest(enriched.copy(title = "售前"), fields),
        )
        assertEquals(
            contactProfileFieldsDigest(enriched, fields),
            contactProfileFieldsDigest(enriched.copy(phone = "13800000000"), fields),
        )
    }

    private fun contact(company: String?, title: String?) = ContactEntity(
        contactId = "contact-1",
        displayName = "李应啸",
        normalizedName = "李应啸",
        phone = null,
        email = null,
        wechatId = null,
        company = company,
        title = title,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "TEST",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
