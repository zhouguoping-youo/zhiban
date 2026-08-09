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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

internal sealed interface AgentResponseBlock {
    data class Heading(val level: Int, val text: String) : AgentResponseBlock
    data class Paragraph(val text: String) : AgentResponseBlock
    data class ListItem(val marker: String, val text: String) : AgentResponseBlock
    data class Quote(val text: String) : AgentResponseBlock
    data class Code(val language: String?, val content: String) : AgentResponseBlock
}

internal object AgentResponseParser {
    fun parse(source: String): List<AgentResponseBlock> {
        val blocks = mutableListOf<AgentResponseBlock>()
        val paragraph = mutableListOf<String>()
        var codeLanguage: String? = null
        val code = mutableListOf<String>()
        fun flushParagraph() {
            paragraph.joinToString(" ").trim().takeIf(String::isNotEmpty)?.let {
                blocks +=
                    AgentResponseBlock.Paragraph(it)
            }
            paragraph.clear()
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
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    codeLanguage = trimmed.removePrefix("```").trim()
                }

                trimmed.isBlank() -> flushParagraph()

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
        flushParagraph()
        // Streaming replies frequently end before the closing fence arrives. Render the partial code safely.
        if (codeLanguage !=
            null
        ) {
            blocks += AgentResponseBlock.Code(codeLanguage!!.ifBlank { null }, code.joinToString("\n"))
        }
        return blocks
    }

    private val HEADING = Regex("^(#{1,3})\\s+(.+)$")
    private val BULLET = Regex("^[-*+]\\s+(.+)$")
    private val NUMBERED = Regex("^(\\d{1,3})[.)]\\s+(.+)$")
}

@Composable
internal fun AgentRichResponse(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentResponseParser.parse(text).forEach { block ->
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

                    is AgentResponseBlock.ListItem -> Row {
                        Text(block.marker, Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
            }
        }
    }
}

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
