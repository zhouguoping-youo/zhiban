package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactIntelligenceMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration35To36BackfillsPeopleAndDowngradesUnverifiedSystemFacts() {
        val name = "contact-intelligence-migration.db"
        helper.createDatabase(name, 35).use { db ->
            db.execSQL(
                """INSERT INTO contacts (
                    contactId, displayName, normalizedName, phone, company, title,
                    aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs
                ) VALUES (
                    'contact-1', '丁波', '丁波', '13800138000', '旧公司', '售前',
                    '[]', '[]', 'SYSTEM_CONTACT:lookup-1', 10, 20
                )""",
            )
            db.execSQL(
                """INSERT INTO organizations (
                    organizationId, canonicalName, normalizedName, source, sourceRef,
                    userConfirmed, createdAtEpochMs, updatedAtEpochMs
                ) VALUES ('org-1', '旧公司', '旧公司', 'SYSTEM_CONTACT', 'android-contact:lookup-1', 1, 10, 20)""",
            )
            db.execSQL(
                """INSERT INTO contact_employments (
                    employmentId, contactId, organizationId, companyNameSnapshot, title,
                    isCurrent, source, evidenceRef, confidence, userConfirmed,
                    createdAtEpochMs, updatedAtEpochMs
                ) VALUES (
                    'employment-1', 'contact-1', 'org-1', '旧公司', '售前',
                    1, 'SYSTEM_CONTACT', 'android-contact:lookup-1', 0.8, 1, 10, 20
                )""",
            )
        }

        helper.runMigrationsAndValidate(name, 36, true, AgentDatabase.MIGRATION_35_36).use { db ->
            db.query("SELECT canonicalContactId, kind FROM persons WHERE personId = 'contact-1'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "contact-1")
                check(cursor.getString(1) == "CONTACT")
            }
            db.query("SELECT kind FROM persons WHERE personId = 'user:self'").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "OWNER")
            }
            db.query(
                "SELECT isCurrent, userConfirmed, confidence FROM contact_employments " +
                    "WHERE employmentId = 'employment-1'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 0)
                check(cursor.getInt(1) == 0)
                check(cursor.getDouble(2) == 0.6)
            }
            db.query(
                "SELECT currentState, verificationState FROM person_employment_episodes " +
                    "WHERE episodeId = 'employment-1'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "UNKNOWN")
                check(cursor.getString(1) == "OBSERVED")
            }
            db.query(
                "SELECT verificationState FROM identity_claims " +
                    "WHERE personId = 'contact-1' AND fieldType = 'COMPANY'",
            ).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "OBSERVED")
            }
        }
    }
}
