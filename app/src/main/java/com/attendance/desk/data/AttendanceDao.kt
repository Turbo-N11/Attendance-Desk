package com.attendance.desk.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    // ---- Students ----
    @Query("SELECT * FROM students ORDER BY CAST(roll AS INTEGER) ASC")
    fun getStudentsFlow(): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students ORDER BY CAST(roll AS INTEGER) ASC")
    suspend fun getStudents(): List<StudentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudents(students: List<StudentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: StudentEntity)

    @Query("DELETE FROM students WHERE roll = :roll")
    suspend fun deleteStudentByRoll(roll: String)

    @Query("DELETE FROM students")
    suspend fun clearStudents()

    // ---- Subjects ----
    @Query("SELECT * FROM subjects ORDER BY code ASC")
    fun getSubjectsFlow(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects ORDER BY code ASC")
    suspend fun getSubjects(): List<SubjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubjects(subjects: List<SubjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubject(subject: SubjectEntity)

    @Query("DELETE FROM subjects WHERE code = :code")
    suspend fun deleteSubjectByCode(code: String)

    @Query("DELETE FROM subjects")
    suspend fun clearSubjects()

    // ---- Records ----
    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    fun getRecordsFlow(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    suspend fun getRecords(): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_records WHERE subjectCode = :code ORDER BY timestamp DESC")
    suspend fun getRecordsBySubject(code: String): List<AttendanceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: AttendanceRecordEntity)

    @Query("DELETE FROM attendance_records WHERE id = :id")
    suspend fun deleteRecordById(id: String)

    @Query("DELETE FROM attendance_records")
    suspend fun clearRecords()

    // ---- Absents (child table) ----
    @Query("SELECT * FROM attendance_absents WHERE recordId = :recordId")
    suspend fun getAbsentsForRecord(recordId: String): List<AttendanceAbsentEntity>

    @Query("SELECT * FROM attendance_absents")
    suspend fun getAllAbsents(): List<AttendanceAbsentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAbsents(absents: List<AttendanceAbsentEntity>)

    @Query("DELETE FROM attendance_absents WHERE recordId = :recordId")
    suspend fun deleteAbsentsForRecord(recordId: String)

    @Query("DELETE FROM attendance_absents")
    suspend fun clearAbsents()

    // ---- Transactions ----
    @Transaction
    suspend fun insertRecordWithAbsents(
        record: AttendanceRecordEntity,
        absents: List<AttendanceAbsentEntity>
    ) {
        insertRecord(record)
        if (absents.isNotEmpty()) insertAbsents(absents)
    }

    @Transaction
    suspend fun deleteRecordWithAbsents(recordId: String) {
        deleteAbsentsForRecord(recordId)
        deleteRecordById(recordId)
    }

    @Transaction
    suspend fun clearAllRecords() {
        clearAbsents()
        clearRecords()
    }
}
