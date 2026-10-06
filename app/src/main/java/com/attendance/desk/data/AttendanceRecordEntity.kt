package com.attendance.desk.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attendance_records",
    indices = [Index(value = ["subjectCode"]), Index(value = ["timestamp"])]
)
data class AttendanceRecordEntity(
    @PrimaryKey val id: String,
    val date: String,          // dd/MM/yyyy
    val time: String,          // 9.15 - 10.15
    val subjectCode: String,   // OSY / DAN / STE / ENDS
    val type: String,          // Lecture / Practical
    val coGroup: String? = null, // CO1/CO2/CO3 when Practical
    val header: String,        // Batch 1 / CO-5K (A)
    val timestamp: Long
)

@Entity(
    tableName = "attendance_absents",
    primaryKeys = ["recordId", "roll"],
    foreignKeys = [
        ForeignKey(
            entity = AttendanceRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recordId"]), Index(value = ["roll"])]
)
data class AttendanceAbsentEntity(
    val recordId: String,
    val roll: String
)
