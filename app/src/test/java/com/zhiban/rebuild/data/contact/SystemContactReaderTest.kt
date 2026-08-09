package com.zhiban.rebuild.data.contact

import android.provider.ContactsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemContactReaderTest {
    @Test
    fun parsesFullAndYearlessBirthdays() {
        assertEquals(SystemContactBirthday(1990, 3, 8), parseSystemContactBirthday("1990-03-08"))
        assertEquals(SystemContactBirthday(null, 3, 8), parseSystemContactBirthday("--03-08"))
        assertEquals(SystemContactBirthday(null, 3, 8), parseSystemContactBirthday("03-08"))
    }

    @Test
    fun rejectsInvalidBirthday() {
        assertNull(parseSystemContactBirthday("--13-08"))
        assertNull(parseSystemContactBirthday("not-a-date"))
    }

    @Test
    fun normalizesChineseMobileCountryCodeToDomesticCanonicalValue() {
        assertEquals("13800138000", normalizeContactPhone("+86 138-0013-8000"))
        assertEquals("13800138000", normalizeContactPhone("13800138000"))
        assertEquals("+85221234567", normalizeContactPhone("+852 2123 4567"))
    }

    @Test
    fun mapsSystemImProtocolWithoutTreatingEveryAccountAsWechat() {
        assertEquals("QQ", systemImPlatform(ContactsContract.CommonDataKinds.Im.PROTOCOL_QQ, null))
        assertEquals("WECHAT", systemImPlatform(ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, "微信"))
        assertEquals("WE_COM", systemImPlatform(ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, "企业微信"))
        assertEquals("FEISHU", systemImPlatform(ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, "Lark"))
        assertNull(systemImPlatform(ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, "unknown"))
    }
}
