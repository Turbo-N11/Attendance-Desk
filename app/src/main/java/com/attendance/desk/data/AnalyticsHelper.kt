package com.attendance.desk.data

import com.attendance.desk.AttendanceRecord

data class StudentStat(
    val roll: String,
    val present: Int,
    val total: Int,
    val percent: Double,
    val absentRecords: List<AttendanceRecord> = emptyList()
)

object AnalyticsHelper {
    fun computeStats(
        rolls: List<String>,
        history: List<AttendanceRecord>,
        subjectFilter: String? = null, // null = all
        typeFilter: String? = null // null = all, else Lecture/Practical
    ): List<StudentStat> {
        val filtered = history.filter { rec ->
            (subjectFilter == null || rec.subject.equals(subjectFilter, ignoreCase = true)) &&
            (typeFilter == null || rec.type == typeFilter)
        }
        val total = filtered.size
        if (total == 0) return rolls.map { StudentStat(it, 0, 0, 0.0) }
        // For each roll, count how many records they were absent in
        val absentCount = mutableMapOf<String, Int>()
        val absentMap = mutableMapOf<String, MutableList<AttendanceRecord>>()
        for (rec in filtered) {
            for (roll in rec.absent) {
                absentCount[roll] = (absentCount[roll] ?: 0) + 1
                absentMap.getOrPut(roll) { mutableListOf() }.add(rec)
            }
        }
        return rolls.map { roll ->
            val absent = absentCount[roll] ?: 0
            val present = total - absent
            val pct = if (total > 0) present * 100.0 / total else 0.0
            StudentStat(roll, present, total, pct, absentMap[roll] ?: emptyList())
        }.sortedByDescending { it.percent }
    }

    fun defaulterList(stats: List<StudentStat>, threshold: Double = 75.0): List<StudentStat> =
        stats.filter { it.total > 0 && it.percent < threshold }.sortedBy { it.percent }

    fun subjectSummary(history: List<AttendanceRecord>): Map<String, Pair<Int, Int>> {
        // subject -> (lectureCount, practicalCount)
        val map = mutableMapOf<String, Pair<Int, Int>>()
        for (rec in history) {
            val key = rec.subject.uppercase()
            val cur = map[key] ?: (0 to 0)
            if (rec.type == "Lecture") map[key] = (cur.first + 1) to cur.second
            else map[key] = cur.first to (cur.second + 1)
        }
        return map
    }
}
