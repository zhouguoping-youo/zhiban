package com.zhiban.rebuild.data.event

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.contact.ContactEntity

object EventPlanStatus {
    const val DRAFT = "DRAFT"
    const val COORDINATING = "COORDINATING"
    const val CONFIRMED = "CONFIRMED"
    const val COMPLETED = "COMPLETED"

    val ALL = setOf(DRAFT, COORDINATING, CONFIRMED, COMPLETED)
}

object EventResponseStatus {
    const val PENDING = "PENDING"
    const val GOING = "GOING"
    const val MAYBE = "MAYBE"
    const val DECLINED = "DECLINED"

    val ALL = setOf(PENDING, GOING, MAYBE, DECLINED)
}

@Entity(
    tableName = "event_plans",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("status"), Index("proposedStartAtEpochMs"), Index("scheduleId")],
)
data class EventPlanEntity(
    @PrimaryKey val planId: String,
    val title: String,
    val proposedStartAtEpochMs: Long,
    val durationMinutes: Int,
    val location: String?,
    val note: String?,
    val status: String,
    val scheduleId: String?,
    val sourceType: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "event_plan_participants",
    primaryKeys = ["planId", "contactId"],
    foreignKeys = [
        ForeignKey(
            entity = EventPlanEntity::class,
            parentColumns = ["planId"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("contactId"), Index("responseStatus")],
)
data class EventPlanParticipantEntity(
    val planId: String,
    val contactId: String,
    val responseStatus: String,
    val responseSource: String,
    val updatedAtEpochMs: Long,
)
