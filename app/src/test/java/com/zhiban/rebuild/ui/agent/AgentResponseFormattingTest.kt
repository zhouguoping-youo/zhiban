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

    @Test fun `inline model label only exposes user facing response level`() {
        assertEquals("标准", compactInlineModelLabel("step-3.5-flash 智能/标准"))
        assertEquals("深入", compactInlineModelLabel("step-3.5-flash 智能/深入"))
        assertEquals("快速", compactInlineModelLabel("step-3.5-flash 智能/快速"))
    }
}
