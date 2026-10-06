package com.attendance.desk.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timetable_slots",
    indices = [Index(value = ["day", "position"])]
)
data class TimetableSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,        // MONDAY ... SATURDAY
    val position: Int,      // 0..8 ordering
    val time: String,       // "9.15 - 10.15"
    val subjectRaw: String  // e.g. "CO1-END(TS) L3\nCO2-OSY..." or "BREAK" or ""
)
