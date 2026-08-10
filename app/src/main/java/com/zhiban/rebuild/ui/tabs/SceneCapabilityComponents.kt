package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .zhiBanCardSurface()
            .padding(horizontal = ZhiBanSpacing.Xl, vertical = ZhiBanSpacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        ZhiBanLeadingIcon(icon, tint = MaterialTheme.colorScheme.primary)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            supportingText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            Button(
                onClick = onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ZhiBanSize.TouchTarget)
                    .then(if (primaryTestTag == null) Modifier else Modifier.testTag(primaryTestTag)),
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(
                onClick = onSecondary,
                modifier = Modifier.height(ZhiBanSize.TouchTarget),
            ) {
                Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
