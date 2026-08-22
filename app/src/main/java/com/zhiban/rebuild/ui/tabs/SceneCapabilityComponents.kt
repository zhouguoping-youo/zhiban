package com.zhiban.rebuild.ui.tabs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.zhiban.rebuild.ui.components.ZhiBanCompactEmptyState

/** Shared first-run state for every scene capability workbench. */
@Composable
internal fun SceneCapabilityEmptyState(
    icon: ImageVector,
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryTestTag: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    testTag: String? = null,
) {
    ZhiBanCompactEmptyState(
        title = title,
        modifier = modifier.then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        subtitle = supportingText,
        icon = icon,
        primaryLabel = primaryLabel,
        onPrimary = onPrimary,
        primaryModifier = if (primaryTestTag == null) Modifier else Modifier.testTag(primaryTestTag),
        secondaryLabel = secondaryLabel,
        onSecondary = onSecondary,
    )
}
