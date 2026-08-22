package com.zhiban.rebuild.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

/**
 * The one button hierarchy every page uses.
 *
 * A page gets at most one [ZhiBanPrimaryButton]; supporting actions use
 * [ZhiBanSecondaryButton] or [ZhiBanTextActionButton], and destructive
 * confirmations use [ZhiBanDangerButton]. Local Button/ButtonDefaults
 * combinations are not allowed to drift from these shapes and heights.
 */
@Composable
fun ZhiBanPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ZhiBanSize.Control),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(ZhiBanRadius.Card),
        colors = ButtonDefaults.buttonColors(),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(ZhiBanIconSize.Inline),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(ZhiBanSpacing.Sm))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ZhiBanIconSize.Inline))
            Spacer(Modifier.width(ZhiBanSpacing.Sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Quiet companion to the single primary action: tonal surface, same geometry. */
@Composable
fun ZhiBanSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ZhiBanSize.Control),
        enabled = enabled,
        shape = RoundedCornerShape(ZhiBanRadius.Card),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ZhiBanIconSize.Inline))
            Spacer(Modifier.width(ZhiBanSpacing.Sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Solid error fill reserved for confirming destructive actions. */
@Composable
fun ZhiBanDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ZhiBanSize.Control),
        enabled = enabled,
        shape = RoundedCornerShape(ZhiBanRadius.Card),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Lowest-emphasis action; still keeps the 48 dp target and shared type. */
@Composable
fun ZhiBanTextActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ZhiBanSize.Control),
        enabled = enabled,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Confirm action for [ZhiBanAlertDialog]'s confirmButton slot. */
@Composable
fun ZhiBanDialogConfirmButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ZhiBanSize.DialogAction),
        enabled = enabled,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Dismiss action for [ZhiBanAlertDialog]'s dismissButton slot. */
@Composable
fun ZhiBanDialogDismissButton(text: String = "取消", onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ZhiBanSize.DialogAction),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Numeric badge pinned to the top-end of header icons. Counts above 99
 * collapse so the header geometry never shifts.
 */
@Composable
fun ZhiBanBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(ZhiBanRadius.Full))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Compressed empty state: one status line, at most one primary and one
 * secondary action. Never a half-screen blank card.
 */
@Composable
fun ZhiBanCompactEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = ZhiBanSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(ZhiBanIconContainer.Compact)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ZhiBanIconSize.EmptyState),
                )
            }
            Spacer(Modifier.height(ZhiBanSpacing.Md))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(ZhiBanSpacing.Xs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(ZhiBanSpacing.Lg))
            ZhiBanPrimaryButton(text = primaryLabel, onClick = onPrimary)
        }
        if (secondaryLabel != null && onSecondary != null) {
            ZhiBanTextActionButton(text = secondaryLabel, onClick = onSecondary)
        }
    }
}

/** Single loading presentation: one indicator plus an optional line. */
@Composable
fun ZhiBanLoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = ZhiBanSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(ZhiBanIconSize.EmptyState),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        if (!label.isNullOrBlank()) {
            Spacer(Modifier.height(ZhiBanSpacing.Md))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Unified error presentation with a single retry action. */
@Composable
fun ZhiBanErrorState(message: String, modifier: Modifier = Modifier, retryLabel: String? = null, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = ZhiBanSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (retryLabel != null && onRetry != null) {
            Spacer(Modifier.height(ZhiBanSpacing.Md))
            ZhiBanSecondaryButton(text = retryLabel, onClick = onRetry)
        }
    }
}

/** One option inside [ZhiBanOptionSheet]. */
data class ZhiBanOption<T>(val value: T, val label: String, val subtitle: String? = null)

/**
 * Canonical single-choice bottom sheet. List selection belongs here instead
 * of in AlertDialog; short confirms stay in dialogs, and heavy editing moves
 * to a full page.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun <T> ZhiBanOptionSheet(
    title: String,
    options: List<ZhiBanOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZhiBanBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xl),
        )
        Spacer(Modifier.height(ZhiBanSpacing.Sm))
        options.forEach { option ->
            val isSelected = option.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ZhiBanSize.ListRow)
                    .clickable(role = Role.Button) {
                        onSelect(option.value)
                        onDismissRequest()
                    }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = ZhiBanSpacing.Xl, vertical = ZhiBanSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    option.subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isSelected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(ZhiBanIconSize.Field),
                    )
                }
            }
        }
        Spacer(Modifier.height(ZhiBanSpacing.Lg))
    }
}
