package com.zhiban.rebuild.data.agent

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactMethodNormalizationMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "contact-method-normalization-migration.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun clean() = context.deleteDatabase(name).let { Unit }

    @Test fun migration33To34NormalizesLegacyPhonesAndKeepsBestDuplicate() {
        helper.createDatabase(name, 33).use { db ->
            db.execSQL(
                "INSERT INTO contacts " +
                    "(contactId, displayName, normalizedName, aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('contact-1', '张三', '张三', '[]', '[]', 'USER', 1, 1)",
            )
            insertPhone(db, "preferred-formatted", "+86 138-0013-8000", isPrimary = 1, userConfirmed = 1)
            insertPhone(db, "duplicate-canonical", "13800138000", isPrimary = 0, userConfirmed = 0)
            insertPhone(db, "international", "+852 2123 4567", isPrimary = 0, userConfirmed = 1)
        }

        helper.runMigrationsAndValidate(name, 34, true, AgentDatabase.MIGRATION_33_34).use { db ->
            assertEquals(2, db.int("SELECT COUNT(*) FROM contact_methods"))
            assertEquals(
                "13800138000",
                db.text("SELECT normalizedValue FROM contact_methods WHERE methodId = 'preferred-formatted'"),
            )
            assertEquals(
                0,
                db.int("SELECT COUNT(*) FROM contact_methods WHERE methodId = 'duplicate-canonical'"),
            )
            assertEquals(
                "+85221234567",
                db.text("SELECT normalizedValue FROM contact_methods WHERE methodId = 'international'"),
            )
        }
    }

    private fun insertPhone(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        methodId: String,
        value: String,
        isPrimary: Int,
        userConfirmed: Int,
    ) {
        db.execSQL(
            "INSERT INTO contact_methods " +
                "(methodId, contactId, kind, value, normalizedValue, isPrimary, source, confidence, " +
                "userConfirmed, createdAtEpochMs, updatedAtEpochMs) " +
                "VALUES (?, 'contact-1', 'PHONE', ?, ?, ?, 'USER', 0.8, ?, 1, 1)",
            arrayOf<Any>(methodId, value, value, isPrimary, userConfirmed),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.int(query: String): Int =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.text(query: String): String =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}
