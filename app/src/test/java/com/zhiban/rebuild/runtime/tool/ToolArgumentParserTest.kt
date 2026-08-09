package com.zhiban.rebuild.runtime.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentParserTest {
    @Test
    fun `parses only explicitly allowed keys`() {
        val value = parseToolArgs("""{"query":"张三","limit":5}""", setOf("query", "limit"))

        assertEquals("张三", value.getValue("query").toString().trim('"'))
    }

    @Test
    fun `rejects unknown keys and malformed json with same safe failure`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseToolArgs("""{"query":"张三","overridePolicy":true}""", setOf("query"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseToolArgs("not-json", setOf("query"))
        }
    }

    @Test
    fun `dynamic schema without declared properties accepts object keys`() {
        val value = parseToolArgs("""{"dynamic":"value"}""", null)

        assertEquals("value", value.getValue("dynamic").toString().trim('"'))
    }
}
