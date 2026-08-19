package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsActiveDeletedIndexMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun upgradeFromCallbackIndexedV42PassesValidation() {
        // 真机崩溃场景复现(2026-08-19):旧安装的 CALLBACK 在 onOpen 建了 contacts 部分索引
        // index_contacts_active_deleted(9c2c875 起,非 Room 托管)。该索引留在库中时,
        // 42→43/43→44/44→45 的迁移链跑完,Room 校验因多余索引抛
        // "Migration didn't properly handle: contacts" 而崩溃循环。45→46 在验证前删除
        // CALLBACK 托管的两个部分索引,让整条升级链通过校验。
        val name = "contacts-active-deleted-upgrade.db"
        helper.createDatabase(name, 42).apply {
            execSQL(
                "CREATE INDEX IF NOT EXISTS `index_contacts_active_deleted` ON `contacts` (`deletedAtEpochMs`) " +
                    "WHERE `deletedAtEpochMs` IS NULL",
            )
            execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_runs_single_active_per_definition` " +
                    "ON `plan_runs` (`definitionId`) WHERE `runStatus` = 'ACTIVE'",
            )
            close()
        }
        // 校验通过即成功;若多余索引未清,此调用会抛 IllegalStateException。
        helper.runMigrationsAndValidate(
            name,
            46,
            true,
            AgentDatabase.MIGRATION_42_43,
            AgentDatabase.MIGRATION_43_44,
            AgentDatabase.MIGRATION_44_45,
            AgentDatabase.MIGRATION_45_46,
        ).close()
    }
}
