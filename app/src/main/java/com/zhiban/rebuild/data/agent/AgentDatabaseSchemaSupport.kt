package com.zhiban.rebuild.data.agent

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Shared SQL helpers used by migrations and defensive database-open repair. */
private const val CREATE_CONTACT_INTELLIGENCE_TABLE_1 = """CREATE TABLE IF NOT EXISTS `persons` (
            `personId` TEXT NOT NULL,
            `canonicalContactId` TEXT,
            `displayName` TEXT NOT NULL,
            `normalizedName` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `createdAtEpochMs` INTEGER NOT NULL,
            `updatedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`personId`),
            FOREIGN KEY(`canonicalContactId`) REFERENCES `contacts`(`contactId`)
                ON UPDATE NO ACTION ON DELETE SET NULL
        )"""
private const val CREATE_CONTACT_INTELLIGENCE_TABLE_2 = """CREATE TABLE IF NOT EXISTS `source_identities` (
            `sourceIdentityId` TEXT NOT NULL,
            `personId` TEXT,
            `sourceType` TEXT NOT NULL,
            `accountScope` TEXT NOT NULL,
            `tenantId` TEXT,
            `stableExternalId` TEXT,
            `visibleHandle` TEXT NOT NULL,
            `normalizedHandle` TEXT NOT NULL,
            `conversationScopeId` TEXT,
            `resolutionStatus` TEXT NOT NULL,
            `confidence` REAL NOT NULL,
            `sourceRef` TEXT,
            `firstObservedAtEpochMs` INTEGER NOT NULL,
            `lastObservedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`sourceIdentityId`),
            FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                ON UPDATE NO ACTION ON DELETE SET NULL
        )"""
private const val CREATE_CONTACT_INTELLIGENCE_TABLE_3 = """CREATE TABLE IF NOT EXISTS `identity_claims` (
            `claimId` TEXT NOT NULL,
            `personId` TEXT NOT NULL,
            `fieldType` TEXT NOT NULL,
            `displayValue` TEXT NOT NULL,
            `normalizedValue` TEXT NOT NULL,
            `validFromEpochMs` INTEGER,
            `validToEpochMs` INTEGER,
            `temporalPrecision` TEXT NOT NULL,
            `recordedAtEpochMs` INTEGER NOT NULL,
            `sourceIdentityId` TEXT,
            `sourceRef` TEXT,
            `confidence` REAL NOT NULL,
            `verificationState` TEXT NOT NULL,
            `supersedesClaimId` TEXT,
            `status` TEXT NOT NULL,
            PRIMARY KEY(`claimId`),
            FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sourceIdentityId`) REFERENCES `source_identities`(`sourceIdentityId`)
                ON UPDATE NO ACTION ON DELETE SET NULL
        )"""

private fun createContactIntelligencePart1Sub1(db: SupportSQLiteDatabase) {
    db.execSQL(CREATE_CONTACT_INTELLIGENCE_TABLE_1)
    db.execSQL(CREATE_CONTACT_INTELLIGENCE_TABLE_2)
    db.execSQL(CREATE_CONTACT_INTELLIGENCE_TABLE_3)
}

private fun createContactIntelligencePart1(db: SupportSQLiteDatabase) {
    createContactIntelligencePart1Sub1(db)
}

internal fun createContactIntelligenceTables(db: SupportSQLiteDatabase) {
    createContactIntelligencePart1(db)
}

internal fun createEmploymentEpisodeTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `person_employment_episodes` (
            `episodeId` TEXT NOT NULL,
            `personId` TEXT NOT NULL,
            `organizationId` TEXT,
            `companyNameSnapshot` TEXT NOT NULL,
            `department` TEXT,
            `title` TEXT,
            `validFromEpochMs` INTEGER,
            `validToEpochMs` INTEGER,
            `temporalPrecision` TEXT NOT NULL,
            `currentState` TEXT NOT NULL,
            `sourceRef` TEXT,
            `confidence` REAL NOT NULL,
            `verificationState` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `recordedAtEpochMs` INTEGER NOT NULL,
            `updatedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`episodeId`),
            FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`organizationId`) REFERENCES `organizations`(`organizationId`)
                ON UPDATE NO ACTION ON DELETE SET NULL
        )""",
    )
    createIndices(
        db,
        "person_employment_episodes",
        "personId" to false,
        "organizationId" to false,
        "validFromEpochMs" to false,
        "validToEpochMs" to false,
        "status" to false,
    )
}

internal fun createRelationshipEpisodeTable(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `relationship_episodes` (
            `episodeId` TEXT NOT NULL,
            `fromPersonId` TEXT NOT NULL,
            `toPersonId` TEXT NOT NULL,
            `relationshipType` TEXT NOT NULL,
            `direction` TEXT NOT NULL,
            `validFromEpochMs` INTEGER,
            `validToEpochMs` INTEGER,
            `temporalPrecision` TEXT NOT NULL,
            `evidenceRefsJson` TEXT NOT NULL,
            `confidence` REAL NOT NULL,
            `verificationState` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `recordedAtEpochMs` INTEGER NOT NULL,
            `updatedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`episodeId`),
            FOREIGN KEY(`fromPersonId`) REFERENCES `persons`(`personId`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`toPersonId`) REFERENCES `persons`(`personId`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )""",
    )
    createIndices(
        db,
        "relationship_episodes",
        "fromPersonId" to false,
        "toPersonId" to false,
        "relationshipType" to false,
        "validFromEpochMs" to false,
        "validToEpochMs" to false,
        "status" to false,
    )
}

internal fun createGroupTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `group_conversations` (
            `groupId` TEXT NOT NULL,
            `platform` TEXT NOT NULL,
            `accountScope` TEXT NOT NULL,
            `stableGroupId` TEXT,
            `displayName` TEXT NOT NULL,
            `sourceRef` TEXT,
            `firstObservedAtEpochMs` INTEGER NOT NULL,
            `lastObservedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`groupId`)
        )""",
    )
    createIndices(
        db,
        "group_conversations",
        "platform,accountScope,stableGroupId" to false,
        "lastObservedAtEpochMs" to false,
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `group_membership_episodes` (
            `membershipId` TEXT NOT NULL,
            `groupId` TEXT NOT NULL,
            `sourceIdentityId` TEXT NOT NULL,
            `groupAlias` TEXT,
            `validFromEpochMs` INTEGER,
            `validToEpochMs` INTEGER,
            `status` TEXT NOT NULL,
            `confidence` REAL NOT NULL,
            `sourceRef` TEXT,
            `recordedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`membershipId`),
            FOREIGN KEY(`groupId`) REFERENCES `group_conversations`(`groupId`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sourceIdentityId`) REFERENCES `source_identities`(`sourceIdentityId`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )""",
    )
    createIndices(
        db,
        "group_membership_episodes",
        "groupId" to false,
        "sourceIdentityId" to false,
        "validToEpochMs" to false,
        "status" to false,
    )
}

internal fun createAndroidSyncTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `android_raw_contact_links` (
            `linkId` TEXT NOT NULL,
            `personId` TEXT NOT NULL,
            `aggregateContactId` INTEGER NOT NULL,
            `lookupKey` TEXT NOT NULL,
            `rawContactId` INTEGER NOT NULL,
            `accountName` TEXT,
            `accountType` TEXT,
            `sourceId` TEXT,
            `version` INTEGER NOT NULL,
            `isReadOnly` INTEGER NOT NULL,
            `lastObservedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`linkId`),
            FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )""",
    )
    createIndices(
        db,
        "android_raw_contact_links",
        "personId" to false,
        "aggregateContactId" to false,
        "lookupKey" to false,
        "rawContactId" to true,
        "accountType,accountName" to false,
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `contact_sync_snapshots` (
            `snapshotId` TEXT NOT NULL,
            `linkId` TEXT NOT NULL,
            `baseProjectionJson` TEXT NOT NULL,
            `baseDigest` TEXT NOT NULL,
            `desiredProjectionJson` TEXT,
            `desiredDigest` TEXT,
            `syncState` TEXT NOT NULL,
            `lastVerifiedAtEpochMs` INTEGER,
            `updatedAtEpochMs` INTEGER NOT NULL,
            PRIMARY KEY(`snapshotId`),
            FOREIGN KEY(`linkId`) REFERENCES `android_raw_contact_links`(`linkId`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )""",
    )
    createIndices(
        db,
        "contact_sync_snapshots",
        "linkId" to true,
        "syncState" to false,
        "updatedAtEpochMs" to false,
    )
}

internal fun createIndices(db: SupportSQLiteDatabase, table: String, vararg columns: Pair<String, Boolean>) {
    columns.forEach { (columnList, unique) ->
        val indexColumns = columnList.split(',')
        val indexName = "index_${table}_${indexColumns.joinToString("_")}"
        val sqlColumns = indexColumns.joinToString(", ") { "`$it`" }
        db.execSQL("CREATE ${if (unique) "UNIQUE " else ""}INDEX IF NOT EXISTS `$indexName` ON `$table` ($sqlColumns)")
    }
}

internal fun downgradeUnverifiedSystemContactFacts(db: SupportSQLiteDatabase) {
    db.execSQL(
        "UPDATE contact_employments SET isCurrent = 0, userConfirmed = 0, confidence = MIN(confidence, 0.6) " +
            "WHERE source = 'SYSTEM_CONTACT'",
    )
    db.execSQL("UPDATE organizations SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
    db.execSQL("UPDATE contact_methods SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
    db.execSQL("UPDATE contact_addresses SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
    db.execSQL("UPDATE contact_important_dates SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
    db.execSQL("UPDATE contact_platform_identities SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
}

internal fun backfillPeopleAndSourceIdentities(db: SupportSQLiteDatabase) {
    db.execSQL(
        """INSERT OR IGNORE INTO persons (
            personId, canonicalContactId, displayName, normalizedName, kind, status,
            createdAtEpochMs, updatedAtEpochMs
        ) SELECT contactId, contactId, displayName, normalizedName, 'CONTACT',
            CASE WHEN deletedAtEpochMs IS NULL THEN 'ACTIVE' ELSE 'DELETED' END,
            createdAtEpochMs, updatedAtEpochMs FROM contacts""",
    )
    db.execSQL(
        """INSERT OR IGNORE INTO persons VALUES (
            'user:self', NULL, '我', '我', 'OWNER', 'ACTIVE', 0, 0
        )""",
    )
    db.execSQL(
        """INSERT OR IGNORE INTO source_identities (
            sourceIdentityId, personId, sourceType, accountScope, tenantId,
            stableExternalId, visibleHandle, normalizedHandle, conversationScopeId,
            resolutionStatus, confidence, sourceRef, firstObservedAtEpochMs, lastObservedAtEpochMs
        ) SELECT 'contact:' || contactId, contactId, source, 'LOCAL', NULL,
            NULL, displayName, normalizedName, NULL, 'RESOLVED',
            CASE WHEN source = 'USER' THEN 1.0 ELSE 0.7 END,
            source, createdAtEpochMs, updatedAtEpochMs FROM contacts""",
    )
    db.execSQL(
        """INSERT OR IGNORE INTO source_identities (
            sourceIdentityId, personId, sourceType, accountScope, tenantId,
            stableExternalId, visibleHandle, normalizedHandle, conversationScopeId,
            resolutionStatus, confidence, sourceRef, firstObservedAtEpochMs, lastObservedAtEpochMs
        ) SELECT 'platform:' || identityId, contactId, platform, 'LOCAL', NULL,
            platformUserId, handle, normalizedHandle, NULL, 'RESOLVED',
            CASE WHEN userConfirmed = 1 THEN 1.0 ELSE 0.7 END,
            source, createdAtEpochMs, updatedAtEpochMs FROM contact_platform_identities""",
    )
}

internal fun backfillIdentityClaims(db: SupportSQLiteDatabase) {
    insertContactClaim(db, "NAME", "displayName", "normalizedName")
    insertContactClaim(db, "PHONE", "phone", "phone")
    insertContactClaim(db, "EMAIL", "email", "email")
    insertContactClaim(db, "COMPANY", "company", "company")
    insertContactClaim(db, "TITLE", "title", "title")
}

internal fun insertContactClaim(db: SupportSQLiteDatabase, fieldType: String, displayColumn: String, normalizedColumn: String) {
    db.execSQL(
        """INSERT OR IGNORE INTO identity_claims (
            claimId, personId, fieldType, displayValue, normalizedValue,
            validFromEpochMs, validToEpochMs, temporalPrecision, recordedAtEpochMs,
            sourceIdentityId, sourceRef, confidence, verificationState, supersedesClaimId, status
        ) SELECT 'migration:${fieldType.lowercase()}:' || contactId, contactId, '$fieldType',
            $displayColumn, lower($normalizedColumn), NULL, NULL, 'UNKNOWN', updatedAtEpochMs,
            'contact:' || contactId, source,
            CASE WHEN source = 'USER' THEN 1.0 ELSE 0.6 END,
            CASE WHEN source = 'USER' THEN 'USER_CONFIRMED' ELSE 'OBSERVED' END,
            NULL, 'ACTIVE' FROM contacts WHERE $displayColumn IS NOT NULL AND trim($displayColumn) != ''""",
    )
}

internal fun backfillTemporalEpisodes(db: SupportSQLiteDatabase) {
    db.execSQL(
        """INSERT OR IGNORE INTO person_employment_episodes (
            episodeId, personId, organizationId, companyNameSnapshot, department, title,
            validFromEpochMs, validToEpochMs, temporalPrecision, currentState, sourceRef,
            confidence, verificationState, status, recordedAtEpochMs, updatedAtEpochMs
        ) SELECT employmentId, contactId, organizationId, companyNameSnapshot, department, title,
            NULL, NULL, 'UNKNOWN',
            CASE WHEN isCurrent = 1 AND userConfirmed = 1 THEN 'CURRENT_CONFIRMED' ELSE 'UNKNOWN' END,
            evidenceRef, confidence,
            CASE WHEN userConfirmed = 1 THEN 'USER_CONFIRMED' ELSE 'OBSERVED' END,
            'ACTIVE', createdAtEpochMs, updatedAtEpochMs FROM contact_employments""",
    )
    db.execSQL(
        """INSERT OR IGNORE INTO relationship_episodes (
            episodeId, fromPersonId, toPersonId, relationshipType, direction,
            validFromEpochMs, validToEpochMs, temporalPrecision, evidenceRefsJson,
            confidence, verificationState, status, recordedAtEpochMs, updatedAtEpochMs
        ) SELECT edgeId, fromContactId, toContactId, relationType, 'BIDIRECTIONAL',
            NULL, NULL, 'UNKNOWN', evidenceRefsJson, confidence,
            CASE WHEN userConfirmed = 1 THEN 'USER_CONFIRMED' ELSE 'INFERRED' END,
            CASE WHEN status = 'DELETED' THEN 'DELETED' ELSE 'ACTIVE' END,
            createdAtEpochMs, updatedAtEpochMs FROM relationship_edges
            WHERE EXISTS (SELECT 1 FROM persons WHERE personId = fromContactId)
              AND EXISTS (SELECT 1 FROM persons WHERE personId = toContactId)""",
    )
}

internal val AGENT_DATABASE_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        createPreferenceDetachTrigger(db)
        createSingleCurrentMemoryTrigger(db)
        createSingleCurrentMemoryUpdateTrigger(db)
        createPlanRunsSingleActiveIndex(db)
        createContactsActiveDeletedIndex(db)
        createFactFts(db)
        createContactSearchFts(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        // Foreign-key enforcement is per-connection and off by default. onOpen runs after
        // onCreate/onUpgrade, so migrations keep their legacy FK-off behavior while every
        // runtime write on this (primary/writer) connection enforces the declared cascades.
        // Without this, deleting a memory/fact left orphan child rows: a stale
        // memory_current_versions row blocked re-committing the same logicalMemoryId
        // (CURRENT_MEMORY_EXISTS) and embedding_vectors leaked after their fact was removed.
        db.execSQL("PRAGMA foreign_keys=ON")
        createPreferenceDetachTrigger(db)
        createSingleCurrentMemoryTrigger(db)
        createSingleCurrentMemoryUpdateTrigger(db)
        createPlanRunsSingleActiveIndex(db)
        createContactsActiveDeletedIndex(db)
        createFactFts(db)
        createContactSearchFts(db)
    }

    private fun createPreferenceDetachTrigger(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS detach_user_preferences_before_run_delete
            BEFORE DELETE ON agent_runs
            BEGIN
                UPDATE memories
                SET sourceRunId = NULL
                WHERE sourceRunId = OLD.id AND kind = 'USER_PREFERENCE';
            END
            """.trimIndent(),
        )
    }
}

internal fun createSingleCurrentMemoryTrigger(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS reject_second_current_memory_version
        BEFORE INSERT ON memory_records
        WHEN NEW.txToEpochMs IS NULL AND EXISTS (
            SELECT 1 FROM memory_records
            WHERE namespaceId = NEW.namespaceId
              AND logicalMemoryId = NEW.logicalMemoryId
              AND txToEpochMs IS NULL
        )
        BEGIN
            SELECT RAISE(ABORT, 'duplicate current memory version');
        END
        """.trimIndent(),
    )
}

internal fun createSingleCurrentMemoryUpdateTrigger(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS reject_reopened_second_current_memory_version
        BEFORE UPDATE OF txToEpochMs, namespaceId, logicalMemoryId ON memory_records
        WHEN NEW.txToEpochMs IS NULL AND EXISTS (
            SELECT 1 FROM memory_records
            WHERE namespaceId = NEW.namespaceId
              AND logicalMemoryId = NEW.logicalMemoryId
              AND txToEpochMs IS NULL
              AND NOT (
                namespaceId = OLD.namespaceId
                AND memoryId = OLD.memoryId
                AND recordVersion = OLD.recordVersion
              )
        )
        BEGIN
            SELECT RAISE(ABORT, 'duplicate current memory version');
        END
        """.trimIndent(),
    )
}

/** 部分索引:Room 无法在 @Entity 声明,由 CALLBACK 托管(同 plan_runs 先例)。 */
internal fun createContactsActiveDeletedIndex(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_contacts_active_deleted` ON `contacts` (`deletedAtEpochMs`) " +
            "WHERE `deletedAtEpochMs` IS NULL",
    )
}

internal fun createPlanRunsSingleActiveIndex(db: SupportSQLiteDatabase) {
    // ADR-006 §3.1: per-(definition) single ACTIVE partial UNIQUE.
    // Fresh installs get this from MIGRATION_8_9; onOpen is a defensive
    // safety net in case the index was dropped or migrated from a
    // schema path that bypassed MIGRATION_8_9.
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_runs_single_active_per_definition` " +
            "ON `plan_runs` (`definitionId`) WHERE `runStatus` = 'ACTIVE'",
    )
}

internal fun createFactSchema(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `facts` (`factId` TEXT NOT NULL, `factType` TEXT NOT NULL, `textContent` TEXT NOT NULL, `structuredDataJson` TEXT, `sourceType` TEXT NOT NULL, `sourceRef` TEXT, `contactId` TEXT, `skillId` TEXT, `confidence` REAL NOT NULL, `sensitivity` TEXT NOT NULL, `status` TEXT NOT NULL, `ttlDays` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`factId`))",
    )
    listOf(
        "factType",
        "sourceType",
        "sourceRef",
        "contactId",
        "skillId",
        "expiresAtEpochMs",
        "status",
    ).forEach {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_$it` ON `facts` (`$it`)")
    }
    // The virtual table is intentionally created by CALLBACK after Room's strict
    // managed-schema validation; Room 2.6 treats unmanaged virtual tables as unexpected.
}

internal fun createFactFts(db: SupportSQLiteDatabase) {
    // Android's framework SQLite build varies by OS/vendor. Probe the actual module
    // instead of relying on compile-option functions, which are themselves optional.
    runCatching {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS fact_fts USING fts5(factId UNINDEXED, textContent, factType, sourceType)",
        )
    }.getOrElse {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS fact_fts USING fts4(factId, textContent, factType, sourceType)",
        )
    }
}

internal fun createContactSearchFts(db: SupportSQLiteDatabase) {
    val concat =
        "new.displayName || ' ' || new.normalizedName || ' ' || COALESCE(new.phone,'') || ' ' || COALESCE(new.email,'') || ' ' || COALESCE(new.company,'') || ' ' || new.aliasesJson || ' ' || new.tagsJson || ' ' || COALESCE(new.note,'')"
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS contact_fts_ai AFTER INSERT ON contacts BEGIN
            INSERT INTO contact_search_fts(contactId, content) VALUES (new.contactId, $concat);
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS contact_fts_ad AFTER DELETE ON contacts BEGIN
            DELETE FROM contact_search_fts WHERE contactId = old.contactId;
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS contact_fts_au AFTER UPDATE ON contacts BEGIN
            DELETE FROM contact_search_fts WHERE contactId = old.contactId;
            INSERT INTO contact_search_fts(contactId, content) VALUES (new.contactId, $concat);
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO contact_search_fts(contactId, content)
        SELECT contactId, displayName || ' ' || normalizedName || ' ' || COALESCE(phone, '')
            || ' ' || COALESCE(email, '') || ' ' || COALESCE(company, '')
            || ' ' || aliasesJson || ' ' || tagsJson || ' ' || COALESCE(note, '')
        FROM contacts WHERE deletedAtEpochMs IS NULL
            AND contactId NOT IN (SELECT contactId FROM contact_search_fts)
        """.trimIndent(),
    )
}
