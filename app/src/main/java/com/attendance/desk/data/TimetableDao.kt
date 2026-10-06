package com.attendance.desk.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetable_slots ORDER BY CASE day WHEN 'MONDAY' THEN 0 WHEN 'TUESDAY' THEN 1 WHEN 'WEDNESDAY' THEN 2 WHEN 'THURSDAY' THEN 3 WHEN 'FRIDAY' THEN 4 WHEN 'SATURDAY' THEN 5 ELSE 6 END, position ASC")
    fun getAllFlow(): Flow<List<TimetableSlotEntity>>

    @Query("SELECT * FROM timetable_slots ORDER BY CASE day WHEN 'MONDAY' THEN 0 WHEN 'TUESDAY' THEN 1 WHEN 'WEDNESDAY' THEN 2 WHEN 'THURSDAY' THEN 3 WHEN 'FRIDAY' THEN 4 WHEN 'SATURDAY' THEN 5 ELSE 6 END, position ASC")
    suspend fun getAll(): List<TimetableSlotEntity>

    @Query("SELECT * FROM timetable_slots WHERE day = :day ORDER BY position ASC")
    suspend fun getByDay(day: String): List<TimetableSlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(slots: List<TimetableSlotEntity>)

    @Query("DELETE FROM timetable_slots")
    suspend fun clearAll()

    @Query("DELETE FROM timetable_slots WHERE day = :day")
    suspend fun clearDay(day: String)
}
