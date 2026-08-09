package com.zhiban.rebuild.ui.tabs

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
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanSize

@Composable
internal fun OwnerContactRow(profile: UserProfile, onClick: () -> Unit) {
    val displayName = profile.displayNameOrMe()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(RelationAccent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                displayName.take(1),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                color = RelationInk,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (profile.name.isBlank() && profile.preferredName.isBlank()) {
                    "完善称呼、手机号和社交账号"
                } else {
                    "我的资料 · 知伴认识的我"
                },
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "本人",
            color = RelationInk,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.background(RelationSoft, RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(RelationSoft), contentAlignment = Alignment.Center) {
            Text(
                contact.displayName.take(1),
                color = RelationInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.displayName,
                color = RelationInk,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = listOfNotNull(contact.company, contact.title, contact.phone).take(2).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        contact.tagsJson.firstKnownTag()?.let {
            Text(
                it,
                color = RelationMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(
                    RelationSoft,
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun RelationEmpty(searching: Boolean, onImport: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface).padding(vertical = 30.dp, horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(RelationSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Groups, null, tint = RelationInk, modifier = Modifier.size(ZhiBanIconSize.Action))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (searching) "没有找到联系人" else "还没有联系人",
            color = RelationInk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) "换个词或分类试试" else "从通讯录选择重要的人",
            color = RelationMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!searching) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onImport,
                colors = ButtonDefaults.buttonColors(containerColor = RelationAccent),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Outlined.PhoneAndroid, null, modifier = Modifier.size(ZhiBanIconSize.Inline))
                Spacer(Modifier.width(6.dp))
                Text("导入联系人")
            }
            TextButton(onClick = onAdd, modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)) {
                Text("手动添加", color = RelationMuted)
            }
        }
    }
}
