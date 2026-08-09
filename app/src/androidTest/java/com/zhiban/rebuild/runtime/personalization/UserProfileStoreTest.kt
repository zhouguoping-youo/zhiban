package com.zhiban.rebuild.runtime.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = System.nanoTime().toString()
    private val preferencesName = "user_profile_test_$suffix"
    private val avatarDirectoryName = "avatar_test_$suffix"

    @After fun cleanUp() {
        context.deleteSharedPreferences(preferencesName)
        File(context.filesDir, avatarDirectoryName).deleteRecursively()
    }

    @Test
    fun mergeMissingIdentityOnlyFillsBlankFields() {
        val store = UserProfileStore(context, preferencesName, avatarDirectoryName)
        store.clear()
        store.save(
            UserProfile(
                name = "",
                phone = "",
                wechatId = "",
                preferredName = "",
                douyinId = "",
            ),
        )
        assertFalse(store.hasIdentity())

        store.mergeMissingIdentity(name = "周国平", phone = "13800138000", wechatId = "wx-id-01")
        val filled = store.profile.value
        assertEquals("周国平", filled.name)
        assertEquals("13800138000", filled.phone)
        assertEquals("wx-id-01", filled.wechatId)
        assertTrue(store.hasIdentity())

        store.mergeMissingIdentity(name = "改名", phone = "199", wechatId = "other")
        val unchanged = store.profile.value
        assertEquals("周国平", unchanged.name)
        assertEquals("13800138000", unchanged.phone)
        assertEquals("wx-id-01", unchanged.wechatId)
    }

    @Test
    fun avatarIsEncryptedAtRestAndCanBeReadBack() = kotlinx.coroutines.test.runTest {
        val store = UserProfileStore(context, preferencesName, avatarDirectoryName)
        val source = "private-avatar-pixels-$suffix".encodeToByteArray()

        val path = store.persistAvatarBytes(source)
        val persisted = File(path).readBytes()

        assertFalse(persisted.contentEquals(source))
        assertFalse(persisted.decodeToString().contains("private-avatar-pixels"))
        assertTrue(store.readAvatarBytes(path)!!.contentEquals(source))
    }

    @Test
    fun legacyPlaintextAvatarMigratesOnFirstRead() = kotlinx.coroutines.test.runTest {
        val store = UserProfileStore(context, preferencesName, avatarDirectoryName)
        val source = "legacy-private-avatar-$suffix".encodeToByteArray()
        val legacy = File(context.filesDir, "$avatarDirectoryName/avatar.png").apply {
            parentFile!!.mkdirs()
            writeBytes(source)
        }
        store.save(UserProfile(avatarUri = legacy.absolutePath))

        val loaded = store.readAvatarBytes(legacy.absolutePath)

        assertTrue(loaded!!.contentEquals(source))
        assertFalse(legacy.exists())
        val encryptedPath = store.profile.value.avatarUri!!
        assertTrue(encryptedPath.endsWith(".enc"))
        assertFalse(File(encryptedPath).readBytes().decodeToString().contains("legacy-private-avatar"))
    }
}
