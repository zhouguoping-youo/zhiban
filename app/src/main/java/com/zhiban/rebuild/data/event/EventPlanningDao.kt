package com.zhiban.rebuild.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventPlanningDao {
    @Query("SELECT * FROM event_plans ORDER BY proposedStartAtEpochMs, updatedAtEpochMs DESC")
    fun observePlans(): Flow<List<EventPlanEntity>>

    @Query("SELECT * FROM event_plans WHERE planId = :planId")
    fun observePlan(planId: String): Flow<EventPlanEntity?>

    @Query("SELECT * FROM event_plans WHERE planId = :planId")
    suspend fun findPlan(planId: String): EventPlanEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlan(plan: EventPlanEntity)

    @Update
    suspend fun updatePlan(plan: EventPlanEntity): Int

    @Query("SELECT * FROM event_plan_participants ORDER BY updatedAtEpochMs, contactId")
    fun observeAllParticipants(): Flow<List<EventPlanParticipantEntity>>

    @Query("SELECT * FROM event_plan_participants WHERE planId = :planId ORDER BY updatedAtEpochMs, contactId")
    fun observeParticipants(planId: String): Flow<List<EventPlanParticipantEntity>>

    @Query("SELECT * FROM event_plan_participants WHERE planId = :planId ORDER BY updatedAtEpochMs, contactId")
    suspend fun participantsForPlan(planId: String): List<EventPlanParticipantEntity>

    @Query("SELECT * FROM event_plan_participants WHERE planId = :planId AND contactId = :contactId")
    suspend fun findParticipant(planId: String, contactId: String): EventPlanParticipantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipant(participant: EventPlanParticipantEntity)

    @Query("DELETE FROM event_plan_participants WHERE planId = :planId AND contactId = :contactId")
    suspend fun removeParticipant(planId: String, contactId: String): Int
}
