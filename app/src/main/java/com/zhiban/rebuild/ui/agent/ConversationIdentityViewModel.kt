package com.zhiban.rebuild.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ConversationIdentityUi(val avatarBytes: ByteArray? = null, val fallbackLabel: String = "我")

@HiltViewModel
class ConversationIdentityViewModel @Inject constructor(store: UserProfileStore) : ViewModel() {
    val identity = store.profile
        .map { profile ->
            ConversationIdentityUi(
                avatarBytes = runSuspendCatching { store.readAvatarBytes(profile.avatarUri) }.getOrNull(),
                fallbackLabel = profile.avatarLabel(),
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ConversationIdentityUi(fallbackLabel = store.profile.value.avatarLabel()),
        )
}

private fun UserProfile.avatarLabel(): String = preferredName.ifBlank { name }.take(1).ifBlank { "我" }
