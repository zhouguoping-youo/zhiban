package com.zhiban.rebuild.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResponseFormattingTest {
    @Test fun `markdown-like response becomes stable semantic blocks`() {
        val blocks = AgentResponseParser.parse(
            """# 结论

这是 **重点** 和 `代码`。
- 第一项
2. 第二项
> 注意隐私
```kotlin
val answer = 42
```""",
        )
        assertTrue(blocks[0] is AgentResponseBlock.Heading)
        assertTrue(blocks[1] is AgentResponseBlock.Paragraph)
        assertEquals(2, blocks.filterIsInstance<AgentResponseBlock.ListItem>().size)
        assertEquals("val answer = 42", blocks.filterIsInstance<AgentResponseBlock.Code>().single().content)
    }

    @Test fun `unfinished streaming code fence remains visible`() {
        val code = AgentResponseParser.parse("```json\n{\"ok\": true}").single() as AgentResponseBlock.Code
        assertEquals("json", code.language)
        assertEquals("{\"ok\": true}", code.content)
    }

    @Test fun `pipe table becomes a structured table block`() {
        val blocks = AgentResponseParser.parse(
            """先看结论。

| 联系人 | 公司 |
| --- | --- |
| 黄勇 | 平凯星辰 |
| 丁波 | 安徽九翰 |""",
        )
        val table = blocks.filterIsInstance<AgentResponseBlock.Table>().single()
        assertEquals(listOf("联系人", "公司"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("黄勇", "平凯星辰"), table.rows[0])
        assertTrue(blocks.first() is AgentResponseBlock.Paragraph)
    }

    @Test fun `pipe lines without a separator row stay plain prose`() {
        val blocks = AgentResponseParser.parse("配置形如 | key | value | 的写法")
        assertTrue(blocks.filterIsInstance<AgentResponseBlock.Table>().isEmpty())
        assertTrue(blocks.single() is AgentResponseBlock.Paragraph)
    }

    @Test fun `inline model label only exposes user facing response level`() {
        assertEquals("标准", compactInlineModelLabel("step-3.5-flash 智能/标准"))
        assertEquals("深入", compactInlineModelLabel("step-3.5-flash 智能/深入"))
        assertEquals("快速", compactInlineModelLabel("step-3.5-flash 智能/快速"))
    }
}
