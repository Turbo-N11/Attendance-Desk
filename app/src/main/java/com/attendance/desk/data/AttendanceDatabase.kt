package com.attendance.desk.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [StudentEntity::class, SubjectEntity::class, AttendanceRecordEntity::class, AttendanceAbsentEntity::class, TimetableSlotEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun attendanceDao(): AttendanceDao
    abstract fun timetableDao(): TimetableDao

    companion object {
        @Volatile private var INSTANCE: AttendanceDatabase? = null

        fun getInstance(context: Context): AttendanceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AttendanceDatabase::class.java,
                    "attendance_desk.db"
                )
                    .addCallback(SeedCallback(context))
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class SeedCallback(private val context: Context) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Seed on background thread after DB creation
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = database.attendanceDao()
                        dao.insertStudents(SeedData.defaultStudents())
                        dao.insertSubjects(SeedData.defaultSubjects())
                        val tDao = database.timetableDao()
                        tDao.insertAll(SeedData.defaultTimetableSlots())
                    }
                }
            }
        }
    }
}

// ---- Helpers to migrate from old SharedPreferences Gson blob (one-time) ----
suspend fun migrateFromPrefsIfNeeded(context: Context, dao: AttendanceDao) {
    val prefs = context.getSharedPreferences("attendance_prefs", Context.MODE_PRIVATE)
    val gson = com.google.gson.Gson()

    // Students
    val existingStudents = dao.getStudents()
    if (existingStudents.isEmpty()) {
        val rollsJson = prefs.getString("rolls", null)
        if (rollsJson != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<MutableList<String>>() {}.type
                val rolls: MutableList<String> = gson.fromJson(rollsJson, type)
                val students = rolls.map { StudentEntity(it) }
                dao.insertStudents(students)
            } catch (_: Exception) {
                dao.insertStudents(SeedData.defaultStudents())
            }
        } else {
            dao.insertStudents(SeedData.defaultStudents())
        }
    }

    // Subjects
    val existingSubjects = dao.getSubjects()
    if (existingSubjects.isEmpty()) {
        val subjJson = prefs.getString("subjects", null)
        if (subjJson != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<MutableList<String>>() {}.type
                val subs: MutableList<String> = gson.fromJson(subjJson, type)
                val filtered = subs.filter { it in setOf("OSY","DAN","STE","ENDS","END") }
                    .map { if (it=="END") "ENDS" else it }
                    .distinct()
                if (filtered.isNotEmpty()) {
                    dao.insertSubjects(filtered.map { SubjectEntity(it) })
                } else {
                    dao.insertSubjects(SeedData.defaultSubjects())
                }
            } catch (_: Exception) {
                dao.insertSubjects(SeedData.defaultSubjects())
            }
        } else {
            dao.insertSubjects(SeedData.defaultSubjects())
        }
    }

    // History -> records + absents
    val existingRecords = dao.getRecords()
    if (existingRecords.isEmpty()) {
        val historyJson = prefs.getString("history", null)
        if (historyJson != null) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<MutableList<com.attendance.desk.AttendanceRecord>>() {}.type
                val history: MutableList<com.attendance.desk.AttendanceRecord> = gson.fromJson(historyJson, type) ?: mutableListOf()
                for (rec in history) {
                    val entity = AttendanceRecordEntity(
                        id = rec.id,
                        date = rec.date,
                        time = rec.time,
                        subjectCode = if (rec.subject=="END") "ENDS" else rec.subject,
                        type = rec.type,
                        coGroup = rec.coGroup,
                        header = rec.header,
                        timestamp = rec.timestamp
                    )
                    val absents = rec.absent.map { AttendanceAbsentEntity(recordId = rec.id, roll = it) }
                    dao.insertRecordWithAbsents(entity, absents)
                }
            } catch (_: Exception) { }
        }
    }
}

suspend fun migrateTimetableFromPrefsIfNeeded(context: Context, tDao: TimetableDao) {
    val existing = tDao.getAll()
    if (existing.isNotEmpty()) return
    val prefs = context.getSharedPreferences("attendance_prefs", Context.MODE_PRIVATE)
    val gson = com.google.gson.Gson()
    val ttJson = prefs.getString("tt", null)
    if (ttJson != null) {
        try {
            val type = object : com.google.gson.reflect.TypeToken<MutableList<com.attendance.desk.DaySchedule>>() {}.type
            val list: MutableList<com.attendance.desk.DaySchedule> = gson.fromJson(ttJson, type) ?: mutableListOf()
            val slots = mutableListOf<TimetableSlotEntity>()
            for (daySched in list) {
                daySched.slots.forEachIndexed { idx, slot ->
                    slots.add(TimetableSlotEntity(day = daySched.day, position = idx, time = slot.time, subjectRaw = slot.subject))
                }
            }
            if (slots.isNotEmpty()) {
                tDao.insertAll(slots)
                return
            }
        } catch (_: Exception) {}
    }
    // Fallback seed
    tDao.insertAll(SeedData.defaultTimetableSlots())
}
