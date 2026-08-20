package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity

/** Keeps merged-source accounts readable without losing the newest/confirmed source. */
internal fun deduplicatePlatformIdentities(identities: List<ContactPlatformIdentityEntity>): List<ContactPlatformIdentityEntity> = identities
    .sortedWith(
        compareByDescending<ContactPlatformIdentityEntity> { it.userConfirmed }
            .thenByDescending { it.updatedAtEpochMs },
    )
    .distinctBy { identity ->
        identity.platform.trim().uppercase() to
            identity.normalizedHandle.trim().trimStart('@').lowercase().ifBlank {
                identity.handle.trim().trimStart('@').lowercase()
            }
    }
