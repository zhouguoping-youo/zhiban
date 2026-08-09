package com.zhiban.rebuild.data.calllog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "call_records",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["linkedContactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["source", "providerRowId"], unique = true),
        Index("normalizedNumber"),
        Index("startedAtEpochMs"),
        Index("linkedContactId"),
        Index("notePromptState"),
    ],
)
data class CallRecordEntity(
    @PrimaryKey val callRecordId: String,
    val source: String,
    val providerRowId: Long,
    val rawNumber: String?,
    val normalizedNumber: String?,
    val numberPresentation: Int,
    val systemType: Int,
    val direction: String,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
    val lastModifiedEpochMs: Long,
    val phoneAccountId: String?,
    val phoneAccountComponentName: String?,
    val linkedContactId: String?,
    val linkState: String,
    val linkSource: String?,
    val sourceStatus: String,
    val notePromptState: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "call_notes",
    foreignKeys = [
        ForeignKey(
            entity = CallRecordEntity::class,
            parentColumns = ["callRecordId"],
            childColumns = ["callRecordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("callRecordId"), Index("createdAtEpochMs")],
)
data class CallNoteEntity(
    @PrimaryKey val callNoteId: String,
    val callRecordId: String,
    val noteText: String,
    val source: String,
    val asrProvider: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Dao
interface CallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCall(value: CallRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(value: CallNoteEntity)

    @Query("SELECT * FROM call_records WHERE source = :source AND providerRowId = :providerRowId LIMIT 1")
    suspend fun findBySourceRow(source: String, providerRowId: Long): CallRecordEntity?

    @Query("SELECT * FROM call_records WHERE callRecordId = :callRecordId")
    suspend fun findById(callRecordId: String): CallRecordEntity?

    @Query("SELECT * FROM call_records WHERE startedAtEpochMs >= :sinceEpochMs ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun findLatestSince(sinceEpochMs: Long): CallRecordEntity?

    @Query("SELECT * FROM call_records ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<CallRecordEntity>>

    @Query(
        """SELECT * FROM call_records
        WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = call_records.linkedContactId AND undoneAtEpochMs IS NULL),
            linkedContactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        )
        ORDER BY startedAtEpochMs DESC LIMIT :limit""",
    )
    fun observeForContact(contactId: String, limit: Int = 100): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_records WHERE notePromptState = 'PENDING' ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observePendingNotes(limit: Int = 20): Flow<List<CallRecordEntity>>

    @Query(
        "UPDATE call_records SET notePromptState = :state, updatedAtEpochMs = :nowEpochMs WHERE callRecordId = :callRecordId",
    )
    suspend fun updateNotePromptState(callRecordId: String, state: String, nowEpochMs: Long): Int

    @Query("DELETE FROM call_notes WHERE callNoteId = :callNoteId")
    suspend fun deleteNote(callNoteId: String): Int

    @Query("SELECT * FROM call_notes WHERE callRecordId = :callRecordId ORDER BY createdAtEpochMs DESC")
    fun observeNotes(callRecordId: String): Flow<List<CallNoteEntity>>
}
