package com.zhiban.rebuild.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.components.ZhiBanTextActionButton
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

internal sealed interface AgentResponseBlock {
    data class Heading(val level: Int, val text: String) : AgentResponseBlock
    data class Paragraph(val text: String) : AgentResponseBlock
    data class ListItem(val marker: String, val text: String) : AgentResponseBlock
    data class Quote(val text: String) : AgentResponseBlock
    data class Code(val language: String?, val content: String) : AgentResponseBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : AgentResponseBlock
}

internal object AgentResponseParser {
    fun parse(source: String): List<AgentResponseBlock> {
        val blocks = mutableListOf<AgentResponseBlock>()
        val paragraph = mutableListOf<String>()
        var codeLanguage: String? = null
        val code = mutableListOf<String>()
        val tableLines = mutableListOf<String>()
        fun flushParagraph() {
            paragraph.joinToString(" ").trim().takeIf(String::isNotEmpty)?.let {
                blocks +=
                    AgentResponseBlock.Paragraph(it)
            }
            paragraph.clear()
        }
        fun flushTable() {
            if (tableLines.isEmpty()) return
            if (tableLines.size >= 2 && TABLE_SEPARATOR.matches(tableLines[1])) {
                blocks += AgentResponseBlock.Table(
                    header = tableCells(tableLines[0]),
                    rows = tableLines.drop(2).map(::tableCells),
                )
            } else {
                // Not a real table (no separator row): keep the raw lines as prose instead of mangling them.
                paragraph += tableLines
            }
            tableLines.clear()
        }
        source.replace("\r\n", "\n").lines().forEach { raw ->
            val line = raw.trimEnd()
            if (codeLanguage != null) {
                if (line.trimStart().startsWith("```")) {
                    blocks += AgentResponseBlock.Code(codeLanguage!!.ifBlank { null }, code.joinToString("\n"))
                    code.clear()
                    codeLanguage = null
                } else {
                    code += raw
                }
                return@forEach
            }
            val trimmed = line.trimStart()
            if (tableLines.isNotEmpty()) {
                if (trimmed.startsWith("|")) {
                    tableLines += trimmed
                    return@forEach
                }
                flushTable()
            }
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    codeLanguage = trimmed.removePrefix("```").trim()
                }

                trimmed.isBlank() -> flushParagraph()

                trimmed.startsWith("|") -> {
                    flushParagraph()
                    tableLines += trimmed
                }

                HEADING.matches(trimmed) -> {
                    flushParagraph()
                    val match = HEADING.matchEntire(trimmed)!!
                    blocks +=
                        AgentResponseBlock.Heading(match.groupValues[1].length, match.groupValues[2])
                }

                BULLET.matches(trimmed) -> {
                    flushParagraph()
                    val match = BULLET.matchEntire(trimmed)!!
                    blocks +=
                        AgentResponseBlock.ListItem("•", match.groupValues[1])
                }

                NUMBERED.matches(trimmed) -> {
                    flushParagraph()
                    val match = NUMBERED.matchEntire(trimmed)!!
                    blocks +=
                        AgentResponseBlock.ListItem("${match.groupValues[1]}.", match.groupValues[2])
                }

                trimmed.startsWith("> ") -> {
                    flushParagraph()
                    blocks +=
                        AgentResponseBlock.Quote(trimmed.removePrefix("> "))
                }

                else -> paragraph += trimmed
            }
        }
        flushTable()
        flushParagraph()
        // Streaming replies frequently end before the closing fence arrives. Render the partial code safely.
        if (codeLanguage !=
            null
        ) {
            blocks += AgentResponseBlock.Code(codeLanguage!!.ifBlank { null }, code.joinToString("\n"))
        }
        return blocks
    }

    private fun tableCells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private val HEADING = Regex("^(#{1,3})\\s+(.+)$")
    private val BULLET = Regex("^[-*+]\\s+(.+)$")
    private val NUMBERED = Regex("^(\\d{1,3})[.)]\\s+(.+)$")
    private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?$")
}

private const val COLLAPSE_CHAR_THRESHOLD = 600
private const val COLLAPSE_BLOCK_THRESHOLD = 8
private const val HEAD_BLOCK_COUNT = 3
private const val HEAD_CHAR_BUDGET = 220

/**
 * §六长文约束：结论先行、详情可展开。超过阈值的回复只渲染头部块（结论段），
 * 其余收进「展开全文」；表格、编号、引用、代码在展开后按原样可读渲染。
 */
@Composable
internal fun AgentRichResponse(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { AgentResponseParser.parse(text) }
    val collapsible = blocks.size > COLLAPSE_BLOCK_THRESHOLD || text.length > COLLAPSE_CHAR_THRESHOLD
    var expanded by remember { mutableStateOf(false) }
    val visibleBlocks = if (!collapsible || expanded) blocks else headBlocks(blocks)
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visibleBlocks.forEach { block ->
                AgentResponseBlockView(block)
            }
            if (collapsible) {
                ZhiBanTextActionButton(
                    text = if (expanded) "收起" else "展开全文",
                    onClick = { expanded = !expanded },
                )
            }
        }
    }
}

private fun headBlocks(blocks: List<AgentResponseBlock>): List<AgentResponseBlock> {
    val head = mutableListOf<AgentResponseBlock>()
    var chars = 0
    for (block in blocks) {
        if (head.size >= HEAD_BLOCK_COUNT || chars >= HEAD_CHAR_BUDGET) break
        head += block
        chars += when (block) {
            is AgentResponseBlock.Heading -> block.text.length
            is AgentResponseBlock.Paragraph -> block.text.length
            is AgentResponseBlock.ListItem -> block.text.length
            is AgentResponseBlock.Quote -> block.text.length
            is AgentResponseBlock.Code -> block.content.length
            is AgentResponseBlock.Table -> block.header.sumOf(String::length)
        }
    }
    return head.ifEmpty { blocks.take(1) }
}

@Composable
private fun AgentResponseBlockView(block: AgentResponseBlock) {
    when (block) {
        is AgentResponseBlock.Heading -> Text(
            inlineMarkup(block.text),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyLarge
            },
        )

        is AgentResponseBlock.Paragraph -> Text(
            inlineMarkup(block.text),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
        )

        is AgentResponseBlock.ListItem -> Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                block.marker,
                Modifier.width(if (block.marker == "•") 14.dp else 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                inlineMarkup(block.text),
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        is AgentResponseBlock.Quote -> Text(
            inlineMarkup(block.text),
            Modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(ZhiBanRadius.ExtraSmall),
            ).padding(ZhiBanSpacing.Md),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        is AgentResponseBlock.Code -> Column(
            Modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(ZhiBanRadius.Small),
            ).padding(ZhiBanSpacing.Md),
        ) {
            block.language?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                block.content,
                Modifier.horizontalScroll(rememberScrollState()),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is AgentResponseBlock.Table -> AgentResponseTable(block)
    }
}

@Composable
private fun AgentResponseTable(table: AgentResponseBlock.Table) {
    val columnCount = (listOf(table.header) + table.rows).maxOfOrNull(List<String>::size) ?: 0
    if (columnCount == 0) return
    val columnWidths = (0 until columnCount).map { column ->
        (listOf(table.header) + table.rows)
            .mapNotNull { row -> row.getOrNull(column) }
            .maxOfOrNull(::cellUnits) ?: 0
    }
    Column(
        Modifier.horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(ZhiBanRadius.Small))
            .padding(vertical = ZhiBanSpacing.Xs),
    ) {
        TableRow(
            cells = table.header,
            columnWidths = columnWidths,
            bold = true,
        )
        table.rows.forEach { row ->
            TableRow(cells = row, columnWidths = columnWidths, bold = false)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, columnWidths: List<Int>, bold: Boolean) {
    Row {
        columnWidths.forEachIndexed { index, units ->
            val width = (units * 8 + 20).coerceIn(72, 240).dp
            Text(
                inlineMarkup(cells.getOrNull(index).orEmpty()),
                modifier = Modifier.width(width).padding(horizontal = 8.dp, vertical = 6.dp),
                color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** Approximate display width: CJK glyphs take about two latin-cell units. */
private fun cellUnits(text: String): Int = text.fold(0) { acc, ch -> acc + if (ch.code > 0x2E7F) 2 else 1 }

internal fun inlineMarkup(source: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val token = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    token.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        val value = match.value
        if (value.startsWith("**")) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(value.removePrefix("**").removeSuffix("**"))
            pop()
        } else {
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
            append(value.removePrefix("`").removeSuffix("`"))
            pop()
        }
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}
