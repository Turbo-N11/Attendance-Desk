package com.attendance.desk.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey val roll: String,
    val isActive: Boolean = true
)
