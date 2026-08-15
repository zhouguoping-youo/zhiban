package com.zhiban.rebuild.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiban.rebuild.ui.theme.Gray200
import com.zhiban.rebuild.ui.theme.Gray500
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanWarmBackground

val ZhiBanTabHorizontalPadding = ZhiBanSpacing.PageHorizontal
val ZhiBanTabTopPadding = ZhiBanSpacing.PageTop
val ZhiBanTabBottomSpacer = ZhiBanSpacing.PageBottom

@Composable
fun ZhiBanPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/**
 * Shared header for the five primary destinations.
 *
 * Every tab reserves the same 64 dp row, title role, subtitle role and trailing
 * action geometry. This keeps title baselines and 48 dp action targets aligned
 * even when the number of actions differs.
 */
@Composable
fun ZhiBanPrimaryTabHeader(title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ZhiBanSize.TopBar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZhiBanHeaderTitleBlock(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * One title block for primary, secondary and conversation headers.
 *
 * The block wraps its own content height: a header without a subtitle is exactly
 * as tall as its title, so the title's optical centre lands on the same row as the
 * leading back/action icons. Reserving an empty subtitle line pushed a bare title
 * above that icon row, which is the misalignment this avoids.
 */
@Composable
fun ZhiBanHeaderTitleBlock(title: String, subtitle: String?, modifier: Modifier = Modifier, horizontalAlignment: Alignment.Horizontal = Alignment.Start) {
    val subtitleStyle = MaterialTheme.typography.bodySmall
    Column(
        modifier = modifier.wrapContentHeight(),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = subtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A single optical grid for every action shown in a primary-tab header. */
@Composable
fun ZhiBanHeaderIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onBackground,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(ZhiBanIconContainer.TouchTarget),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ZhiBanIconSize.Action),
        )
    }
}

/** Text actions share the same 48 dp row and typography as adjacent icon actions. */
@Composable
fun ZhiBanHeaderTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onBackground) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ZhiBanIconContainer.TouchTarget),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ZhiBanSpacing.Sm),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

@Composable
fun ZhiBanLeadingIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier = modifier
            .size(ZhiBanIconContainer.Compact)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ZhiBanIconSize.Leading),
        )
    }
}

/**
 * Canonical content-card surface used across tabs and settings.
 *
 * Dialogs, text inputs and floating composers have their own roles; ordinary
 * page cards must use this modifier so radius and surface color cannot drift.
 */
@Composable
fun Modifier.zhiBanCardSurface(containerColor: Color = MaterialTheme.colorScheme.surface): Modifier =
    clip(RoundedCornerShape(ZhiBanRadius.Card)).background(containerColor)

@Composable
fun ZhiBanGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = ZhiBanRadius.Card,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = Color.Transparent,
    elevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.shadow(
            elevation = elevation,
            shape = RoundedCornerShape(cornerRadius),
            ambientColor = Color.Black.copy(alpha = 0.06f),
            spotColor = Color.Black.copy(alpha = 0.10f),
        ),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = borderColor.takeUnless { it == Color.Transparent }?.let { BorderStroke(1.dp, it) },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(content = content) }
}

/**
 * Shared secondary-page header. Keeping the back target, title baseline and
 * trailing action identical prevents each settings page from inventing its own
 * navigation geometry.
 */
@Composable
fun ZhiBanTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (subtitle == null) ZhiBanSize.TopBar else ZhiBanSize.ListRowWithSubtitle)
            .padding(horizontal = ZhiBanSpacing.PageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(ZhiBanSize.TouchTarget)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(ZhiBanIconSize.Action),
                )
            }
            Spacer(Modifier.width(ZhiBanSpacing.Xs))
        }
        ZhiBanHeaderTitleBlock(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

enum class ZhiBanSaveState {
    IDLE,
    SAVING,
    SAVED,
}

/** Shared save action for editable secondary and tertiary pages. */
@Composable
fun ZhiBanSaveButton(state: ZhiBanSaveState, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, idleLabel: String = "保存") {
    val saved = state == ZhiBanSaveState.SAVED
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = ZhiBanSize.Control),
        enabled = enabled && state != ZhiBanSaveState.SAVING,
        shape = RoundedCornerShape(ZhiBanRadius.Card),
        colors = if (saved) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (saved) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(ZhiBanIconSize.Inline),
            )
            Spacer(Modifier.width(ZhiBanSpacing.Sm))
        }
        Text(
            when (state) {
                ZhiBanSaveState.IDLE -> idleLabel
                ZhiBanSaveState.SAVING -> "保存中…"
                ZhiBanSaveState.SAVED -> "已保存"
            },
        )
    }
}

/** Canonical row for mutually-exclusive settings. */
@Composable
fun ZhiBanSingleChoiceRow(title: String, subtitle: String = "", selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (subtitle.isBlank()) ZhiBanSize.ListRow else ZhiBanSize.ListRowWithSubtitle,
            )
            .clickable(onClick = onClick)
            .padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
fun ZhiBanGradientIcon(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    brush: Brush = SolidColor(ZhiBanTerracotta),
    textColor: Color = Color.White,
) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 3)).background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = (size.value * 0.38f).sp, color = textColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ZhiBanHeader(title: String, subtitle: String, modifier: Modifier = Modifier, iconText: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (iconText !=
            null
        ) {
            ZhiBanGradientIcon(text = iconText, size = ZhiBanSize.TouchTarget)
            Spacer(modifier = Modifier.width(ZhiBanSpacing.Md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun ZhiBanSectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onActionClick: (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = ZhiBanSize.TouchTarget, minHeight = ZhiBanSize.TouchTarget)
                    .clickable(enabled = onActionClick != null) { onActionClick?.invoke() },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ZhiBanChip(text: String, selected: Boolean, modifier: Modifier = Modifier, color: Color = ZhiBanTerracotta, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = ZhiBanSize.TouchTarget, minHeight = ZhiBanSize.TouchTarget)
            .alpha(if (enabled) 1f else 0.48f)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(ZhiBanRadius.Full))
                .background(if (selected) color else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = ZhiBanSpacing.Lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ZhiBanSegmentedControl(options: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = ZhiBanSize.Control)
            .clip(RoundedCornerShape(ZhiBanRadius.Full))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(ZhiBanSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val sel = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(RoundedCornerShape(ZhiBanRadius.Full))
                    .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .semantics { selected = sel }
                    .clickable(role = Role.Button) { onSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun RowScope.ZhiBanStatTile(value: String, label: String, iconText: String, accent: Color = ZhiBanTerracotta) {
    ZhiBanGlassCard(modifier = Modifier.weight(1f), cornerRadius = ZhiBanRadius.Card) {
        Column(modifier = Modifier.padding(ZhiBanSpacing.Lg)) {
            ZhiBanGradientIcon(
                text = iconText,
                size = 34.dp,
                brush = Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f))),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = ZhiBanTextPrimary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Gray500)
        }
    }
}

@Composable
fun RowScope.ZhiBanCompactMetric(value: String, label: String, iconText: String, accent: Color = ZhiBanTerracotta) {
    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(ZhiBanRadius.Small)).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(iconText, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = Gray500)
        }
    }
}

@Composable
fun ZhiBanEmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ZhiBanMiniButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier.defaultMinSize(
            minHeight = ZhiBanSize.TouchTarget,
        ).clip(
            RoundedCornerShape(ZhiBanRadius.Full),
        ).background(
            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        ).clickable(
            enabled = enabled,
            onClick = onClick,
        ).padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ZhiBanSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    ZhiBanTextInput(value = value, onValueChange = onValueChange, placeholder = placeholder, modifier = modifier, minHeight = ZhiBanSize.Control, leading = {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.Search,
            contentDescription = null,
            tint = Gray500,
            modifier = Modifier.size(ZhiBanIconSize.Field),
        )
    })
}

@Composable
fun ZhiBanTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minHeight: Dp = ZhiBanSize.Input,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(ZhiBanRadius.Input)
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(
            minHeight = minHeight,
        ).clip(
            shape,
        ).background(
            MaterialTheme.colorScheme.surface,
        ).border(
            1.dp,
            borderColor,
            shape,
        ).padding(horizontal = ZhiBanSpacing.Lg, vertical = if (singleLine) 0.dp else ZhiBanSpacing.Md),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            leading()
            Box(modifier = Modifier.width(10.dp))
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine, maxLines = maxLines,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
            interactionSource = interactionSource, cursorBrush = SolidColor(ZhiBanTerracotta),
            textStyle = MaterialTheme.typography.bodyMedium.merge(
                androidx.compose.ui.text.TextStyle(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ),
            modifier = Modifier.weight(1f).then(
                if (singleLine) {
                    Modifier.defaultMinSize(minHeight = minHeight)
                } else {
                    Modifier.defaultMinSize(minHeight = 72.dp)
                },
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (trailing != null) {
            Box(modifier = Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
fun ZhiBanProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, color: Color = ZhiBanTerracotta) {
    Box(
        modifier = modifier.fillMaxWidth().height(
            height,
        ).clip(RoundedCornerShape(ZhiBanRadius.Full)).background(com.zhiban.rebuild.ui.theme.Gray100),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(
                progress.coerceIn(0f, 1f),
            ).height(
                height,
            ).clip(RoundedCornerShape(ZhiBanRadius.Full)).background(Brush.horizontalGradient(listOf(color, ZhiBanTerracotta))),
        )
    }
}
