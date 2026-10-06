package com.attendance.desk.data

object SeedData {
    // Students: 1-63, 81-87  => 70 rolls
    fun defaultStudents(): List<StudentEntity> {
        val list = mutableListOf<StudentEntity>()
        for (i in 1..63) list.add(StudentEntity(roll = i.toString()))
        for (i in 81..87) list.add(StudentEntity(roll = i.toString()))
        return list
    }

    // Subjects: OSY, DAN, STE, ENDS (as requested)
    fun defaultSubjects(): List<SubjectEntity> = listOf(
        SubjectEntity("OSY"),
        SubjectEntity("DAN"),
        SubjectEntity("STE"),
        SubjectEntity("ENDS")
    )

    // Timetable: mirrors original defaultTimetable() but as flat slots for Room
    fun defaultTimetableSlots(): List<TimetableSlotEntity> {
        val raw = listOf(
            "MONDAY" to listOf(
                "9.15 - 10.15" to "CO1-END(TS) L3\nCO2-OSY(KJ) L1\nCO3-STE(AS) L2",
                "10.15 - 11.15" to "CO1-END(TS) L3\nCO2-OSY(KJ) L1\nCO3-STE(AS) L2",
                "11.15 - 12.15" to "OSY (KJ)\n202",
                "12.15 - 1.15" to "OSY (KJ)\n202",
                "1.15 - 2.00" to "BREAK",
                "2.00 - 3.00" to "ENDS (TS)\nLRDUC",
                "3.00 - 4.00" to "SPI (ZM) L3",
                "4.00 - 5.00" to "",
                "5.00 - 6.00" to "",
            ),
            "TUESDAY" to listOf(
                "9.15 - 10.15" to "CO1-STE(AS) L3\nCO2-FREE\nCO3-FREE",
                "10.15 - 11.15" to "CO1-STE(AS) L3",
                "11.15 - 12.15" to "CO1-STE(AS)L2\nCO2-DAN(ZM)L3\nCO3-OSY(KJ)L1",
                "12.15 - 1.15" to "",
                "1.15 - 2.00" to "BREAK",
                "2.00 - 3.00" to "STE (AS)\nLRDUC",
                "3.00 - 4.00" to "STE (AS)\nLRDUC",
                "4.00 - 5.00" to "",
                "5.00 - 6.00" to "",
            ),
            "WEDNESDAY" to listOf(
                "9.15 - 10.15" to "OSY (KJ)\nLRDUC",
                "10.15 - 11.15" to "STE (AS)\nLRDUC",
                "11.15 - 12.15" to "DAN (ZM)\nLRDUC",
                "12.15 - 1.15" to "DAN (ZM)\nLRDUC",
                "1.15 - 2.00" to "BREAK",
                "2.00 - 3.00" to "CO1-FREE\nCO2-STE(AS)L2\nCO3-DAN(ZM)L3",
                "3.00 - 4.00" to "CO1-FREE\nCO2-STE(AS)L2\nCO3-DAN(ZM)L3",
                "4.00 - 5.00" to "",
                "5.00 - 6.00" to "",
            ),
            "THURSDAY" to listOf(
                "9.15 - 10.15" to "",
                "10.15 - 11.15" to "STE (AS)\nLRDUC",
                "11.15 - 12.15" to "CO1-OSY(KJ)L1\nCO2-END(TS)L2\nCO3-STE(AS)L3",
                "12.15 - 1.15" to "CO1-OSY(KJ)L1\nCO2-END(TS)L2\nCO3-STE(AS)L3",
                "1.15 - 2.00" to "BREAK",
                "2.00 - 3.00" to "OSY (KJ)\nLRDUC",
                "3.00 - 4.00" to "OSY (KJ)\nLRDUC",
                "4.00 - 5.00" to "",
                "5.00 - 6.00" to "",
            ),
            "FRIDAY" to listOf(
                "9.00 - 10.00" to "",
                "10.00 - 11.00" to "",
                "11.00 - 12.00" to "DAN (ZM)\nLRDUC",
                "12.00 - 1.00" to "DAN (ZM)\nLRDUC",
                "1.00 - 2.00" to "BREAK",
                "2.00 - 3.00" to "CO1-DAN(ZM)L3\nCO2-STE(AS)L2\nCO3-END(TS)L3",
                "3.00 - 4.00" to "CO1-DAN(ZM)L3\nCO2-STE(AS)L2\nCO3-END(TS)L3",
                "4.00 - 5.00" to "",
                "5.00 - 6.00" to "",
            ),
            "SATURDAY" to listOf(
                "9.00 - 10.00" to "",
                "10.00 - 11.00" to "",
                "11.00 - 12.00" to "",
                "12.00 - 1.00" to "",
                "1.00 - 2.00" to "BREAK",
                "2.00 - 3.00" to "",
                "3.00 - 4.00" to "",
                "4.00 - 5.00" to "",
                "5.00 - 6.00" to "",
            )
        )
        val out = mutableListOf<TimetableSlotEntity>()
        for ((day, slots) in raw) {
            slots.forEachIndexed { idx, (time, subj) ->
                out.add(TimetableSlotEntity(day = day, position = idx, time = time, subjectRaw = subj))
            }
        }
        return out
    }
}
