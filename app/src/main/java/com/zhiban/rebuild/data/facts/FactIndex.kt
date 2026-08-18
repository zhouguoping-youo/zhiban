package com.zhiban.rebuild.data.facts

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(
    tableName = "facts",
    indices = [
        Index("factType"), Index("sourceType"), Index("sourceRef"),
        Index("contactId"), Index("skillId"), Index("expiresAtEpochMs"), Index("status"),
    ],
)
@Serializable
data class FactEntity(
    @PrimaryKey val factId: String,
    val factType: String,
    val textContent: String,
    val structuredDataJson: String?,
    val sourceType: String,
    val sourceRef: String?,
    val contactId: String?,
    val skillId: String?,
    val confidence: Double,
    val sensitivity: String,
    val status: String,
    val ttlDays: Int,
    val expiresAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "embedding_vectors",
    foreignKeys = [
        ForeignKey(
            entity = FactEntity::class,
            parentColumns = ["factId"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            "factId",
        ), Index(value = ["providerId", "modelId"]), Index(value = ["factId", "providerId", "modelId"], unique = true),
    ],
)
data class EmbeddingVectorEntity(
    @PrimaryKey val embeddingId: String,
    val factId: String,
    val providerId: String,
    val modelId: String,
    val dimensions: Int,
    val vectorBlob: ByteArray,
    val generatedAtEpochMs: Long,
    val modelVersion: String?,
)

@Dao
internal interface FactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FactEntity)

    @Query("SELECT * FROM facts WHERE factId = :factId")
    suspend fun find(factId: String): FactEntity?

    @Query("DELETE FROM facts WHERE factId = :factId")
    suspend fun delete(factId: String): Int

    @Query("SELECT * FROM facts WHERE factId IN (:factIds)")
    suspend fun findByIds(factIds: List<String>): List<FactEntity>

    @Query(
        """SELECT * FROM facts WHERE status = 'ACTIVE'
        AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)
        AND (contactId IS NULL OR NOT EXISTS (
            SELECT 1 FROM contacts contact
            WHERE contact.contactId = COALESCE(
                (SELECT canonicalContactId FROM contact_merge_links
                 WHERE sourceContactId = facts.contactId AND undoneAtEpochMs IS NULL),
                facts.contactId
            ) AND contact.deletedAtEpochMs IS NOT NULL
        ))
        ORDER BY updatedAtEpochMs DESC LIMIT :limit""",
    )
    suspend fun recent(now: Long, limit: Int): List<FactEntity>

    @Query(
        """SELECT * FROM facts WHERE (
        contactId = :contactId OR contactId IN (
            SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
        )
    ) AND status = 'ACTIVE' AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)
    ORDER BY updatedAtEpochMs DESC""",
    )
    fun observeByContact(contactId: String, now: Long): Flow<List<FactEntity>>

    @Query(
        """SELECT * FROM facts f WHERE f.status='ACTIVE' AND f.sensitivity!='SENSITIVE'
        AND (f.expiresAtEpochMs IS NULL OR f.expiresAtEpochMs>:now)
        AND (f.contactId IS NULL OR NOT EXISTS (
            SELECT 1 FROM contacts contact
            WHERE contact.contactId = COALESCE(
                (SELECT canonicalContactId FROM contact_merge_links
                 WHERE sourceContactId = f.contactId AND undoneAtEpochMs IS NULL),
                f.contactId
            ) AND contact.deletedAtEpochMs IS NOT NULL
        ))
        AND NOT EXISTS (SELECT 1 FROM embedding_vectors e WHERE e.factId=f.factId AND e.providerId=:providerId AND e.modelId=:modelId AND e.dimensions=:dimensions)
        ORDER BY f.updatedAtEpochMs LIMIT :limit""",
    )
    suspend fun missingEmbeddings(now: Long, providerId: String, modelId: String, dimensions: Int, limit: Int): List<FactEntity>

    @Query(
        """SELECT COUNT(*) FROM facts f WHERE f.status='ACTIVE' AND f.sensitivity!='SENSITIVE'
        AND (f.expiresAtEpochMs IS NULL OR f.expiresAtEpochMs>:now)
        AND (f.contactId IS NULL OR NOT EXISTS (
            SELECT 1 FROM contacts contact
            WHERE contact.contactId = COALESCE(
                (SELECT canonicalContactId FROM contact_merge_links
                 WHERE sourceContactId = f.contactId AND undoneAtEpochMs IS NULL),
                f.contactId
            ) AND contact.deletedAtEpochMs IS NOT NULL
        ))
        AND NOT EXISTS (SELECT 1 FROM embedding_vectors e WHERE e.factId=f.factId AND e.providerId=:providerId AND e.modelId=:modelId AND e.dimensions=:dimensions)""",
    )
    suspend fun missingEmbeddingCount(now: Long, providerId: String, modelId: String, dimensions: Int): Int

    @Query(
        "SELECT factId FROM facts WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs<=:now ORDER BY factId LIMIT :limit",
    )
    suspend fun expiredFactIds(now: Long, limit: Int): List<String>

    @Query("SELECT * FROM facts WHERE contactId IN (:contactIds) AND status = 'ACTIVE'")
    suspend fun activeForContacts(contactIds: List<String>): List<FactEntity>
}

@Dao
internal interface EmbeddingVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EmbeddingVectorEntity)

    @Query(
        """SELECT e.* FROM embedding_vectors e INNER JOIN facts f ON f.factId=e.factId
        WHERE e.providerId=:providerId AND e.modelId=:modelId AND e.dimensions=:dimensions
        AND f.status='ACTIVE' AND (f.expiresAtEpochMs IS NULL OR f.expiresAtEpochMs>:now)
        AND (f.contactId IS NULL OR NOT EXISTS (
            SELECT 1 FROM contacts contact
            WHERE contact.contactId = COALESCE(
                (SELECT canonicalContactId FROM contact_merge_links
                 WHERE sourceContactId = f.contactId AND undoneAtEpochMs IS NULL),
                f.contactId
            ) AND contact.deletedAtEpochMs IS NOT NULL
        ))
        ORDER BY f.updatedAtEpochMs DESC LIMIT :limit""",
    )
    suspend fun active(providerId: String, modelId: String, dimensions: Int, now: Long, limit: Int): List<EmbeddingVectorEntity>

    @Query("DELETE FROM embedding_vectors WHERE providerId=:providerId AND modelId=:modelId")
    suspend fun deleteSpace(providerId: String, modelId: String): Int

    @Query("DELETE FROM embedding_vectors WHERE factId=:factId")
    suspend fun deleteByFact(factId: String): Int
}

/** Transactional boundary for the canonical Fact row and its local FTS5 projection. */
internal class FactIndex(private val database: AgentDatabase) {
    suspend fun upsert(fact: FactEntity) = database.withTransaction { upsertInTransaction(fact) }

    /** 批量 upsert 同事务(P2:过去逐条一事务,revoke 一批事实时事务数=事实数)。 */
    suspend fun upsertAll(facts: List<FactEntity>) = database.withTransaction {
        for (fact in facts) upsertInTransaction(fact)
    }

    private suspend fun upsertInTransaction(fact: FactEntity) {
        val previous = database.factDao().find(fact.factId)
        if (previous != null && (previous.textContent != fact.textContent || previous.status != fact.status)) {
            database.embeddingVectorDao().deleteByFact(fact.factId)
        }
        database.factDao().upsert(fact)
        val sql = database.openHelper.writableDatabase
        ensureFts()
        sql.execSQL("DELETE FROM fact_fts WHERE factId = ?", arrayOf(fact.factId))
        if (fact.status == "ACTIVE") {
            sql.execSQL(
                "INSERT INTO fact_fts(factId,textContent,factType,sourceType) VALUES(?,?,?,?)",
                arrayOf(fact.factId, lexemes(fact.textContent), fact.factType, fact.sourceType),
            )
        }
    }

    suspend fun delete(factId: String): Boolean = database.withTransaction { deleteInTransaction(factId) }

    /** 批量删除同事务(P2)。 */
    suspend fun deleteAll(factIds: List<String>): Int = database.withTransaction {
        factIds.count { deleteInTransaction(it) }
    }

    private suspend fun deleteInTransaction(factId: String): Boolean {
        ensureFts()
        database.openHelper.writableDatabase.execSQL("DELETE FROM fact_fts WHERE factId = ?", arrayOf(factId))
        return database.factDao().delete(factId) == 1
    }

    /** Uses the canonical boundary so expired rows cannot leave FTS/vector projections behind. */
    suspend fun deleteExpired(now: Long, limit: Int = 128): Int {
        require(limit in 1..1_000)
        val ids = database.factDao().expiredFactIds(now, limit)
        deleteAll(ids)
        return ids.size
    }

    suspend fun revokeByContactIds(contactIds: List<String>, nowEpochMs: Long): Int {
        if (contactIds.isEmpty()) return 0
        val active = database.factDao().activeForContacts(contactIds)
        upsertAll(active.map { it.copy(status = "REVOKED", updatedAtEpochMs = nowEpochMs) })
        return active.size
    }

    /** Recreates the derived FTS projection from canonical Fact rows without changing source data. */
    suspend fun rebuild(): Int = database.withTransaction {
        ensureFts()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("DELETE FROM fact_fts")
        var rebuilt = 0
        sql.query(
            "SELECT factId,textContent,factType,sourceType FROM facts WHERE status='ACTIVE' ORDER BY factId",
        ).use { rows ->
            while (rows.moveToNext()) {
                sql.execSQL(
                    "INSERT INTO fact_fts(factId,textContent,factType,sourceType) VALUES(?,?,?,?)",
                    arrayOf(
                        rows.getString(0),
                        lexemes(rows.getString(1)),
                        rows.getString(2),
                        rows.getString(3),
                    ),
                )
                rebuilt++
            }
        }
        rebuilt
    }

    /** Repairs missing or surplus FTS rows; returns true only when a rebuild was necessary. */
    suspend fun repairIfInconsistent(): Boolean {
        ensureFts()
        val sql = database.openHelper.readableDatabase
        val canonicalCount = sql.query("SELECT COUNT(*) FROM facts WHERE status='ACTIVE'").use {
            it.moveToFirst()
            it.getInt(0)
        }
        val projectionCount = sql.query("SELECT COUNT(*) FROM fact_fts").use {
            it.moveToFirst()
            it.getInt(0)
        }
        if (canonicalCount == projectionCount) return false
        rebuild()
        return true
    }

    fun search(query: String, now: Long, limit: Int): List<FactEntity> {
        require(limit in 1..100)
        ensureFts()
        val normalized = lexemes(query)
        if (normalized.isBlank()) return emptyList()
        val cursor = database.openHelper.readableDatabase.query(
            """SELECT f.* FROM facts f
               WHERE f.factId IN (SELECT factId FROM fact_fts WHERE fact_fts MATCH ?)
                 AND f.status='ACTIVE'
                 AND (f.expiresAtEpochMs IS NULL OR f.expiresAtEpochMs>?)
                 AND (f.contactId IS NULL OR NOT EXISTS (
                     SELECT 1 FROM contacts contact
                     WHERE contact.contactId = COALESCE(
                         (SELECT canonicalContactId FROM contact_merge_links
                          WHERE sourceContactId = f.contactId AND undoneAtEpochMs IS NULL),
                         f.contactId
                     ) AND contact.deletedAtEpochMs IS NOT NULL
                 ))
               ORDER BY f.updatedAtEpochMs DESC LIMIT ?
            """.trimIndent(),
            arrayOf<Any?>(normalized, now, limit),
        )
        return cursor.use(::readFacts)
    }

    private fun readFacts(rows: Cursor): List<FactEntity> = buildList {
        fun text(name: String) = rows.getString(rows.getColumnIndexOrThrow(name))
        fun nullableText(name: String) = rows.getColumnIndexOrThrow(name).let { if (rows.isNull(it)) null else rows.getString(it) }
        fun nullableLong(name: String) = rows.getColumnIndexOrThrow(name).let { if (rows.isNull(it)) null else rows.getLong(it) }
        while (rows.moveToNext()) {
            add(
                FactEntity(
                    text("factId"), text("factType"), text("textContent"), nullableText("structuredDataJson"),
                    text("sourceType"), nullableText("sourceRef"), nullableText("contactId"), nullableText("skillId"),
                    rows.getDouble(rows.getColumnIndexOrThrow("confidence")), text("sensitivity"), text("status"),
                    rows.getInt(rows.getColumnIndexOrThrow("ttlDays")), nullableLong("expiresAtEpochMs"),
                    rows.getLong(rows.getColumnIndexOrThrow("createdAtEpochMs")),
                    rows.getLong(rows.getColumnIndexOrThrow("updatedAtEpochMs")),
                ),
            )
        }
    }

    private fun lexemes(value: String): String = buildList {
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) add(latin.toString().lowercase()).also { latin.clear() }
        }
        value.codePoints().forEach { codePoint ->
            when {
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN -> {
                    flushLatin()
                    add(String(Character.toChars(codePoint)))
                }

                Character.isLetterOrDigit(codePoint) -> latin.appendCodePoint(codePoint)

                else -> flushLatin()
            }
        }
        flushLatin()
    }.joinToString(" ")

    private fun ensureFts() {
        val db = database.openHelper.writableDatabase
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
}
