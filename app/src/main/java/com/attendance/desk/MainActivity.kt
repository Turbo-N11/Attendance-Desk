package com.attendance.desk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.desk.data.AttendanceAbsentEntity
import com.attendance.desk.data.AttendanceDatabase
import com.attendance.desk.data.AttendanceRecordEntity
import com.attendance.desk.data.SeedData
import com.attendance.desk.data.StudentEntity
import com.attendance.desk.data.SubjectEntity
import com.attendance.desk.data.AppTheme
import com.attendance.desk.data.AppThemes
import com.attendance.desk.data.ThemeManager
import com.attendance.desk.data.TimetableSlotEntity
import com.attendance.desk.data.migrateFromPrefsIfNeeded
import com.attendance.desk.data.migrateTimetableFromPrefsIfNeeded
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

// ---------- Dynamic Palette — driven by ThemeManager (see data/AppTheme.kt) ----------
val CreamBg: Color get() = ThemeManager.current.bg
val CreamSurface: Color get() = ThemeManager.current.surface
val CreamCard: Color get() = ThemeManager.current.card
val CreamBorder: Color get() = ThemeManager.current.border
val CreamPrimary: Color get() = ThemeManager.current.primary
val CreamPrimaryDark: Color get() = ThemeManager.current.primaryDark
val CreamSecondary: Color get() = ThemeManager.current.secondary
val CreamAccentLight: Color get() = ThemeManager.current.accentLight
val CreamText: Color get() = ThemeManager.current.text
val CreamTextSec: Color get() = ThemeManager.current.textSec
val CreamMuted: Color get() = ThemeManager.current.muted
val PresentGreen = Color(0xFF2E7D32)
val PresentBg = Color(0xFFE8F5E9)
val PresentBorder = Color(0xFFA5D6A7)
val AbsentRed = Color(0xFFBF360C)
val AbsentBg = Color(0xFFFDF0E6)
val AbsentBorder = Color(0xFFD7A99A)
// Subject accents - distinctive, per-brief
val SubjOSY = Color(0xFF1565C0)
val SubjDAN = Color(0xFF6A1B9A)
val SubjSTE = Color(0xFF2E7D32)
val SubjENDS = Color(0xFFEF6C00)
fun subjectAccent(code: String): Color = when (code.uppercase().trim()) {
    "OSY" -> SubjOSY
    "DAN" -> SubjDAN
    "STE" -> SubjSTE
    "ENDS","END" -> SubjENDS
    else -> CreamPrimary
}
fun subjectForRaw(raw: String): String {
    // Extract subject code from raw like "OSY (KJ)\n202" or "CO1-STE(AS) L3"
    val upper = raw.uppercase()
    return when {
        upper.contains("OSY") -> "OSY"
        upper.contains("DAN") -> "DAN"
        upper.contains("STE") -> "STE"
        upper.contains("END") -> "ENDS"
        upper.contains("SPI") -> "SPI"
        else -> ""
    }
}

// ---------- Models ----------
data class DaySlot(val time: String, var subject: String)
data class DaySchedule(val day: String, val slots: MutableList<DaySlot>)

fun defaultRolls(): MutableList<String> {
    // Room seed: 1-63, 81-87 => 70 students (as per schema)
    return SeedData.defaultStudents().map { it.roll }.toMutableList()
}

fun defaultTimetable(): List<DaySchedule> {
    // Single source via SeedData -> ensures ENDS, etc consistent across prefs fallback and Room seed
    return SeedData.defaultTimetableSlots().toDaySchedules()
}

fun defaultSubjects(): MutableList<String> = SeedData.defaultSubjects().map { it.code }.toMutableList()
val batchOptions = listOf("CO-5K (A)","Batch 1","Batch 2","Batch 3")
val coOptions = listOf("CO1","CO2","CO3")

data class AttendanceRecord(
    val id: String = UUID.randomUUID().toString(),
    val date: String,
    val time: String,
    val subject: String,
    val type: String, // Lecture or Practical
    val coGroup: String? = null, // CO1/CO2/CO3 when Practical
    val header: String,
    val absent: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

// ---------- Converters for Room <-> UI model ----------
// Keep AttendanceRecord as UI model for Excel/WhatsApp; DB uses normalized tables
fun AttendanceRecord.toEntity(): AttendanceRecordEntity = AttendanceRecordEntity(
    id = id,
    date = date,
    time = time,
    subjectCode = if (subject == "END") "ENDS" else subject,
    type = type,
    coGroup = coGroup,
    header = header,
    timestamp = timestamp
)
fun AttendanceRecordEntity.toUi(absent: List<String>): AttendanceRecord = AttendanceRecord(
    id = id,
    date = date,
    time = time,
    subject = subjectCode,
    type = type,
    coGroup = coGroup,
    header = header,
    absent = absent,
    timestamp = timestamp
)

// ---------- Timetable converters ----------
fun List<TimetableSlotEntity>.toDaySchedules(): List<DaySchedule> {
    val grouped = this.groupBy { it.day }
    val order = listOf("MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY")
    return order.mapNotNull { day ->
        val slots = grouped[day]?.sortedBy { it.position } ?: return@mapNotNull null
        DaySchedule(day, slots.map { DaySlot(it.time, it.subjectRaw) }.toMutableList())
    }
}
fun List<DaySchedule>.toSlotEntities(): List<TimetableSlotEntity> {
    val out = mutableListOf<TimetableSlotEntity>()
    for (daySched in this) {
        daySched.slots.forEachIndexed { idx, slot ->
            out.add(TimetableSlotEntity(day = daySched.day, position = idx, time = slot.time, subjectRaw = slot.subject))
        }
    }
    return out
}

// ---------- Prefs Helper ----------
// Now only for one-time migration; all data lives in Room
// Rolls/Subjects/History/TT prefs are deprecated.
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("attendance_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    @Deprecated("Use Room - students table")
    fun saveRolls(list: List<String>) { sp.edit().putString("rolls", gson.toJson(list)).apply() }
    @Deprecated("Use Room")
    fun loadRolls(): MutableList<String> {
        val s = sp.getString("rolls", null) ?: return defaultRolls()
        return try { gson.fromJson(s, object: TypeToken<MutableList<String>>(){}.type) } catch (e: Exception){ defaultRolls() }
    }
    @Deprecated("Use Room - subjects table")
    fun saveSubjects(list: List<String>) { sp.edit().putString("subjects", gson.toJson(list)).apply() }
    @Deprecated("Use Room")
    fun loadSubjects(): MutableList<String> {
        val s = sp.getString("subjects", null) ?: return defaultSubjects()
        return try { gson.fromJson(s, object: TypeToken<MutableList<String>>(){}.type) } catch(e: Exception){ defaultSubjects() }
    }
    fun saveTimetable(list: List<DaySchedule>) { sp.edit().putString("tt", gson.toJson(list)).apply() }
    fun loadTimetable(): MutableList<DaySchedule> {
        val s = sp.getString("tt", null) ?: return defaultTimetable().map{ it.copy(slots=it.slots.toMutableList()) }.toMutableList()
        return try { gson.fromJson(s, object: TypeToken<MutableList<DaySchedule>>(){}.type) } catch(e: Exception){ defaultTimetable().toMutableList()}
    }
    @Deprecated("Use Room - attendance_records + attendance_absents")
    fun saveHistory(list: List<AttendanceRecord>) { sp.edit().putString("history", gson.toJson(list)).apply() }
    @Deprecated("Use Room")
    fun loadHistory(): MutableList<AttendanceRecord> {
        val s = sp.getString("history", null) ?: return mutableListOf()
        return try { gson.fromJson(s, object: TypeToken<MutableList<AttendanceRecord>>(){}.type) ?: mutableListOf() } catch(e: Exception){ mutableListOf() }
    }
    fun saveTheme(id: String) { sp.edit().putString("app_theme", id).apply() }
    fun loadTheme(): String = sp.getString("app_theme", "cream") ?: "cream"
}

// ---------- Helpers ----------
fun formatDate(d: Date, pattern: String="dd/MM/yyyy"): String = SimpleDateFormat(pattern, Locale.getDefault()).format(d)
fun formatDateShort(d: Date): String = SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(d)
fun today(): String = formatDate(Date(), "dd/MM/yyyy")
fun generateMessage(header: String, date: String, time: String, subject: String, absent: String, type: String = "Lecture", coGroup: String? = null): String {
    val typeLine = if(type=="Practical" && coGroup!=null) " ($type - $coGroup)" else if(type=="Practical") " (Practical)" else ""
    return """$header
Date : $date
Time : $time
Lecture : $subject$typeLine
Absent : $absent""".trimIndent()
}
fun isBatchPractical(header: String): Boolean = header.contains("Batch", ignoreCase = true)
fun batchToCo(header: String): String = when {
    header.contains("1") -> "CO1"
    header.contains("2") -> "CO2"
    header.contains("3") -> "CO3"
    else -> "CO1"
}

// ---------- Pie Chart helpers ----------
data class PieSlice(val label: String, val value: Float, val color: Color)
@Composable
fun PieChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    donutHole: Float = 0.52f, // 0 = pie, 0.5+ = donut
    strokeWidth: Float = 2f
) {
    if (slices.isEmpty() || slices.sumOf { it.value.toDouble() } == 0.0) {
        Box(modifier=modifier, contentAlignment=Alignment.Center) {
            Text("No data", fontSize=10.sp, color=CreamMuted)
        }
        return
    }
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val radius = diameter / 2f
        val center = androidx.compose.ui.geometry.Offset(size.width/2f, size.height/2f)
        var startAngle = -90f
        slices.forEach { slice ->
            val sweep = (slice.value / total) * 360f
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = donutHole==0f,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(diameter, diameter)
            )
            // white separator
            if (strokeWidth>0) {
                drawArc(
                    color = Color.White,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = donutHole==0f,
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
            }
            startAngle += sweep
        }
        if (donutHole>0f) {
            drawCircle(color = Color.White, radius = radius * donutHole, center = center)
            // inner border
            drawCircle(color = CreamBorder.copy(alpha=0.6f), radius = radius * donutHole, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width=1f))
        }
    }
}
@Composable
fun PieLegend(slices: List<PieSlice>, total: Float) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        slices.forEach { s ->
            val pct = if (total>0) s.value*100f/total else 0f
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Box(modifier=Modifier.size(10.dp).clip(CircleShape).background(s.color))
                Text(s.label, fontSize=10.sp, color=CreamText, fontWeight=FontWeight.SemiBold, modifier=Modifier.weight(1f, fill=false))
                Text(String.format(Locale.US,"%.0f%%", pct), fontSize=10.sp, color=CreamTextSec, fontWeight=FontWeight.Bold)
                Text("(${s.value.toInt()})", fontSize=9.sp, color=CreamMuted)
            }
        }
    }
}
fun saveExcelToDownloads(ctx: Context, srcFile: java.io.File): java.io.File? {
    return try {
        // 1. Always save to app-specific external files (no permission, always works, shareable via FileProvider)
        val extDir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dest = java.io.File(extDir, srcFile.name)
        srcFile.copyTo(dest, overwrite = true)
        // 2. Try public Downloads via MediaStore on Android Q+ (best-effort, never crash)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val resolver = ctx.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, srcFile.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/AttendanceDesk")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                // fallback already saved to externalFiles, so just log
            }
        }
        dest
    } catch(t: Throwable){
        t.printStackTrace()
        Toast.makeText(ctx, "Download save failed: ${t.message}", Toast.LENGTH_SHORT).show()
        null
    }
}
fun shareExcelFile(ctx: Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share Excel"))
    } catch(t: Throwable){
        t.printStackTrace()
        Toast.makeText(ctx, "Share failed: ${t.message}", Toast.LENGTH_LONG).show()
    }
}
fun generateSTEExcel(ctx: Context, rolls: List<String>, history: List<AttendanceRecord>): java.io.File? {
    // Filter STE only, split into Lecture vs Practical separate registers
    val steRecords = history.filter { it.subject.equals("STE", ignoreCase = true) }.sortedBy { it.timestamp }
    if(steRecords.isEmpty()){
        Toast.makeText(ctx, "No STE records yet — take attendance first", Toast.LENGTH_SHORT).show()
        return null
    }
    // Separate
    val lectures = steRecords.filter { it.type=="Lecture" }
    val practicals = steRecords.filter { it.type=="Practical" }
    return try {
        val wb = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        val headerStyle = wb.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.TAN.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER
            val f = wb.createFont().apply { bold = true; color = org.apache.poi.ss.usermodel.IndexedColors.BROWN.index }
            setFont(f)
            borderBottom = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderTop = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderLeft = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderRight = org.apache.poi.ss.usermodel.BorderStyle.THIN
        }
        val presentStyle = wb.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.LIGHT_GREEN.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
        }
        val absentStyle = wb.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.ROSE.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
            val f = wb.createFont().apply { bold = true; color = org.apache.poi.ss.usermodel.IndexedColors.DARK_RED.index }
            setFont(f)
        }
        val rollStyle = wb.createCellStyle().apply {
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER
        }

        fun buildSheet(name: String, records: List<AttendanceRecord>){
            if(records.isEmpty()) return
            val sheet = wb.createSheet(name)
            // Header row: Roll | each date | Total P | %
            val header = sheet.createRow(0)
            header.createCell(0).apply { setCellValue("Roll No"); cellStyle = headerStyle }
            records.forEachIndexed { idx, rec ->
                val c = header.createCell(idx+1)
                val label = "${rec.date}\n${rec.time}\n${if(rec.type=="Practical") "PR-${rec.coGroup?:""}" else "LE"}"
                c.setCellValue(label)
                c.cellStyle = headerStyle
            }
            val pCol = records.size+1
            val pctCol = records.size+2
            header.createCell(pCol).apply { setCellValue("Present"); cellStyle=headerStyle }
            header.createCell(pctCol).apply { setCellValue("%"); cellStyle=headerStyle }
            header.heightInPoints = 36f

            rolls.forEachIndexed { rIdx, roll ->
                val row = sheet.createRow(rIdx+1)
                row.createCell(0).apply { setCellValue(roll); cellStyle = rollStyle }
                var presentCount = 0
                records.forEachIndexed { cIdx, rec ->
                    val cell = row.createCell(cIdx+1)
                    val isAbsent = rec.absent.contains(roll)
                    if(isAbsent){
                        cell.setCellValue("A")
                        cell.cellStyle = absentStyle
                    } else {
                        cell.setCellValue("P")
                        cell.cellStyle = presentStyle
                        presentCount++
                    }
                }
                row.createCell(pCol).apply { setCellValue(presentCount.toDouble()); cellStyle = presentStyle }
                val pct = if(records.isNotEmpty()) presentCount*100.0/records.size else 0.0
                row.createCell(pctCol).apply { setCellValue(String.format(Locale.US,"%.1f%%", pct)); cellStyle = presentStyle }
            }
            // widths
            sheet.setColumnWidth(0, 2800)
            for(i in 1..records.size) sheet.setColumnWidth(i, 4200)
            sheet.setColumnWidth(pCol, 2800)
            sheet.setColumnWidth(pctCol, 2800)
            // autofilter
            try { sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0,0,0,pctCol)) } catch(_:Exception){}
        }

        buildSheet("STE_Lecture", lectures)
        buildSheet("STE_Practical", practicals)
        // If only one type, the other sheet won't exist; ensure at least one exists
        if(wb.numberOfSheets==0){
            buildSheet("STE_Combined", steRecords)
        }
        // Summary sheet
        val sum = wb.createSheet("Summary")
        val r0 = sum.createRow(0); r0.createCell(0).apply{ setCellValue("STE Attendance Summary"); cellStyle=headerStyle }; sum.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(0,0,0,3))
        val r1 = sum.createRow(1); listOf("Type","Lectures","Practicals","Total").forEachIndexed{ i,v-> r1.createCell(i).apply{ setCellValue(v); cellStyle=headerStyle } }
        val r2 = sum.createRow(2); r2.createCell(0).apply{ setCellValue("Count"); cellStyle=rollStyle }; r2.createCell(1).apply{ setCellValue(lectures.size.toDouble()); cellStyle=presentStyle }; r2.createCell(2).apply{ setCellValue(practicals.size.toDouble()); cellStyle=presentStyle }; r2.createCell(3).apply{ setCellValue(steRecords.size.toDouble()); cellStyle=presentStyle }
        sum.setColumnWidth(0, 5000); sum.setColumnWidth(1, 3500); sum.setColumnWidth(2, 3500); sum.setColumnWidth(3, 3500)

        val outFile = java.io.File(ctx.cacheDir, "STE_Attendance_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.xlsx")
        java.io.FileOutputStream(outFile).use { wb.write(it) }
        wb.close()
        outFile
    } catch(t: Throwable){
        t.printStackTrace()
        Toast.makeText(ctx, "Excel failed: ${t.message}", Toast.LENGTH_LONG).show()
        null
    }
}

// Generalized: All subjects with Lecture/Practical separated
fun generateFullExcel(ctx: Context, rolls: List<String>, history: List<AttendanceRecord>): java.io.File? {
    if (history.isEmpty()) {
        Toast.makeText(ctx, "No records yet — take attendance first", Toast.LENGTH_SHORT).show()
        return null
    }
    return try {
        val wb = org.apache.poi.xssf.usermodel.XSSFWorkbook()
        val headerStyle = wb.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.TAN.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER
            val f = wb.createFont().apply { bold = true; color = org.apache.poi.ss.usermodel.IndexedColors.BROWN.index }
            setFont(f)
            borderBottom = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderTop = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderLeft = org.apache.poi.ss.usermodel.BorderStyle.THIN
            borderRight = org.apache.poi.ss.usermodel.BorderStyle.THIN
        }
        val presentStyle = wb.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.LIGHT_GREEN.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
        }
        val absentStyle = wb.createCellStyle().apply {
            fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.ROSE.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
            val f = wb.createFont().apply { bold = true; color = org.apache.poi.ss.usermodel.IndexedColors.DARK_RED.index }
            setFont(f)
        }
        val rollStyle = wb.createCellStyle().apply {
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
            verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER
        }

        fun buildSheet(name: String, records: List<AttendanceRecord>) {
            if (records.isEmpty()) return
            val sheet = wb.createSheet(name.take(31))
            val header = sheet.createRow(0)
            header.createCell(0).apply { setCellValue("Roll No"); cellStyle = headerStyle }
            records.forEachIndexed { idx, rec ->
                val c = header.createCell(idx + 1)
                val label = "${rec.date}\n${rec.time}\n${rec.subject}-${if (rec.type == "Practical") "PR-${rec.coGroup ?: ""}" else "LE"}"
                c.setCellValue(label)
                c.cellStyle = headerStyle
            }
            val pCol = records.size + 1
            val pctCol = records.size + 2
            header.createCell(pCol).apply { setCellValue("Present"); cellStyle = headerStyle }
            header.createCell(pctCol).apply { setCellValue("%"); cellStyle = headerStyle }
            header.heightInPoints = 36f
            rolls.forEachIndexed { rIdx, roll ->
                val row = sheet.createRow(rIdx + 1)
                row.createCell(0).apply { setCellValue(roll); cellStyle = rollStyle }
                var presentCount = 0
                records.forEachIndexed { cIdx, rec ->
                    val cell = row.createCell(cIdx + 1)
                    val isAbsent = rec.absent.contains(roll)
                    if (isAbsent) {
                        cell.setCellValue("A"); cell.cellStyle = absentStyle
                    } else {
                        cell.setCellValue("P"); cell.cellStyle = presentStyle; presentCount++
                    }
                }
                row.createCell(pCol).apply { setCellValue(presentCount.toDouble()); cellStyle = presentStyle }
                val pct = if (records.isNotEmpty()) presentCount * 100.0 / records.size else 0.0
                row.createCell(pctCol).apply { setCellValue(String.format(Locale.US, "%.1f%%", pct)); cellStyle = presentStyle }
            }
            sheet.setColumnWidth(0, 2800)
            for (i in 1..records.size) sheet.setColumnWidth(i, 4200)
            sheet.setColumnWidth(pCol, 2800)
            sheet.setColumnWidth(pctCol, 2800)
            try { sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, pctCol)) } catch (_: Exception) {}
        }

        // Per subject
        val subjects = history.map { it.subject }.distinct().sorted()
        for (subj in subjects) {
            val subRecs = history.filter { it.subject.equals(subj, true) }.sortedBy { it.timestamp }
            val lecs = subRecs.filter { it.type == "Lecture" }
            val pracs = subRecs.filter { it.type == "Practical" }
            buildSheet("${subj}_Lecture", lecs)
            buildSheet("${subj}_Practical", pracs)
            if (lecs.isEmpty() && pracs.isEmpty()) buildSheet("${subj}_All", subRecs)
        }
        // Combined summary
        val sum = wb.createSheet("Summary")
        val r0 = sum.createRow(0); r0.createCell(0).apply { setCellValue("Attendance Summary — All Subjects"); cellStyle = headerStyle }; sum.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 3))
        val r1 = sum.createRow(1); listOf("Subject", "Lectures", "Practicals", "Total").forEachIndexed { i, v -> r1.createCell(i).apply { setCellValue(v); cellStyle = headerStyle } }
        subjects.forEachIndexed { idx, subj ->
            val row = sum.createRow(idx + 2)
            val lecs = history.count { it.subject.equals(subj, true) && it.type == "Lecture" }
            val pracs = history.count { it.subject.equals(subj, true) && it.type == "Practical" }
            row.createCell(0).apply { setCellValue(subj); cellStyle = rollStyle }
            row.createCell(1).apply { setCellValue(lecs.toDouble()); cellStyle = presentStyle }
            row.createCell(2).apply { setCellValue(pracs.toDouble()); cellStyle = presentStyle }
            row.createCell(3).apply { setCellValue((lecs + pracs).toDouble()); cellStyle = presentStyle }
        }
        sum.setColumnWidth(0, 5000); sum.setColumnWidth(1, 3500); sum.setColumnWidth(2, 3500); sum.setColumnWidth(3, 3500)

        val outFile = java.io.File(ctx.cacheDir, "Attendance_Full_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.xlsx")
        java.io.FileOutputStream(outFile).use { wb.write(it) }
        wb.close()
        outFile
    } catch (t: Throwable) {
        t.printStackTrace()
        Toast.makeText(ctx, "Excel failed: ${t.message}", Toast.LENGTH_LONG).show()
        null
    }
}
fun openWhatsApp(ctx: Context, text: String) {
    try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            `package` = "com.whatsapp"
        }
        ctx.startActivity(sendIntent)
    } catch (e: Exception) {
        try {
            val chooser = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            ctx.startActivity(Intent.createChooser(chooser, "Share via"))
        } catch (ex: Exception) {
            Toast.makeText(ctx, "WhatsApp not found", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------- Main Activity ----------
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply saved theme before first composition (avoid flash)
        val prefs = Prefs(this)
        ThemeManager.setTheme(AppThemes.byId(prefs.loadTheme()))
        setContent {
            AttendanceApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceApp() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var appTheme by remember { mutableStateOf(AppThemes.byId(prefs.loadTheme())) }
    // Keep ThemeManager in sync and persist
    LaunchedEffect(appTheme.id) {
        ThemeManager.setTheme(appTheme)
        prefs.saveTheme(appTheme.id)
    }
    // Theme-aware MaterialTheme - wraps entire app so theme picker recomposes instantly
    MaterialTheme(
        colorScheme = if (appTheme.id == "midnight") darkColorScheme(
            primary = appTheme.primary,
            onPrimary = Color.White,
            primaryContainer = appTheme.accentLight,
            secondary = appTheme.secondary,
            background = appTheme.bg,
            surface = appTheme.surface,
            onBackground = appTheme.text,
            onSurface = appTheme.text
        ) else lightColorScheme(
            primary = appTheme.primary,
            onPrimary = Color.White,
            primaryContainer = appTheme.accentLight,
            secondary = appTheme.secondary,
            background = appTheme.bg,
            surface = appTheme.surface,
            onBackground = appTheme.text,
            onSurface = appTheme.text
        )
    ) {
        val db = remember { AttendanceDatabase.getInstance(ctx) }
        val dao = remember { db.attendanceDao() }
        val scope = rememberCoroutineScope()

        // --- Room-backed state ---
        val tDao = remember { db.timetableDao() }
    var rolls by remember { mutableStateOf<List<String>>(emptyList()) }
    var subjects by remember { mutableStateOf<List<String>>(emptyList()) }
    var timetable by remember { mutableStateOf<List<DaySchedule>>(emptyList()) }
    var history by remember { mutableStateOf<List<AttendanceRecord>>(emptyList()) }
    var isDbReady by remember { mutableStateOf(false) }

    // Helpers to reload from Room
    suspend fun reloadRolls() {
        val students = dao.getStudents()
        rolls = students.map { it.roll }.sortedWith(compareBy({ it.toIntOrNull() ?: 9999 }, { it }))
        if (rolls.isEmpty()) {
            dao.insertStudents(SeedData.defaultStudents())
            rolls = SeedData.defaultStudents().map { it.roll }
        }
    }
    suspend fun reloadSubjects() {
        val subs = dao.getSubjects()
        subjects = subs.map { it.code }.sorted()
        if (subjects.isEmpty()) {
            dao.insertSubjects(SeedData.defaultSubjects())
            subjects = SeedData.defaultSubjects().map { it.code }
        }
    }
    suspend fun reloadHistory() {
        val records = dao.getRecords()
        val allAbsents = dao.getAllAbsents()
        val grouped = allAbsents.groupBy { it.recordId }
        history = records.map { rec ->
            val abs = grouped[rec.id]?.map { it.roll } ?: emptyList()
            rec.toUi(abs)
        }
    }
    suspend fun reloadTimetable() {
        val slots = tDao.getAll()
        timetable = if (slots.isEmpty()) {
            val seeded = SeedData.defaultTimetableSlots()
            tDao.insertAll(seeded)
            seeded.toDaySchedules()
        } else slots.toDaySchedules()
    }

    var absentSet by remember { mutableStateOf(setOf<String>()) }
    var selectedDate by remember { mutableStateOf(today()) }
    var selectedTime by remember { mutableStateOf("9.15 - 10.15") }
    var selectedSubject by remember { mutableStateOf("STE") }
    var selectedType by remember { mutableStateOf("Lecture") } // Lecture / Practical
    var selectedCo by remember { mutableStateOf("CO1") }
    var headerText by remember { mutableStateOf("CO-5K (A)") }

    LaunchedEffect(Unit) {
        try { migrateFromPrefsIfNeeded(ctx, dao) } catch (_: Exception) {}
        try { migrateTimetableFromPrefsIfNeeded(ctx, tDao) } catch (_: Exception) {}
        reloadRolls()
        reloadSubjects()
        reloadTimetable()
        reloadHistory()
        reloadHistory()
        reloadTimetable()
        isDbReady = true
    }
    // Keep selectedSubject valid once subjects load
    LaunchedEffect(subjects) {
        if (subjects.isNotEmpty() && selectedSubject !in subjects) selectedSubject = subjects.first()
    }

    var selectedTab by remember { mutableStateOf(0) }

    var showPreview by remember { mutableStateOf(false) }
    var showRollEditor by remember { mutableStateOf(false) }
    var showTtEditor by remember { mutableStateOf(false) }
    var showSubjectEditor by remember { mutableStateOf(false) }
    var editingRecord by remember { mutableStateOf<AttendanceRecord?>(null) }

    val absentListSorted by remember(absentSet) {
        derivedStateOf {
            absentSet.toList().sortedWith(compareBy({ it.toIntOrNull() ?: 9999 }, { it.lowercase() }))
        }
    }
    val absentString = if (absentListSorted.isEmpty()) "NIL" else absentListSorted.joinToString(", ")

    var editDate by remember { mutableStateOf(selectedDate) }
    var editTime by remember { mutableStateOf(selectedTime) }
    var editSubject by remember { mutableStateOf(selectedSubject) }
    var editType by remember { mutableStateOf(selectedType) }
    var editCo by remember { mutableStateOf(selectedCo) }
    var editAbsent by remember { mutableStateOf(absentString) }
    var editHeader by remember { mutableStateOf(headerText) }
    var editMessage by remember { mutableStateOf("") }

    fun refreshMessage() {
        editMessage = generateMessage(editHeader, editDate, editTime, editSubject, editAbsent.ifBlank { "NIL" }, editType, if(editType=="Practical") editCo else null)
    }
    LaunchedEffect(showPreview) {
        if (showPreview) {
            editDate = selectedDate
            editTime = selectedTime
            editSubject = selectedSubject
            editType = selectedType
            editCo = selectedCo
            editHeader = headerText
            editAbsent = if (absentSet.isEmpty()) "NIL" else absentListSorted.joinToString(", ")
            refreshMessage()
        }
    }

    fun saveCurrentToHistory(){
        val rec = AttendanceRecord(
            date = selectedDate,
            time = selectedTime,
            subject = selectedSubject,
            type = selectedType,
            coGroup = if(selectedType=="Practical") selectedCo else null,
            header = headerText,
            absent = absentListSorted
        )
        scope.launch {
            dao.insertRecordWithAbsents(
                rec.toEntity(),
                rec.absent.map { AttendanceAbsentEntity(recordId = rec.id, roll = it) }
            )
            reloadHistory()
            Toast.makeText(ctx, "Saved to ${rec.subject} tracking (${selectedType})", Toast.LENGTH_SHORT).show()
        }
    }
    // Auto copy absent to clipboard-like? We'll keep simple.

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Attendance Desk", fontWeight = FontWeight.Bold, color = CreamText, fontSize = 18.sp, maxLines=1)
                        Text("M.H. Saboo Siddik · CO5K", fontSize = 10.sp, color = CreamTextSec, maxLines=1)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CreamBg),
                navigationIcon = {
                    Box(
                        modifier = Modifier.padding(start=10.dp).size(34.dp).clip(CircleShape).background(CreamCard).border(1.dp, CreamBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.School, null, tint = CreamPrimary, modifier = Modifier.size(18.dp)) }
                },
                actions = {
                    IconButton(onClick = { showPreview = true }) {
                        Box(modifier = Modifier.size(34.dp).clip(CircleShape).background(CreamPrimary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CreamCard, tonalElevation = 2.dp) {
                NavigationBarItem(selected = selectedTab==0, onClick = { selectedTab=0 }, icon = { Icon(Icons.Filled.GridView, null, modifier=Modifier.size(20.dp)) }, label = { Text("Grid", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CreamPrimary, selectedTextColor = CreamPrimary, indicatorColor = CreamAccentLight))
                NavigationBarItem(selected = selectedTab==1, onClick = { selectedTab=1 }, icon = { Icon(Icons.Filled.CalendarViewWeek, null, modifier=Modifier.size(20.dp)) }, label = { Text("Table", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CreamPrimary, selectedTextColor = CreamPrimary, indicatorColor = CreamAccentLight))
                NavigationBarItem(selected = selectedTab==2, onClick = { selectedTab=2 }, icon = { Icon(Icons.Filled.Analytics, null, modifier=Modifier.size(20.dp)) }, label = { Text("Analytics", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CreamPrimary, selectedTextColor = CreamPrimary, indicatorColor = CreamAccentLight))
                NavigationBarItem(selected = selectedTab==3, onClick = { selectedTab=3 }, icon = { Icon(Icons.Filled.Settings, null, modifier=Modifier.size(20.dp)) }, label = { Text("Settings", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = CreamPrimary, selectedTextColor = CreamPrimary, indicatorColor = CreamAccentLight))
            }
        },
        containerColor = CreamBg,
        floatingActionButton = {
            if (selectedTab==0) {
                ExtendedFloatingActionButton(
                    onClick = { showPreview = true },
                    containerColor = CreamPrimary,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp)) },
                    text = { Text("${absentSet.size} absent • Send", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(CreamBg)) {
            when (selectedTab) {
                0 -> AttendanceGridScreen(
                    rolls = rolls,
                    absentSet = absentSet,
                    onToggle = { r ->
                        val newSet = absentSet.toMutableSet()
                        if (newSet.contains(r)) newSet.remove(r) else newSet.add(r)
                        absentSet = newSet
                    },
                    onClear = { absentSet = mutableSetOf() },
                    onSelectAllAbsent = { absentSet = rolls.toMutableSet() },
                    onInvert = {
                        val inverted = rolls.filter { it !in absentSet }.toMutableSet()
                        absentSet = inverted
                    },
                    selectedDate = selectedDate,
                    selectedTime = selectedTime,
                    selectedSubject = selectedSubject,
                    selectedType = selectedType,
                    selectedCo = selectedCo,
                    onDateChange = { selectedDate = it; editDate = it },
                    onTimeChange = { selectedTime = it },
                    onSubjectChange = { selectedSubject = it },
                    onTypeChange = { selectedType = it; editType = it; refreshMessage() },
                    onCoChange = { selectedCo = it; editCo = it; refreshMessage() },
                    subjects = subjects,
                    onEditRolls = { showRollEditor = true },
                    headerText = headerText,
                    onHeaderChange = {
                        headerText = it
                        editHeader = it
                        // Auto practical when Batch-wise, Lecture when CO-5K
                        if(isBatchPractical(it)){
                            selectedType = "Practical"
                            selectedCo = batchToCo(it)
                            editType = "Practical"
                            editCo = selectedCo
                        } else {
                            selectedType = "Lecture"
                            editType = "Lecture"
                        }
                        refreshMessage()
                    }
                )
                1 -> TimetableScreen(
                    timetable = timetable,
                    onSlotClick = { day, slot ->
                        selectedTime = slot.time
                        val raw = slot.subject.split("\n").firstOrNull()?.trim() ?: slot.subject
                        val subj = raw.split(" ").firstOrNull()?.split("-")?.lastOrNull()?.replace(Regex("[^A-Za-z0-9]"), "")?.take(6) ?: raw.take(8)
                        if (subj.isNotBlank() && subj != "BREAK" && subj != "") {
                            val clean = subj.uppercase()
                            if (clean.length in 2..6) selectedSubject = clean
                        }
                        // auto detect practical if slot contains L1/L2/L3
                        if(slot.subject.contains("L1")||slot.subject.contains("L2")||slot.subject.contains("L3")){
                            // keep user choice but hint
                        }
                        selectedTab = 0
                        Toast.makeText(ctx, "$day ${slot.time} selected", Toast.LENGTH_SHORT).show()
                    },
                    onEditRequest = { showTtEditor = true }
                )
                2 -> RecordsScreen(
                    rolls = rolls,
                    history = history,
                    subjects = subjects,
                    onClearHistory = {
                        scope.launch {
                            dao.clearAllRecords()
                            reloadHistory()
                            Toast.makeText(ctx,"History cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onGenerateExcel = {
                        scope.launch {
                            try {
                                val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { generateSTEExcel(ctx, rolls, history) }
                                if(f!=null){
                                    saveExcelToDownloads(ctx, f)
                                    shareExcelFile(ctx, f)
                                    Toast.makeText(ctx, "STE Excel saved • also in Download/AttendanceDesk", Toast.LENGTH_LONG).show()
                                }
                            } catch (t: Throwable) {
                                Toast.makeText(ctx, "Excel error: ${t.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onGenerateFullExcel = {
                        scope.launch {
                            try {
                                val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { generateFullExcel(ctx, rolls, history) }
                                if(f!=null){
                                    saveExcelToDownloads(ctx, f)
                                    shareExcelFile(ctx, f)
                                    Toast.makeText(ctx, "Full Excel (all subjects) saved", Toast.LENGTH_LONG).show()
                                }
                            } catch (t: Throwable) {
                                Toast.makeText(ctx, "Excel error: ${t.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDeleteRecord = { id ->
                        scope.launch {
                            dao.deleteRecordWithAbsents(id)
                            reloadHistory()
                        }
                    },
                    onEditRecord = { rec -> editingRecord = rec }
                )
                3 -> SettingsScreen(
                    rolls = rolls,
                    onRollsChange = { /* deprecated - handled via dialog */ },
                    timetable = timetable,
                    onTimetableChange = { newTt ->
                        scope.launch {
                            tDao.clearAll()
                            tDao.insertAll(newTt.toSlotEntities())
                            reloadTimetable()
                        }
                    },
                    subjects = subjects,
                    onSubjectsChange = { /* deprecated */ },
                    onManageRolls = { showRollEditor = true },
                    onManageTt = { showTtEditor = true },
                    onManageSubjects = { showSubjectEditor = true },
                    selectedTheme = appTheme,
                    onThemeChange = { appTheme = it }
                )
            }
        }
    }

    if (showPreview) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            containerColor = CreamCard,
            title = { Text("Preview — Ready to Send", fontWeight = FontWeight.Bold, color = CreamText, fontSize = 16.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                    var batchExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = batchExpanded, onExpandedChange = { batchExpanded = !batchExpanded }) {
                        OutlinedTextField(value = editHeader, onValueChange={}, readOnly=true, label = { Text("Batch / Class") }, modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(batchExpanded) }, shape = RoundedCornerShape(10.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CreamPrimary))
                        ExposedDropdownMenu(expanded = batchExpanded, onDismissRequest = { batchExpanded=false }) {
                            batchOptions.forEach { b ->
                                DropdownMenuItem(text = { Text(b) }, onClick = {
                                    editHeader = b
                                    // auto practical when Batch 1/2/3, lecture when CO-5K (A)
                                    if(isBatchPractical(b)){
                                        editType = "Practical"
                                        editCo = batchToCo(b)
                                    } else {
                                        editType = "Lecture"
                                    }
                                    refreshMessage()
                                    batchExpanded=false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = editDate, onValueChange = { editDate = it; refreshMessage() }, label = { Text("Date") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), leadingIcon = { Icon(Icons.Filled.CalendarToday, null, modifier = Modifier.size(14.dp)) }, singleLine = true)
                        OutlinedTextField(value = editTime, onValueChange = { editTime = it; refreshMessage() }, label = { Text("Slot") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), leadingIcon = { Icon(Icons.Filled.AccessTime, null, modifier=Modifier.size(14.dp)) }, singleLine = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Auto-locked Type — Batch => Practical, CO-5K (A) => Lecture
                    val isBatchEdit = isBatchPractical(editHeader)
                    LaunchedEffect(editHeader) {
                        val expected = if(isBatchEdit) "Practical" else "Lecture"
                        if(editType != expected) { editType = expected; refreshMessage() }
                        if(isBatchEdit) {
                            val expectedCo = batchToCo(editHeader)
                            if(editCo != expectedCo) { editCo = expectedCo; refreshMessage() }
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                        Box(modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(if(isBatchEdit) AbsentBg else PresentBg).border(1.dp, if(isBatchEdit) AbsentBorder else PresentBorder, RoundedCornerShape(8.dp)).padding(horizontal=10.dp, vertical=7.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                Icon(if(isBatchEdit) Icons.Filled.Handyman else Icons.Filled.MenuBook, null, modifier=Modifier.size(12.dp), tint=if(isBatchEdit) AbsentRed else PresentGreen)
                                Text(if(isBatchEdit) "Practical" else "Lecture", fontSize=10.sp, fontWeight=FontWeight.Bold, color=if(isBatchEdit) AbsentRed else PresentGreen)
                                if(isBatchEdit) {
                                    Text("• $editCo", fontSize=10.sp, fontWeight=FontWeight.Bold, color=AbsentRed)
                                }
                            }
                        }
                        Text(if(isBatchEdit) "Auto by Batch" else "Auto by CO-5K (A)", fontSize=9.sp, color=CreamTextSec, fontWeight=FontWeight.SemiBold, modifier=Modifier.weight(1f), maxLines=1, overflow=TextOverflow.Ellipsis)
                        Icon(Icons.Filled.Lock, null, modifier=Modifier.size(12.dp), tint=CreamMuted)
                    }
                    Spacer(Modifier.height(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(value = editSubject, onValueChange = { editSubject = it; refreshMessage() }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, shape = RoundedCornerShape(10.dp), singleLine = true)
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded=false }) {
                            subjects.forEach { s ->
                                DropdownMenuItem(text = { Text(s) }, onClick = { editSubject = s; refreshMessage(); expanded=false })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = editAbsent, onValueChange = { editAbsent = it; refreshMessage() }, label = { Text("Absent (editable)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), minLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Text("Message (will be used for Excel tracking if you tap Save)", fontSize = 11.sp, color = CreamTextSec, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editMessage,
                        onValueChange = { editMessage = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min=110.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CreamPrimary, unfocusedBorderColor = CreamBorder, focusedContainerColor = CreamSurface, unfocusedContainerColor = CreamSurface),
                        minLines = 4
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Tracking: Saved to STE Excel (Lecture vs Practical separate) when you Save", fontSize=10.sp, color=CreamMuted)
                }
            },
            confirmButton = {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilledTonalButton(onClick={
                        if(editMessage.isBlank()){Toast.makeText(ctx,"Message empty",Toast.LENGTH_SHORT).show(); return@FilledTonalButton}
                        val absentList = editAbsent.split(",").map{it.trim()}.filter{it.isNotEmpty() && it!="NIL"}
                        val rec = AttendanceRecord(date=editDate, time=editTime, subject=editSubject, type=editType, coGroup=if(editType=="Practical") editCo else null, header=editHeader, absent=absentList)
                        scope.launch {
                            try {
                                dao.insertRecordWithAbsents(rec.toEntity(), rec.absent.map { AttendanceAbsentEntity(rec.id, it) })
                                reloadHistory()
                                selectedDate = editDate; selectedTime=editTime; selectedSubject=editSubject; selectedType=editType; selectedCo=editCo; headerText=editHeader
                                try{
                                    val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        dao.getRecords().map { r ->
                                            val abs = dao.getAbsentsForRecord(r.id).map { it.roll }
                                            r.toUi(abs)
                                        }
                                    }
                                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { generateSTEExcel(ctx, rolls, latest) }
                                    if(f!=null){
                                        saveExcelToDownloads(ctx, f)
                                        Toast.makeText(ctx, "Saved • Excel updated in AttendanceDesk", Toast.LENGTH_LONG).show()
                                    }
                                } catch(t: Throwable){ t.printStackTrace(); Toast.makeText(ctx, "Saved but Excel err: ${t.message}", Toast.LENGTH_SHORT).show() }
                            } catch (t: Throwable) {
                                Toast.makeText(ctx, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                showPreview=false
                                openWhatsApp(ctx, editMessage)
                            }
                        }
                    }, colors=ButtonDefaults.filledTonalButtonColors(containerColor=CreamAccentLight, contentColor=CreamText), shape=RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Save, null, modifier=Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Save & Send", fontSize=11.sp) }
                    Button(onClick = {
                        if (editMessage.isBlank()) { Toast.makeText(ctx,"Message empty",Toast.LENGTH_SHORT).show(); return@Button }
                        val absentList = editAbsent.split(",").map{it.trim()}.filter{it.isNotEmpty() && it!="NIL"}
                        val rec = AttendanceRecord(date=editDate, time=editTime, subject=editSubject, type=editType, coGroup=if(editType=="Practical") editCo else null, header=editHeader, absent=absentList)
                        scope.launch {
                            try {
                                dao.insertRecordWithAbsents(rec.toEntity(), rec.absent.map { AttendanceAbsentEntity(rec.id, it) })
                                reloadHistory()
                                selectedDate = editDate; selectedTime=editTime; selectedSubject=editSubject; selectedType=editType; selectedCo=editCo; headerText=editHeader
                                try{
                                    val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        dao.getRecords().map { r ->
                                            val abs = dao.getAbsentsForRecord(r.id).map { it.roll }
                                            r.toUi(abs)
                                        }
                                    }
                                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { generateSTEExcel(ctx, rolls, latest) }
                                    if(f!=null) saveExcelToDownloads(ctx, f)
                                } catch(t: Throwable){ t.printStackTrace() }
                            } catch (t: Throwable) {
                                Toast.makeText(ctx, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                showPreview = false
                                openWhatsApp(ctx, editMessage)
                                Toast.makeText(ctx, "Saved • WhatsApp opened", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = CreamPrimary), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Send, null, modifier=Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Send", fontSize=11.sp) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPreview=false }) { Text("Close", color=CreamTextSec) }
            }
        )
    }

    if (showRollEditor) {
        RollEditorDialog(rolls = rolls, onSave = { newList ->
            scope.launch {
                // Replace all students: clear + insert (70 default is 1-63, 81-87)
                dao.clearStudents()
                dao.insertStudents(newList.map { StudentEntity(it) })
                reloadRolls()
                showRollEditor=false
            }
        }, onDismiss = { showRollEditor=false })
    }
    if (showTtEditor) {
        TtEditorDialog(timetable = timetable, onSave = { newTt ->
            scope.launch {
                tDao.clearAll()
                tDao.insertAll(newTt.toSlotEntities())
                reloadTimetable()
                showTtEditor=false
            }
        }, onDismiss = { showTtEditor=false })
    }
    if (showSubjectEditor) {
        SubjectEditorDialog(subjects = subjects, onSave = { newList ->
            scope.launch {
                dao.clearSubjects()
                dao.insertSubjects(newList.map { SubjectEntity(it) })
                reloadSubjects()
                showSubjectEditor=false
                if(selectedSubject !in newList) selectedSubject = newList.firstOrNull() ?: "STE"
            }
        }, onDismiss = { showSubjectEditor=false })
    }
    editingRecord?.let { rec ->
        EditRecordDialog(
            record = rec,
            subjects = subjects,
            rolls = rolls,
            onDismiss = { editingRecord = null },
            onSave = { updated ->
                scope.launch {
                    dao.deleteRecordWithAbsents(updated.id)
                    dao.insertRecordWithAbsents(updated.toEntity(), updated.absent.map { AttendanceAbsentEntity(updated.id, it) })
                    reloadHistory()
                    editingRecord = null
                    Toast.makeText(ctx, "Record updated", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    } // close MaterialTheme
}

// ---------- Grid Screen — fits without scroll ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceGridScreen(
    rolls: List<String>,
    absentSet: Set<String>,
    onToggle: (String)->Unit,
    onClear: ()->Unit,
    onSelectAllAbsent: ()->Unit,
    onInvert: ()->Unit,
    selectedDate: String,
    selectedTime: String,
    selectedSubject: String,
    selectedType: String,
    selectedCo: String,
    onDateChange: (String)->Unit,
    onTimeChange: (String)->Unit,
    onSubjectChange: (String)->Unit,
    onTypeChange: (String)->Unit,
    onCoChange: (String)->Unit,
    subjects: List<String>,
    onEditRolls: ()->Unit,
    headerText: String,
    onHeaderChange: (String)->Unit
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(rolls, filter) {
        if (filter.isBlank()) rolls else rolls.filter { it.contains(filter, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        // Compact header card — no scroll, fixed height approx 170dp
        Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(14.dp), border=BorderStroke(1.dp,CreamBorder), elevation=CardDefaults.cardElevation(1.dp)) {
            Column(modifier=Modifier.padding(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically, modifier=Modifier.fillMaxWidth()) {
                    Box(modifier=Modifier.size(28.dp).clip(CircleShape).background(CreamAccentLight), contentAlignment=Alignment.Center){ Icon(Icons.Filled.People, null, tint=CreamPrimary, modifier=Modifier.size(16.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier=Modifier.weight(1f)) {
                        Text("${rolls.size} rolls  •  ${absentSet.size} absent", fontWeight=FontWeight.Bold, color=CreamText, fontSize=12.sp, maxLines=1)
                        Text("${rolls.size - absentSet.size} present • $selectedSubject ${if(selectedType=="Practical") "(${selectedCo})" else ""} • $selectedTime", fontSize=10.sp, color=CreamTextSec, maxLines=1, overflow=TextOverflow.Ellipsis)
                    }
                    IconButton(onClick=onEditRolls, modifier=Modifier.size(28.dp)) { Icon(Icons.Filled.Edit, null, tint=CreamPrimary, modifier=Modifier.size(16.dp)) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                    var batchExp by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded=batchExp, onExpandedChange={batchExp=!batchExp}, modifier=Modifier.weight(1f)) {
                        OutlinedTextField(value=headerText, onValueChange={}, readOnly=true, label={Text("Batch", fontSize=10.sp)}, modifier=Modifier.menuAnchor().fillMaxWidth(), trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(batchExp)}, shape=RoundedCornerShape(10.dp), singleLine=true, textStyle= androidx.compose.ui.text.TextStyle(fontSize=12.sp), colors=OutlinedTextFieldDefaults.colors(disabledBorderColor=CreamBorder))
                        ExposedDropdownMenu(expanded=batchExp, onDismissRequest={batchExp=false}) {
                            batchOptions.forEach { b -> DropdownMenuItem(text={Text(b, fontSize=12.sp)}, onClick={onHeaderChange(b); batchExp=false}) }
                        }
                    }
                    OutlinedTextField(value=filter, onValueChange={filter=it}, placeholder={Text("Filter", fontSize=11.sp)}, leadingIcon={Icon(Icons.Filled.Search, null, modifier=Modifier.size(14.dp), tint=CreamMuted)}, modifier=Modifier.weight(0.7f), shape=RoundedCornerShape(10.dp), singleLine=true, textStyle= androidx.compose.ui.text.TextStyle(fontSize=12.sp))
                    if(filter.isNotBlank()){
                        IconButton(onClick={filter=""}, modifier=Modifier.size(36.dp)){ Icon(Icons.Filled.Clear, null, tint=CreamMuted, modifier=Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Auto-locked Type — Batch => Practical, CO-5K (A) => Lecture
                val isBatchSelected = isBatchPractical(headerText)
                LaunchedEffect(headerText) {
                    val expectedType = if (isBatchSelected) "Practical" else "Lecture"
                    if (selectedType != expectedType) onTypeChange(expectedType)
                    if (isBatchSelected) {
                        val expectedCo = batchToCo(headerText)
                        if (selectedCo != expectedCo) onCoChange(expectedCo)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                    Box(modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(if(isBatchSelected) AbsentBg else PresentBg).border(1.dp, if(isBatchSelected) AbsentBorder else PresentBorder, RoundedCornerShape(8.dp)).padding(horizontal=10.dp, vertical=7.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            Icon(if(isBatchSelected) Icons.Filled.Handyman else Icons.Filled.MenuBook, null, modifier=Modifier.size(12.dp), tint=if(isBatchSelected) AbsentRed else PresentGreen)
                            Text(if(isBatchSelected) "Practical" else "Lecture", fontSize=10.sp, fontWeight=FontWeight.Bold, color=if(isBatchSelected) AbsentRed else PresentGreen)
                            if(isBatchSelected) {
                                Text("• ${batchToCo(headerText)}", fontSize=10.sp, fontWeight=FontWeight.Bold, color=AbsentRed)
                            }
                        }
                    }
                    Column(modifier=Modifier.weight(1f)) {
                        Text(if(isBatchSelected) "Auto: Practical by Batch" else "Auto: Lecture by CO-5K (A)", fontSize=9.sp, color=CreamTextSec, fontWeight=FontWeight.SemiBold, maxLines=1, overflow=TextOverflow.Ellipsis)
                        Text(if(isBatchSelected) "${selectedSubject} • locked" else "${selectedSubject} • full class", fontSize=9.sp, color=CreamMuted, maxLines=1, overflow=TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.Lock, null, modifier=Modifier.size(12.dp), tint=CreamMuted)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                    OutlinedCard(modifier=Modifier.weight(1f).clickable{ /* date picker handled below */ }, shape=RoundedCornerShape(10.dp), border=BorderStroke(1.dp,CreamBorder)) {
                        var showDate by remember { mutableStateOf(false) }
                        Row(modifier=Modifier.padding(horizontal=8.dp, vertical=6.dp).clickable{ showDate=true }, verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Filled.CalendarToday, null, tint=CreamPrimary, modifier=Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("Date", fontSize=9.sp, color=CreamTextSec, lineHeight=9.sp)
                                Text(selectedDate, fontSize=11.sp, fontWeight=FontWeight.SemiBold, color=CreamText, maxLines=1)
                            }
                        }
                        if(showDate){
                            var tmp by remember { mutableStateOf(selectedDate) }
                            AlertDialog(onDismissRequest={showDate=false}, containerColor=CreamCard, title={Text("Set Date", fontWeight=FontWeight.Bold, color=CreamText)}, text={
                                Column{ OutlinedTextField(value=tmp, onValueChange={tmp=it}, shape=RoundedCornerShape(10.dp), modifier=Modifier.fillMaxWidth(), placeholder={Text("dd/MM/yyyy")}); Spacer(Modifier.height(6.dp)); Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){ FilledTonalButton(onClick={tmp=today()}){Text("Today", fontSize=11.sp)}; FilledTonalButton(onClick={tmp=formatDateShort(Date())}){Text("Short", fontSize=11.sp)} } }
                            }, confirmButton={ Button(onClick={ onDateChange(tmp); showDate=false }, colors=ButtonDefaults.buttonColors(containerColor=CreamPrimary)){Text("Set")} }, dismissButton={ TextButton(onClick={showDate=false}){Text("Cancel")} })
                        }
                    }
                    OutlinedCard(modifier=Modifier.weight(1f), shape=RoundedCornerShape(10.dp), border=BorderStroke(1.dp,CreamBorder)) {
                        Row(modifier=Modifier.padding(horizontal=8.dp, vertical=6.dp), verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccessTime, null, tint=CreamPrimary, modifier=Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                            Column(modifier=Modifier.weight(1f)) {
                                Text("Slot", fontSize=9.sp, color=CreamTextSec, lineHeight=9.sp)
                                Text(selectedTime, fontSize=10.sp, fontWeight=FontWeight.SemiBold, color=CreamText, maxLines=1, overflow=TextOverflow.Ellipsis)
                            }
                        }
                    }
                    var subjExp by remember { mutableStateOf(false) }
                    Box(modifier=Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(expanded=subjExp, onExpandedChange={subjExp=!subjExp}) {
                            OutlinedTextField(value=selectedSubject, onValueChange={onSubjectChange(it)}, label={Text("Subject", fontSize=9.sp)}, modifier=Modifier.menuAnchor().fillMaxWidth(), trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(subjExp)}, shape=RoundedCornerShape(10.dp), singleLine=true, textStyle= androidx.compose.ui.text.TextStyle(fontSize=11.sp, fontWeight=FontWeight.SemiBold))
                            ExposedDropdownMenu(expanded=subjExp, onDismissRequest={subjExp=false}) {
                                subjects.forEach { s -> DropdownMenuItem(text={Text(s, fontSize=12.sp)}, onClick={onSubjectChange(s); subjExp=false}) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick=onClear, shape=RoundedCornerShape(10.dp), border=BorderStroke(1.dp, CreamBorder), modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=4.dp, vertical=4.dp)) { Text("All Present", fontSize=10.sp, color=CreamText, maxLines=1) }
                    FilledTonalButton(onClick=onSelectAllAbsent, colors=ButtonDefaults.filledTonalButtonColors(containerColor=AbsentBg, contentColor=AbsentRed), shape=RoundedCornerShape(10.dp), modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=4.dp, vertical=4.dp)) { Text("All Absent", fontSize=10.sp, maxLines=1) }
                    OutlinedButton(onClick=onInvert, shape=RoundedCornerShape(10.dp), border=BorderStroke(1.dp, CreamBorder), modifier=Modifier.weight(1f), contentPadding=PaddingValues(horizontal=4.dp, vertical=4.dp)) { Icon(Icons.Filled.Flip, null, modifier=Modifier.size(12.dp), tint=CreamText); Spacer(Modifier.width(4.dp)); Text("Invert", fontSize=10.sp, color=CreamText, maxLines=1) }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween, modifier=Modifier.fillMaxWidth()) {
            Text("Tap number: green=present, peach=absent", fontSize=10.sp, color=CreamTextSec)
            Text("${absentSet.size} absent", fontSize=10.sp, color=AbsentRed, fontWeight=FontWeight.Bold, modifier=Modifier.background(AbsentBg, RoundedCornerShape(8.dp)).padding(horizontal=6.dp, vertical=2.dp))
        }
        Spacer(Modifier.height(4.dp))

        // Ultra-fast lightweight grid — 7 columns, no Card overhead
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(bottom = 80.dp, top = 2.dp)
        ) {
            gridItems(filtered, key={it}, contentType={it}) { roll ->
                val isAbsent = absentSet.contains(roll)
                // Lightweight box instead of Card — 3x faster compose/measure
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if(isAbsent) AbsentBg else Color.White)
                        .border(1.dp, if(isAbsent) AbsentBorder else CreamBorder, RoundedCornerShape(9.dp))
                        .clickable { onToggle(roll) }
                        .padding(1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        roll,
                        fontWeight = FontWeight.ExtraBold,
                        color = if(isAbsent) AbsentRed else CreamText,
                        fontSize = if(roll.length > 4) 9.sp else 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    // subtle corner indicator — tiny, no layout cost
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if(isAbsent) AbsentRed.copy(alpha=0.95f) else PresentGreen.copy(alpha=0.9f))
                    )
                }
            }
        }
    }
}

@Composable
fun TimetableScreen(timetable: List<DaySchedule>, onSlotClick: (String, DaySlot)->Unit, onEditRequest: ()->Unit) {
    // LazyColumn for faster scroll & composition (was Column+verticalScroll -> jank)
    androidx.compose.foundation.lazy.LazyColumn(modifier=Modifier.fillMaxSize(), contentPadding=PaddingValues(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {
            Row(modifier=Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Column {
                    Text("Time-Table", fontWeight=FontWeight.Bold, color=CreamText, fontSize=16.sp)
                    Text("Tap slot → auto-fills time & subject", fontSize=10.sp, color=CreamTextSec)
                }
                FilledTonalButton(onClick=onEditRequest, shape=RoundedCornerShape(10.dp), colors=ButtonDefaults.filledTonalButtonColors(containerColor=CreamAccentLight, contentColor=CreamText), contentPadding=PaddingValues(horizontal=10.dp, vertical=6.dp)) {
                    Icon(Icons.Filled.Edit, null, modifier=Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Edit", fontSize=11.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically) {
                Box(modifier=Modifier.size(8.dp).clip(CircleShape).background(CreamPrimary)); Text("Lecture", fontSize=10.sp, color=CreamTextSec)
                Box(modifier=Modifier.size(8.dp).clip(CircleShape).background(CreamMuted)); Text("BREAK", fontSize=10.sp, color=CreamTextSec)
                Box(modifier=Modifier.size(8.dp).clip(CircleShape).background(CreamBorder)); Text("Free", fontSize=10.sp, color=CreamTextSec)
            }
        }
        items(timetable, key={it.day}) { day ->
            Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(14.dp), border=BorderStroke(1.dp, CreamBorder), modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                Column(modifier=Modifier.padding(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(modifier=Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(CreamPrimary), contentAlignment=Alignment.Center){ Text(day.day.take(3), color=Color.White, fontWeight=FontWeight.Bold, fontSize=10.sp) }
                        Spacer(Modifier.width(8.dp))
                        Text(day.day, fontWeight=FontWeight.Bold, color=CreamText, fontSize=12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${day.slots.count{it.subject.isNotBlank() && it.subject!="BREAK"}} lec", fontSize=9.sp, color=CreamTextSec, modifier=Modifier.background(CreamSurface, RoundedCornerShape(8.dp)).padding(horizontal=6.dp, vertical=3.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        day.slots.forEach { slot ->
                            val isBreak = slot.subject == "BREAK"
                            val isEmpty = slot.subject.isBlank()
                            val subjCode = subjectForRaw(slot.subject)
                            val accent = if (subjCode.isNotEmpty()) subjectAccent(subjCode) else CreamPrimary
                            val bg = when { isBreak -> CreamMuted.copy(alpha=0.10f); isEmpty -> Color.Transparent; else -> accent.copy(alpha=0.06f) }
                            val borderC = when { isBreak -> CreamMuted; isEmpty -> CreamBorder.copy(alpha=0.4f); else -> accent.copy(alpha=0.28f) }
                            Row(
                                modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, borderC, RoundedCornerShape(10.dp)).clickable(enabled=!isEmpty && !isBreak){ onSlotClick(day.day, slot) }.padding(horizontal=8.dp, vertical=8.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                // Left accent stripe - distinctive per subject
                                Box(modifier=Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(4.dp)).background(if(isEmpty||isBreak) CreamBorder else accent))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier=Modifier.width(76.dp)) {
                                    Text(slot.time, fontSize=10.sp, fontWeight=FontWeight.ExtraBold, color= if(isBreak) CreamMuted else accent, lineHeight=10.sp)
                                    if (subjCode.isNotEmpty() && !isBreak && !isEmpty) {
                                        Box(modifier=Modifier.padding(top=2.dp).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha=0.12f)).padding(horizontal=5.dp, vertical=1.dp)) {
                                            Text(subjCode, fontSize=8.sp, fontWeight=FontWeight.Bold, color=accent, lineHeight=8.sp)
                                        }
                                    }
                                }
                                Box(modifier=Modifier.width(1.dp).height(28.dp).background(CreamBorder.copy(alpha=0.6f)))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if(isEmpty) "— Free —" else slot.subject,
                                    fontSize=11.sp,
                                    color= when { isBreak-> CreamMuted; isEmpty-> CreamMuted; else -> CreamText },
                                    fontWeight= if(isBreak || isEmpty) FontWeight.Normal else FontWeight.Medium,
                                    modifier=Modifier.weight(1f),
                                    maxLines=3,
                                    overflow=TextOverflow.Ellipsis,
                                    lineHeight=12.sp
                                )
                                if(!isEmpty && !isBreak) {
                                    Box(modifier=Modifier.size(26.dp).clip(CircleShape).background(accent), contentAlignment=Alignment.Center) {
                                        Icon(Icons.Filled.ChevronRight, null, tint=Color.White, modifier=Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(70.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    rolls: List<String>,
    history: List<AttendanceRecord>,
    subjects: List<String> = listOf("OSY","DAN","STE","ENDS"),
    onClearHistory: ()->Unit,
    onGenerateExcel: ()->Unit,
    onGenerateFullExcel: ()->Unit = onGenerateExcel,
    onDeleteRecord: (String)->Unit,
    onEditRecord: (AttendanceRecord)->Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var analyticsSubject by remember { mutableStateOf("ALL") }
    var analyticsType by remember { mutableStateOf("ALL") }
    var defaulterThreshold by remember { mutableStateOf(75) }

    val filteredForAnalytics = remember(history, analyticsSubject, analyticsType) {
        history.filter { rec ->
            (analyticsSubject=="ALL" || rec.subject.equals(analyticsSubject, true)) &&
            (analyticsType=="ALL" || rec.type==analyticsType)
        }
    }
    val stats = remember(rolls, filteredForAnalytics) {
        com.attendance.desk.data.AnalyticsHelper.computeStats(
            rolls, filteredForAnalytics,
            subjectFilter = if (analyticsSubject=="ALL") null else analyticsSubject,
            typeFilter = if (analyticsType=="ALL") null else analyticsType
        )
    }
    val defaulters = remember(stats, defaulterThreshold) {
        com.attendance.desk.data.AnalyticsHelper.defaulterList(stats, defaulterThreshold.toDouble())
    }
    val subjectSummary = remember(history) { com.attendance.desk.data.AnalyticsHelper.subjectSummary(history) }
    val historySorted = remember(history) { history.sortedByDescending{ it.timestamp } }

    androidx.compose.foundation.lazy.LazyColumn(modifier=Modifier.fillMaxSize(), contentPadding=PaddingValues(10.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically, modifier=Modifier.fillMaxWidth()) {
                Box(modifier=Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(CreamPrimary), contentAlignment=Alignment.Center) {
                    Icon(Icons.Filled.Analytics, null, tint=Color.White, modifier=Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier=Modifier.weight(1f)) {
                    Text("Analytics & Records", fontWeight=FontWeight.ExtraBold, color=CreamText, fontSize=16.sp, letterSpacing=0.2.sp)
                    Text("${history.size} sessions • ${rolls.size} students • Lecture/Practical split", fontSize=10.sp, color=CreamTextSec)
                }
                Box(modifier=Modifier.clip(RoundedCornerShape(20.dp)).background(CreamAccentLight).padding(horizontal=8.dp, vertical=4.dp)) {
                    Text("${defaulters.size} defaulters", fontSize=10.sp, color=CreamPrimaryDark, fontWeight=FontWeight.Bold)
                }
            }
        }
        item {
            Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(16.dp), border=BorderStroke(1.dp, CreamBorder), elevation=CardDefaults.cardElevation(2.dp)) {
                Column(modifier=Modifier.padding(12.dp)) {
                    Text("Filters", fontWeight=FontWeight.Bold, color=CreamText, fontSize=11.sp, letterSpacing=0.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                        var subjExp by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded=subjExp, onExpandedChange={subjExp=!subjExp}, modifier=Modifier.weight(1f)) {
                            OutlinedTextField(value=analyticsSubject, onValueChange={}, readOnly=true, label={Text("Subject", fontSize=9.sp)}, modifier=Modifier.menuAnchor().fillMaxWidth(), trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(subjExp)}, shape=RoundedCornerShape(10.dp), singleLine=true, textStyle=androidx.compose.ui.text.TextStyle(fontSize=11.sp, fontWeight=FontWeight.SemiBold))
                            ExposedDropdownMenu(expanded=subjExp, onDismissRequest={subjExp=false}) {
                                DropdownMenuItem(text={Text("ALL", fontSize=12.sp)}, onClick={analyticsSubject="ALL"; subjExp=false})
                                subjects.forEach { s -> DropdownMenuItem(text={Text(s, fontSize=12.sp)}, onClick={analyticsSubject=s; subjExp=false}) }
                            }
                        }
                        Box(modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(CreamSurface).border(1.dp, CreamBorder, RoundedCornerShape(8.dp)).padding(2.dp)) {
                            Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                                FilterChip(selected=analyticsType=="ALL", onClick={analyticsType="ALL"}, label={Text("All", fontSize=10.sp, fontWeight=FontWeight.Bold)}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=CreamPrimary, selectedLabelColor=Color.White, containerColor=Color.Transparent))
                                FilterChip(selected=analyticsType=="Lecture", onClick={analyticsType="Lecture"}, label={Text("LE", fontSize=10.sp, fontWeight=FontWeight.Bold)}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PresentGreen, selectedLabelColor=Color.White, containerColor=Color.Transparent))
                                FilterChip(selected=analyticsType=="Practical", onClick={analyticsType="Practical"}, label={Text("PR", fontSize=10.sp, fontWeight=FontWeight.Bold)}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=AbsentRed, selectedLabelColor=Color.White, containerColor=Color.Transparent))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween, modifier=Modifier.fillMaxWidth()) {
                        Text("Defaulter threshold: $defaulterThreshold%", fontSize=11.sp, color=CreamTextSec, fontWeight=FontWeight.SemiBold)
                        Text("${defaulters.size} below", fontSize=11.sp, color=AbsentRed, fontWeight=FontWeight.Bold)
                    }
                    Slider(value=defaulterThreshold.toFloat(), onValueChange={defaulterThreshold=it.toInt()}, valueRange=50f..90f, steps=7, colors=SliderDefaults.colors(activeTrackColor=CreamPrimary, thumbColor=CreamPrimaryDark))
                    if (subjectSummary.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Sessions by subject", fontSize=10.sp, color=CreamTextSec, fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        subjectSummary.entries.sortedByDescending{ it.value.first + it.value.second }.forEach { (subj, pair) ->
                            val total = pair.first + pair.second
                            val maxTotal = subjectSummary.values.maxOf { it.first + it.second }.coerceAtLeast(1)
                            Column(modifier=Modifier.padding(bottom=6.dp)) {
                                Row(horizontalArrangement=Arrangement.SpaceBetween, modifier=Modifier.fillMaxWidth()) {
                                    Text(subj, fontSize=11.sp, fontWeight=FontWeight.Bold, color=CreamText)
                                    Text("LE ${pair.first} • PR ${pair.second} • $total", fontSize=10.sp, color=CreamTextSec)
                                }
                                Spacer(Modifier.height(3.dp))
                                Box(modifier=Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(CreamSurface).border(1.dp, CreamBorder, RoundedCornerShape(6.dp))) {
                                    Row(modifier=Modifier.fillMaxSize()) {
                                        if (pair.first>0) Box(modifier=Modifier.fillMaxHeight().weight(pair.first.toFloat()).background(PresentGreen))
                                        if (pair.second>0) Box(modifier=Modifier.fillMaxHeight().weight(pair.second.toFloat()).background(AbsentRed.copy(alpha=0.85f)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(16.dp), border=BorderStroke(1.dp,CreamBorder)) {
                Column(modifier=Modifier.padding(12.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                        val leCount = filteredForAnalytics.count { it.type=="Lecture" }
                        val prCount = filteredForAnalytics.count { it.type=="Practical" }
                        Box(modifier=Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(PresentBg).border(1.dp, PresentBorder, RoundedCornerShape(12.dp)).padding(10.dp), contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){ Text("$leCount", fontWeight=FontWeight.ExtraBold, color=PresentGreen, fontSize=22.sp); Text("Lectures", fontSize=10.sp, color=CreamTextSec, fontWeight=FontWeight.SemiBold) }
                        }
                        Box(modifier=Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(AbsentBg).border(1.dp, AbsentBorder, RoundedCornerShape(12.dp)).padding(10.dp), contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){ Text("$prCount", fontWeight=FontWeight.ExtraBold, color=AbsentRed, fontSize=22.sp); Text("Practicals", fontSize=10.sp, color=CreamTextSec, fontWeight=FontWeight.SemiBold) }
                        }
                        Box(modifier=Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CreamSurface).border(1.dp, CreamBorder, RoundedCornerShape(12.dp)).padding(10.dp), contentAlignment=Alignment.Center){
                            val avg = if (stats.isNotEmpty() && filteredForAnalytics.isNotEmpty()) stats.map{it.percent}.average() else 0.0
                            Column(horizontalAlignment=Alignment.CenterHorizontally){ Text(String.format(Locale.US,"%.0f%%", avg), fontWeight=FontWeight.ExtraBold, color=CreamPrimary, fontSize=18.sp); Text("Avg Att.", fontSize=10.sp, color=CreamTextSec, fontWeight=FontWeight.SemiBold) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (defaulters.isNotEmpty()) {
                        Text("Defaulters < $defaulterThreshold% (${defaulters.size})", fontWeight=FontWeight.Bold, color=AbsentRed, fontSize=12.sp)
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            defaulters.take(6).forEach { st ->
                                Row(verticalAlignment=Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AbsentBg.copy(alpha=0.5f)).border(1.dp, AbsentBorder.copy(alpha=0.4f), RoundedCornerShape(10.dp)).padding(horizontal=10.dp, vertical=7.dp)) {
                                    Box(modifier=Modifier.size(28.dp).clip(CircleShape).background(Color.White).border(1.dp, AbsentBorder, CircleShape), contentAlignment=Alignment.Center) {
                                        Text(st.roll, fontWeight=FontWeight.ExtraBold, color=AbsentRed, fontSize=10.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier=Modifier.weight(1f)) {
                                        Box(modifier=Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, CreamBorder, RoundedCornerShape(6.dp))) {
                                            Box(modifier=Modifier.fillMaxWidth((st.percent/100.0).toFloat().coerceIn(0f,1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(if (st.percent<defaulterThreshold.toDouble()) AbsentRed else PresentGreen))
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text("${st.present}/${st.total} • ${String.format(Locale.US,"%.1f%%", st.percent)}", fontSize=10.sp, color=CreamTextSec)
                                    }
                                    Icon(Icons.Filled.WarningAmber, null, tint=AbsentRed.copy(alpha=0.7f), modifier=Modifier.size(16.dp))
                                }
                            }
                            if (defaulters.size>6) Text("+ ${defaulters.size-6} more — tap View All in full list below", fontSize=10.sp, color=CreamTextSec, modifier=Modifier.padding(top=2.dp))
                        }
                    } else if (filteredForAnalytics.isNotEmpty()) {
                        Box(modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PresentBg).border(1.dp, PresentBorder, RoundedCornerShape(10.dp)).padding(12.dp), contentAlignment=Alignment.Center) {
                            Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Filled.Verified, null, tint=PresentGreen, modifier=Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("No defaulters — everyone ≥ $defaulterThreshold%", fontSize=11.sp, color=PresentGreen, fontWeight=FontWeight.SemiBold) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick=onGenerateFullExcel, modifier=Modifier.fillMaxWidth(), colors=ButtonDefaults.buttonColors(containerColor=CreamPrimary), shape=RoundedCornerShape(12.dp)) {
                        Icon(Icons.Filled.TableChart, null, modifier=Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Generate Full Excel (All Subjects)", fontWeight=FontWeight.Bold, fontSize=12.sp)
                    }
                    OutlinedButton(onClick=onGenerateExcel, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(12.dp), border=BorderStroke(1.dp, CreamBorder)) {
                        Icon(Icons.Filled.Description, null, modifier=Modifier.size(14.dp), tint=CreamText); Spacer(Modifier.width(6.dp)); Text("STE only (legacy)", fontSize=11.sp, color=CreamText)
                    }
                    Text("Full: 1 sheet per subject (OSY/DAN/STE/ENDS) × Lecture/Practical + Summary. Matrix P/A per roll.", fontSize=10.sp, color=CreamMuted, lineHeight=11.sp, modifier=Modifier.padding(top=4.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick={
                            if(history.isEmpty()) { Toast.makeText(ctx,"No records",Toast.LENGTH_SHORT).show(); } else {
                                scope.launch {
                                    try {
                                        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            if (analyticsSubject=="ALL") generateFullExcel(ctx, rolls, history) else generateSTEExcel(ctx, rolls, history)
                                        }
                                        if(f!=null) shareExcelFile(ctx, f)
                                    } catch (t: Throwable) { Toast.makeText(ctx,"Share failed: ${t.message}", Toast.LENGTH_LONG).show() }
                                }
                            }
                        }, shape=RoundedCornerShape(10.dp), modifier=Modifier.weight(1f)) { Icon(Icons.Filled.Share, null, modifier=Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Share", fontSize=10.sp) }
                        OutlinedButton(onClick=onClearHistory, shape=RoundedCornerShape(10.dp), border=BorderStroke(1.dp, AbsentBorder), modifier=Modifier.weight(1f)) { Icon(Icons.Filled.DeleteSweep, null, tint=AbsentRed, modifier=Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Clear All", fontSize=10.sp, color=AbsentRed) }
                    }
                }
            }
        }
        if (filteredForAnalytics.isNotEmpty() && rolls.isNotEmpty()) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(16.dp), border=BorderStroke(1.dp, CreamBorder), elevation=CardDefaults.cardElevation(2.dp)) {
                    Column(modifier=Modifier.padding(14.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Filled.PieChart, null, tint=CreamPrimary, modifier=Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text("Visuals", fontWeight=FontWeight.Bold, color=CreamText, fontSize=13.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${filteredForAnalytics.size} sessions", fontSize=10.sp, color=CreamTextSec, modifier=Modifier.background(CreamSurface, RoundedCornerShape(8.dp)).padding(horizontal=6.dp, vertical=3.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp), modifier=Modifier.fillMaxWidth()) {
                            val totalAbsentSlots = filteredForAnalytics.sumOf { it.absent.size }
                            val totalSlots = filteredForAnalytics.size * rolls.size.coerceAtLeast(1)
                            val totalPresentSlots = (totalSlots - totalAbsentSlots).coerceAtLeast(0)
                            val overallSlices = listOf(
                                PieSlice("Present", totalPresentSlots.toFloat(), PresentGreen),
                                PieSlice("Absent", totalAbsentSlots.toFloat(), AbsentRed.copy(alpha=0.85f))
                            )
                            Column(modifier=Modifier.weight(1f), horizontalAlignment=Alignment.CenterHorizontally) {
                                Box(modifier=Modifier.size(110.dp).clip(CircleShape).background(CreamSurface).border(1.dp, CreamBorder, CircleShape), contentAlignment=Alignment.Center) {
                                    PieChart(slices=overallSlices, modifier=Modifier.fillMaxSize().padding(6.dp), donutHole=0.55f)
                                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                        Text(String.format(Locale.US,"%.0f%%", if(totalSlots>0) totalPresentSlots*100f/totalSlots else 0f), fontWeight=FontWeight.ExtraBold, color=CreamText, fontSize=12.sp, lineHeight=12.sp)
                                        Text("present", fontSize=9.sp, color=CreamTextSec)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("Overall", fontWeight=FontWeight.Bold, color=CreamText, fontSize=11.sp)
                                PieLegend(slices=overallSlices, total=overallSlices.sumOf{it.value.toDouble()}.toFloat())
                            }
                            val le = filteredForAnalytics.count{ it.type=="Lecture" }.toFloat()
                            val pr = filteredForAnalytics.count{ it.type=="Practical" }.toFloat()
                            val typeSlices = listOf(
                                PieSlice("Lecture", le, PresentGreen.copy(alpha=0.9f)),
                                PieSlice("Practical", pr, AbsentRed.copy(alpha=0.9f))
                            )
                            Column(modifier=Modifier.weight(1f), horizontalAlignment=Alignment.CenterHorizontally) {
                                Box(modifier=Modifier.size(110.dp).clip(CircleShape).background(CreamSurface).border(1.dp, CreamBorder, CircleShape), contentAlignment=Alignment.Center) {
                                    if (le+pr==0f) Box(modifier=Modifier.fillMaxSize(), contentAlignment=Alignment.Center){ Text("No data", fontSize=10.sp, color=CreamMuted)} else {
                                        PieChart(slices=typeSlices, modifier=Modifier.fillMaxSize().padding(6.dp), donutHole=0.55f)
                                        Column(horizontalAlignment=Alignment.CenterHorizontally){
                                            Text("${le.toInt()}/${pr.toInt()}", fontWeight=FontWeight.Bold, color=CreamText, fontSize=11.sp)
                                            Text("L / P", fontSize=9.sp, color=CreamTextSec)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("Type split", fontWeight=FontWeight.Bold, color=CreamText, fontSize=11.sp)
                                PieLegend(slices=typeSlices, total=le+pr)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (subjectSummary.isNotEmpty() && analyticsSubject=="ALL") {
                            Text("Sessions by subject", fontWeight=FontWeight.Bold, color=CreamText, fontSize=11.sp)
                            Spacer(Modifier.height(8.dp))
                            val subjColors = mapOf("OSY" to SubjOSY, "DAN" to SubjDAN, "STE" to SubjSTE, "ENDS" to SubjENDS)
                            val subjSlices = subjectSummary.entries.sortedByDescending{ it.value.first+it.value.second }.map { (subj, pair) ->
                                val total = (pair.first + pair.second).toFloat()
                                PieSlice(subj, total, subjColors[subj] ?: CreamPrimary)
                            }
                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp), modifier=Modifier.fillMaxWidth()) {
                                Box(modifier=Modifier.size(120.dp).clip(CircleShape).background(CreamSurface).border(1.dp, CreamBorder, CircleShape), contentAlignment=Alignment.Center) {
                                    PieChart(slices=subjSlices, modifier=Modifier.fillMaxSize().padding(8.dp), donutHole=0.48f)
                                    Icon(Icons.Filled.Category, null, tint=CreamMuted, modifier=Modifier.size(20.dp))
                                }
                                Column(modifier=Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    PieLegend(slices=subjSlices, total=subjSlices.sumOf{it.value.toDouble()}.toFloat())
                                }
                            }
                        }
                        if (stats.any{ it.total>0 }) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color=CreamBorder, thickness=1.dp)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                val safe = (stats.count{ it.percent >= defaulterThreshold.toDouble() && it.total>0 }).toFloat()
                                val def = defaulters.size.toFloat()
                                val defSlices = listOf(
                                    PieSlice("Safe ≥${defaulterThreshold}%", safe, PresentGreen),
                                    PieSlice("Defaulters", def, AbsentRed)
                                )
                                Box(modifier=Modifier.size(100.dp).clip(CircleShape).background(CreamSurface).border(1.dp, CreamBorder, CircleShape), contentAlignment=Alignment.Center) {
                                    PieChart(slices=defSlices, modifier=Modifier.fillMaxSize().padding(6.dp), donutHole=0.58f)
                                    Column(horizontalAlignment=Alignment.CenterHorizontally){
                                        Text("${def.toInt()}", fontWeight=FontWeight.ExtraBold, color=AbsentRed, fontSize=14.sp, lineHeight=14.sp)
                                        Text("defaulters", fontSize=9.sp, color=CreamTextSec)
                                    }
                                }
                                Column(modifier=Modifier.weight(1f)) {
                                    Text("Defaulter ratio (≥${defaulterThreshold}%)", fontWeight=FontWeight.Bold, color=CreamText, fontSize=11.sp)
                                    Spacer(Modifier.height(4.dp))
                                    PieLegend(slices=defSlices, total=safe+def)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (stats.isNotEmpty() && filteredForAnalytics.isNotEmpty()) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(14.dp), border=BorderStroke(1.dp,CreamBorder)) {
                    Column(modifier=Modifier.padding(10.dp)) {
                        Row(modifier=Modifier.fillMaxWidth().padding(bottom=6.dp), horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Roll", fontSize=10.sp, fontWeight=FontWeight.Bold, color=CreamTextSec, modifier=Modifier.width(48.dp))
                            Text("Present", fontSize=10.sp, fontWeight=FontWeight.Bold, color=CreamTextSec, modifier=Modifier.width(56.dp), textAlign=TextAlign.Center)
                            Text("Attendance", fontSize=10.sp, fontWeight=FontWeight.Bold, color=CreamTextSec, modifier=Modifier.weight(1f), textAlign=TextAlign.Center)
                            Text("%", fontSize=10.sp, fontWeight=FontWeight.Bold, color=CreamTextSec, modifier=Modifier.width(48.dp), textAlign=TextAlign.End)
                        }
                        HorizontalDivider(color=CreamBorder, thickness=1.dp)
                        Spacer(Modifier.height(6.dp))
                        stats.forEach { st ->
                            val isDefaulter = st.percent < defaulterThreshold.toDouble() && st.total>0
                            Row(verticalAlignment=Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                                Box(modifier=Modifier.width(44.dp).clip(RoundedCornerShape(8.dp)).background(if(isDefaulter) AbsentBg else CreamSurface).border(1.dp, if(isDefaulter) AbsentBorder else CreamBorder, RoundedCornerShape(8.dp)).padding(vertical=4.dp), contentAlignment=Alignment.Center) {
                                    Text(st.roll, fontSize=11.sp, fontWeight=FontWeight.ExtraBold, color=if(isDefaulter) AbsentRed else CreamText)
                                }
                                Text("${st.present}/${st.total}", fontSize=11.sp, color=CreamTextSec, modifier=Modifier.width(56.dp), textAlign=TextAlign.Center, fontWeight=FontWeight.SemiBold)
                                Box(modifier=Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(CreamSurface).border(1.dp, CreamBorder, RoundedCornerShape(6.dp))) {
                                    Box(modifier=Modifier.fillMaxWidth((st.percent/100.0).toFloat().coerceIn(0f,1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(if(isDefaulter) AbsentRed else PresentGreen))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(String.format(Locale.US,"%.0f%%", st.percent), fontSize=11.sp, fontWeight=FontWeight.Bold, color=if(isDefaulter) AbsentRed else PresentGreen, modifier=Modifier.width(44.dp), textAlign=TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column {
                Text("History (${history.size} records)", fontWeight=FontWeight.Bold, color=CreamText, fontSize=13.sp)
                Text("Tap pencil to edit • Lecture/Practical kept separate.", fontSize=10.sp, color=CreamTextSec)
                Spacer(Modifier.height(8.dp))
                if(history.isEmpty()){
                    Card(colors=CardDefaults.cardColors(containerColor=CreamSurface), shape=RoundedCornerShape(12.dp), border=BorderStroke(1.dp,CreamBorder), modifier=Modifier.fillMaxWidth()){
                        Column(modifier=Modifier.padding(18.dp), horizontalAlignment=Alignment.CenterHorizontally){
                            Icon(Icons.Filled.History, null, tint=CreamMuted, modifier=Modifier.size(28.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("No attendance saved yet", color=CreamText, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                            Text("Take attendance → Preview → Save & Send", fontSize=11.sp, color=CreamTextSec)
                        }
                    }
                }
            }
        }

        if (historySorted.isEmpty()) {
            // already handled empty card above, no items
        } else {
            items(historySorted, key={it.id}) { rec ->
                Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(14.dp), border=BorderStroke(1.dp, if(rec.type=="Practical") AbsentBorder else CreamBorder), elevation=CardDefaults.cardElevation(1.dp), modifier=Modifier.fillMaxWidth()) {
                    Row(modifier=Modifier.padding(12.dp), verticalAlignment=Alignment.Top){
                        Box(modifier=Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(if(rec.type=="Practical") AbsentBg else PresentBg).border(1.dp, if(rec.type=="Practical") AbsentBorder else PresentBorder, RoundedCornerShape(10.dp)), contentAlignment=Alignment.Center){
                            Text(if(rec.type=="Practical") "PR" else "LE", fontWeight=FontWeight.ExtraBold, fontSize=10.sp, color=if(rec.type=="Practical") AbsentRed else PresentGreen)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier=Modifier.weight(1f)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Box(modifier=Modifier.clip(RoundedCornerShape(6.dp)).background(CreamPrimary).padding(horizontal=6.dp, vertical=2.dp)) { Text(rec.subject, fontWeight=FontWeight.Bold, color=Color.White, fontSize=11.sp) }
                                Spacer(Modifier.width(6.dp))
                                Text("${rec.type}${if(rec.coGroup!=null) " • ${rec.coGroup}" else ""}", fontSize=10.sp, color=CreamTextSec, modifier=Modifier.background(CreamSurface, RoundedCornerShape(6.dp)).padding(horizontal=6.dp, vertical=2.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("${rec.absent.size} absent", fontSize=10.sp, color=AbsentRed, fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${rec.date} • ${rec.time} • ${rec.header}", fontSize=11.sp, color=CreamTextSec, maxLines=2, overflow=TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Box(modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CreamSurface).border(1.dp, CreamBorder, RoundedCornerShape(8.dp)).padding(horizontal=8.dp, vertical=6.dp)) {
                                Text("Absent: ${if(rec.absent.isEmpty()) "NIL" else rec.absent.joinToString(", ")}", fontSize=11.sp, color=CreamText, lineHeight=13.sp)
                            }
                        }
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            IconButton(onClick={onEditRecord(rec)}, modifier=Modifier.size(32.dp).clip(CircleShape).background(CreamSurface).border(1.dp, CreamBorder, CircleShape)) { Icon(Icons.Filled.Edit, null, tint=CreamPrimary, modifier=Modifier.size(14.dp)) }
                            Spacer(Modifier.height(4.dp))
                            IconButton(onClick={onDeleteRecord(rec.id)}, modifier=Modifier.size(30.dp).clip(CircleShape).background(AbsentBg).border(1.dp, AbsentBorder, CircleShape)) { Icon(Icons.Filled.DeleteOutline, null, tint=AbsentRed, modifier=Modifier.size(14.dp)) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
fun SettingsScreen(
    rolls: List<String>,
    onRollsChange: (MutableList<String>)->Unit,
    timetable: List<DaySchedule>,
    onTimetableChange: (MutableList<DaySchedule>)->Unit,
    subjects: List<String>,
    onSubjectsChange: (MutableList<String>)->Unit,
    onManageRolls: ()->Unit,
    onManageTt: ()->Unit,
    onManageSubjects: ()->Unit,
    selectedTheme: AppTheme = ThemeManager.current,
    onThemeChange: (AppTheme)->Unit = {}
) {
    Column(modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text("Settings", fontWeight=FontWeight.Bold, color=CreamText, fontSize=16.sp)
        Text("Customize grid, timetable, subjects — saved instantly", fontSize=11.sp, color=CreamTextSec)
        Spacer(Modifier.height(12.dp))

        SettingsCard(title="Roll Grid", subtitle="${rolls.size} rolls • 1-63,81-87", icon=Icons.Filled.GridView, onClick=onManageRolls, accent=CreamPrimary)
        SettingsCard(title="Time-Table", subtitle="${timetable.size} days • Room DB", icon=Icons.Filled.CalendarViewWeek, onClick=onManageTt, accent=Color(0xFF5D4037))
        SettingsCard(title="Subjects", subtitle="${subjects.joinToString(" • ")}", icon=Icons.Filled.MenuBook, onClick=onManageSubjects, accent=Color(0xFF2E7D32))

        Spacer(Modifier.height(14.dp))
        // Theme Picker - distinctive, signature element
        Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(16.dp), border=BorderStroke(1.dp, CreamBorder), elevation=CardDefaults.cardElevation(2.dp)) {
            Column(modifier=Modifier.padding(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(modifier=Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(CreamPrimary.copy(alpha=0.12f)), contentAlignment=Alignment.Center) { Icon(Icons.Filled.Palette, null, tint=CreamPrimary, modifier=Modifier.size(18.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier=Modifier.weight(1f)) {
                        Text("Color Themes", fontWeight=FontWeight.Bold, color=CreamText, fontSize=13.sp)
                        Text("Tap to switch — Room data stays", fontSize=10.sp, color=CreamTextSec)
                    }
                    Box(modifier=Modifier.clip(RoundedCornerShape(20.dp)).background(CreamAccentLight).padding(horizontal=8.dp, vertical=4.dp)) { Text(selectedTheme.name, fontSize=10.sp, color=CreamPrimaryDark, fontWeight=FontWeight.Bold, maxLines=1) }
                }
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    AppThemes.all.forEach { theme ->
                        val isSelected = theme.id == selectedTheme.id
                        Card(
                            modifier=Modifier.fillMaxWidth().clickable { onThemeChange(theme) },
                            colors=CardDefaults.cardColors(containerColor= if(isSelected) theme.accentLight else CreamSurface),
                            shape=RoundedCornerShape(12.dp),
                            border=BorderStroke(1.5.dp, if(isSelected) theme.primary else CreamBorder)
                        ) {
                            Row(modifier=Modifier.padding(10.dp), verticalAlignment=Alignment.CenterVertically) {
                                // Color dots preview
                                Row(horizontalArrangement=Arrangement.spacedBy(4.dp), verticalAlignment=Alignment.CenterVertically, modifier=Modifier.width(72.dp)) {
                                    Box(modifier=Modifier.size(22.dp).clip(CircleShape).background(theme.primary).border(1.dp, theme.border, CircleShape))
                                    Box(modifier=Modifier.size(16.dp).clip(CircleShape).background(theme.bg).border(1.dp, theme.border, CircleShape))
                                    Box(modifier=Modifier.size(16.dp).clip(CircleShape).background(theme.card).border(1.dp, theme.border, CircleShape))
                                }
                                Column(modifier=Modifier.weight(1f)) {
                                    Text(theme.name, fontWeight=FontWeight.Bold, color=theme.text, fontSize=12.sp, maxLines=1)
                                    Text(theme.subtitle, fontSize=10.sp, color=theme.textSec, maxLines=1)
                                }
                                if (isSelected) {
                                    Box(modifier=Modifier.size(28.dp).clip(CircleShape).background(theme.primary), contentAlignment=Alignment.Center) {
                                        Icon(Icons.Filled.Check, null, tint=Color.White, modifier=Modifier.size(16.dp))
                                    }
                                } else {
                                    Box(modifier=Modifier.size(28.dp).clip(CircleShape).background(Color.Transparent).border(1.dp, CreamBorder, CircleShape), contentAlignment=Alignment.Center) {
                                        Icon(Icons.Filled.ChevronRight, null, tint=CreamMuted, modifier=Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Midnight is dark mode — great for OLED. Others keep paper-like clarity.", fontSize=10.sp, color=CreamMuted, lineHeight=11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Backup & Restore - polished card with cream + subtle shadow
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var lastBackup by remember { mutableStateOf<String?>(null) }
        Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(16.dp), border=BorderStroke(1.dp,CreamBorder), elevation=CardDefaults.cardElevation(2.dp)) {
            Column(modifier=Modifier.padding(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(modifier=Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(CreamPrimary.copy(alpha=0.12f)), contentAlignment=Alignment.Center) { Icon(Icons.Filled.Backup, null, tint=CreamPrimary, modifier=Modifier.size(18.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier=Modifier.weight(1f)) {
                        Text("Backup & Restore", fontWeight=FontWeight.Bold, color=CreamText, fontSize=13.sp)
                        Text("SQLite .db → Download/AttendanceDesk/Backups", fontSize=10.sp, color=CreamTextSec)
                    }
                    Box(modifier=Modifier.clip(RoundedCornerShape(20.dp)).background(PresentBg).padding(horizontal=8.dp, vertical=4.dp)) { Text("Room v2", fontSize=10.sp, color=PresentGreen, fontWeight=FontWeight.Bold) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                    Button(onClick={
                        val f = com.attendance.desk.data.BackupHelper.backupDatabase(ctx)
                        if (f!=null) {
                            lastBackup = f.name
                            android.widget.Toast.makeText(ctx, "Backup saved: ${f.name}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }, shape=RoundedCornerShape(10.dp), colors=ButtonDefaults.buttonColors(containerColor=CreamPrimary), modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=10.dp)) {
                        Icon(Icons.Filled.Save, null, modifier=Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Backup Now", fontSize=11.sp, fontWeight=FontWeight.Bold)
                    }
                    OutlinedButton(onClick={
                        val f = com.attendance.desk.data.BackupHelper.backupDatabase(ctx)
                        if (f!=null) com.attendance.desk.data.BackupHelper.shareBackupFile(ctx, f)
                    }, shape=RoundedCornerShape(10.dp), modifier=Modifier.weight(1f), contentPadding=PaddingValues(vertical=10.dp)) {
                        Icon(Icons.Filled.Share, null, modifier=Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Share .db", fontSize=11.sp)
                    }
                }
                if (lastBackup!=null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Last: $lastBackup", fontSize=10.sp, color=PresentGreen, fontWeight=FontWeight.SemiBold, modifier=Modifier.background(PresentBg, RoundedCornerShape(6.dp)).padding(horizontal=6.dp, vertical=3.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("To restore: place .db in app folder or use file picker (coming). Reinstall keeps prefs → Room migration handles it.", fontSize=10.sp, color=CreamMuted, lineHeight=11.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(colors=CardDefaults.cardColors(containerColor=CreamCard), shape=RoundedCornerShape(14.dp), border=BorderStroke(1.dp,CreamBorder)) {
            Column(modifier=Modifier.padding(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Filled.Palette, null, tint=CreamPrimary, modifier=Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cream Theme", fontWeight=FontWeight.Bold, color=CreamText, fontSize=13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Warm paper #FFFBF0 • brown #A67C52 • peach absent, mint present. Optimized for CR quick glances.", fontSize=11.sp, color=CreamTextSec, lineHeight=13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(CreamBg, CreamSurface, CreamPrimary, CreamPrimaryDark, CreamSecondary).forEach { c ->
                        Box(modifier=Modifier.size(26.dp).clip(CircleShape).background(c).border(1.dp, CreamBorder, CircleShape))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Card(colors=CardDefaults.cardColors(containerColor=CreamSurface), shape=RoundedCornerShape(14.dp), border=BorderStroke(1.dp,CreamBorder)) {
            Column(modifier=Modifier.padding(12.dp)) {
                Text("WhatsApp flow", fontWeight=FontWeight.SemiBold, color=CreamText, fontSize=12.sp)
                Spacer(Modifier.height(4.dp))
                Text("Preview → Edit any field → Send to WhatsApp (text pre-filled) → pick the subject’s WhatsApp group manually. No group linking needed.", fontSize=11.sp, color=CreamTextSec, lineHeight=13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors=CardDefaults.cardColors(containerColor=CreamAccentLight.copy(alpha=0.5f)), shape=RoundedCornerShape(14.dp), border=BorderStroke(1.dp,CreamBorder)) {
            Column(modifier=Modifier.padding(12.dp)) {
                Text("Tips for CR speed", fontWeight=FontWeight.SemiBold, color=CreamText, fontSize=12.sp)
                Text("• Use Filter to jump to a roll\n• Invert to flip present/absent quickly\n• Tap timetable slot to auto-set time+subject\n• Batch dropdown keeps header consistent", fontSize=11.sp, color=CreamTextSec, lineHeight=13.sp)
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun SettingsCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: ()->Unit, accent: Color) {
    Card(
        modifier=Modifier.fillMaxWidth().padding(bottom=8.dp).clickable{ onClick() },
        colors=CardDefaults.cardColors(containerColor=CreamCard),
        shape=RoundedCornerShape(14.dp),
        border=BorderStroke(1.dp, CreamBorder)
    ) {
        Row(modifier=Modifier.padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(modifier=Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha=0.12f)), contentAlignment=Alignment.Center) {
                Icon(icon, null, tint=accent, modifier=Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier=Modifier.weight(1f)) {
                Text(title, fontWeight=FontWeight.Bold, color=CreamText, fontSize=13.sp)
                Text(subtitle, fontSize=11.sp, color=CreamTextSec, maxLines=1, overflow=TextOverflow.Ellipsis)
            }
            Box(modifier=Modifier.size(28.dp).clip(CircleShape).background(CreamSurface).border(1.dp,CreamBorder,CircleShape), contentAlignment=Alignment.Center) {
                Icon(Icons.Filled.ChevronRight, null, tint=CreamMuted, modifier=Modifier.size(16.dp))
            }
        }
    }
}

// ---------- Editor Dialogs ----------
@Composable
fun RollEditorDialog(rolls: List<String>, onSave: (MutableList<String>)->Unit, onDismiss: ()->Unit) {
    var list by remember { mutableStateOf(rolls.toMutableList()) }
    var newRoll by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=CreamCard,
        title={ Text("Edit Roll Grid", fontWeight=FontWeight.Bold, color=CreamText, fontSize=15.sp) },
        text={
            Column(modifier=Modifier.verticalScroll(rememberScrollState())) {
                Text("Add custom names or numbers. Default 73 rolls.", fontSize=11.sp, color=CreamTextSec)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value=newRoll, onValueChange={newRoll=it}, label={Text("Add roll")}, modifier=Modifier.weight(1f), shape=RoundedCornerShape(10.dp), singleLine=true, textStyle= androidx.compose.ui.text.TextStyle(fontSize=13.sp))
                    IconButton(onClick={
                        if(newRoll.isNotBlank() && newRoll !in list){ list = (list + newRoll.trim()).toMutableList(); newRoll="" }
                    }, modifier=Modifier.size(42.dp).clip(CircleShape).background(CreamPrimary)) { Icon(Icons.Filled.Add, null, tint=Color.White, modifier=Modifier.size(18.dp)) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick={ list = defaultRolls() }, shape=RoundedCornerShape(10.dp), modifier=Modifier.weight(1f), contentPadding=PaddingValues(4.dp)) { Text("Reset", fontSize=11.sp) }
                    OutlinedButton(onClick={ list = mutableListOf() }, shape=RoundedCornerShape(10.dp), modifier=Modifier.weight(1f), contentPadding=PaddingValues(4.dp)) { Text("Clear", fontSize=11.sp) }
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    list.chunked(4).forEach { row ->
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            row.forEach { r ->
                                Card(
                                    colors=CardDefaults.cardColors(containerColor=CreamSurface),
                                    border=BorderStroke(1.dp, CreamBorder),
                                    shape=RoundedCornerShape(20.dp)
                                ) {
                                    Row(modifier=Modifier.padding(horizontal=10.dp, vertical=6.dp), verticalAlignment=Alignment.CenterVertically) {
                                        Text(r, fontSize=11.sp, fontWeight=FontWeight.Medium, color=CreamText)
                                        Spacer(Modifier.width(6.dp))
                                        Box(modifier=Modifier.size(18.dp).clip(CircleShape).background(CreamBorder).clickable{
                                            list = list.filter{ it!=r }.toMutableList()
                                        }, contentAlignment=Alignment.Center){
                                            Icon(Icons.Filled.Close, null, modifier=Modifier.size(12.dp), tint=CreamText)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if(list.isEmpty()) Text("Add at least one roll", color=AbsentRed, fontSize=11.sp, modifier=Modifier.padding(top=8.dp))
            }
        },
        confirmButton={ Button(onClick={ onSave(list) }, colors=ButtonDefaults.buttonColors(containerColor=CreamPrimary), shape=RoundedCornerShape(10.dp)){ Text("Save") } },
        dismissButton={ TextButton(onClick=onDismiss){ Text("Cancel") } }
    )
}

@Composable
fun TtEditorDialog(timetable: List<DaySchedule>, onSave: (MutableList<DaySchedule>)->Unit, onDismiss: ()->Unit) {
    var editable by remember { mutableStateOf(timetable.map{ it.copy(slots=it.slots.map{ s-> s.copy() }.toMutableList()) }.toMutableList()) }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=CreamCard,
        title={ Text("Edit Time-Table", fontWeight=FontWeight.Bold, color=CreamText, fontSize=15.sp) },
        text={
            Column(modifier=Modifier.verticalScroll(rememberScrollState()).heightIn(max=420.dp)) {
                Text("Edit each slot’s text.", fontSize=11.sp, color=CreamTextSec)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick={ editable = defaultTimetable().map{ it.copy(slots=it.slots.toMutableList()) }.toMutableList() }, shape=RoundedCornerShape(10.dp), contentPadding=PaddingValues(horizontal=12.dp, vertical=6.dp)) { Text("Reset default", fontSize=11.sp) }
                Spacer(Modifier.height(8.dp))
                editable.forEachIndexed { di, day ->
                    Text(day.day, fontWeight=FontWeight.Bold, color=CreamPrimary, fontSize=12.sp, modifier=Modifier.padding(vertical=6.dp))
                    day.slots.forEachIndexed { si, slot ->
                        var text by remember(slot) { mutableStateOf(slot.subject) }
                        OutlinedTextField(
                            value=text,
                            onValueChange={ text=it; editable[di].slots[si] = slot.copy(subject=it) },
                            label={ Text(slot.time, fontSize=10.sp) },
                            modifier=Modifier.fillMaxWidth().padding(bottom=6.dp),
                            shape=RoundedCornerShape(10.dp),
                            minLines=1,
                            textStyle= androidx.compose.ui.text.TextStyle(fontSize=12.sp)
                        )
                    }
                    HorizontalDivider(color=CreamBorder, thickness=1.dp, modifier=Modifier.padding(vertical=6.dp))
                }
            }
        },
        confirmButton={ Button(onClick={ onSave(editable) }, colors=ButtonDefaults.buttonColors(containerColor=CreamPrimary), shape=RoundedCornerShape(10.dp)){ Text("Save TT") } },
        dismissButton={ TextButton(onClick=onDismiss){ Text("Cancel") } }
    )
}

@Composable
fun SubjectEditorDialog(subjects: List<String>, onSave: (MutableList<String>)->Unit, onDismiss: ()->Unit) {
    var list by remember { mutableStateOf(subjects.toMutableList()) }
    var newSub by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=CreamCard,
        title={ Text("Subjects", fontWeight=FontWeight.Bold, color=CreamText, fontSize=15.sp) },
        text={
            Column(modifier=Modifier.verticalScroll(rememberScrollState())) {
                Text("Lecture names for dropdown. e.g., CGR, OSY.", fontSize=11.sp, color=CreamTextSec)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=newSub, onValueChange={newSub=it.uppercase()}, label={Text("New subject")}, modifier=Modifier.weight(1f), shape=RoundedCornerShape(10.dp), singleLine=true, textStyle= androidx.compose.ui.text.TextStyle(fontSize=13.sp))
                    IconButton(onClick={ if(newSub.isNotBlank() && newSub.trim().uppercase() !in list){ list = (list + newSub.trim().uppercase()).toMutableList(); newSub="" } }, modifier=Modifier.size(42.dp).clip(CircleShape).background(CreamPrimary)) { Icon(Icons.Filled.Add, null, tint=Color.White, modifier=Modifier.size(18.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                list.forEach { s ->
                    Card(modifier=Modifier.fillMaxWidth().padding(vertical=3.dp), colors=CardDefaults.cardColors(containerColor=CreamSurface), border=BorderStroke(1.dp,CreamBorder), shape=RoundedCornerShape(10.dp)) {
                        Row(modifier=Modifier.padding(horizontal=10.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                            Box(modifier=Modifier.size(30.dp).clip(CircleShape).background(CreamAccentLight), contentAlignment=Alignment.Center){ Text(s.take(2), fontWeight=FontWeight.Bold, color=CreamText, fontSize=11.sp) }
                            Spacer(Modifier.width(10.dp))
                            Text(s, fontWeight=FontWeight.SemiBold, color=CreamText, modifier=Modifier.weight(1f), fontSize=13.sp)
                            IconButton(onClick={ list = list.filter{ it!=s }.toMutableList() }, modifier=Modifier.size(28.dp)) { Icon(Icons.Filled.DeleteOutline, null, tint=AbsentRed, modifier=Modifier.size(16.dp)) }
                        }
                    }
                }
                if(list.isEmpty()) Text("Add at least one subject", color=AbsentRed, fontSize=11.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick={ list = defaultSubjects() }, shape=RoundedCornerShape(10.dp), modifier=Modifier.fillMaxWidth()) { Text("Reset defaults", fontSize=11.sp) }
            }
        },
        confirmButton={ Button(onClick={ onSave(list) }, colors=ButtonDefaults.buttonColors(containerColor=CreamPrimary), shape=RoundedCornerShape(10.dp)){ Text("Save") } },
        dismissButton={ TextButton(onClick=onDismiss){ Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRecordDialog(record: AttendanceRecord, subjects: List<String>, rolls: List<String>, onDismiss: ()->Unit, onSave: (AttendanceRecord)->Unit) {
    var date by remember { mutableStateOf(record.date) }
    var time by remember { mutableStateOf(record.time) }
    var subject by remember { mutableStateOf(record.subject) }
    var type by remember { mutableStateOf(record.type) }
    var co by remember { mutableStateOf(record.coGroup ?: "CO1") }
    var header by remember { mutableStateOf(record.header) }
    var absentSet by remember { mutableStateOf(record.absent.toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CreamCard,
        title = { Text("Edit Attendance", fontWeight=FontWeight.Bold, color=CreamText, fontSize=15.sp) },
        text = {
            Column(modifier=Modifier.verticalScroll(rememberScrollState()).heightIn(max=520.dp)) {
                Text("Update details. Batch auto-locks Lecture/Practical.", fontSize=11.sp, color=CreamTextSec)
                Spacer(Modifier.height(8.dp))
                var headerExp by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded=headerExp, onExpandedChange={headerExp=!headerExp}, modifier=Modifier.fillMaxWidth()) {
                    OutlinedTextField(value=header, onValueChange={}, readOnly=true, label={Text("Batch / Header", fontSize=10.sp)}, modifier=Modifier.menuAnchor().fillMaxWidth(), trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(headerExp)}, shape=RoundedCornerShape(10.dp), singleLine=true)
                    ExposedDropdownMenu(expanded=headerExp, onDismissRequest={headerExp=false}) {
                        batchOptions.forEach { b ->
                            DropdownMenuItem(text={Text(b)}, onClick={
                                header=b
                                if(isBatchPractical(b)){
                                    type="Practical"
                                    co=batchToCo(b)
                                } else {
                                    type="Lecture"
                                }
                                headerExp=false
                            })
                        }
                    }
                }
                val isBatchHeader = isBatchPractical(header)
                LaunchedEffect(header) {
                    val expected = if(isBatchHeader) "Practical" else "Lecture"
                    if(type != expected) type = expected
                    if(isBatchHeader) {
                        val expectedCo = batchToCo(header)
                        if(co != expectedCo) co = expectedCo
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value=date, onValueChange={date=it}, label={Text("Date", fontSize=10.sp)}, modifier=Modifier.weight(1f), shape=RoundedCornerShape(10.dp), singleLine=true, leadingIcon={Icon(Icons.Filled.CalendarToday, null, modifier=Modifier.size(14.dp), tint=CreamMuted)})
                    OutlinedTextField(value=time, onValueChange={time=it}, label={Text("Time", fontSize=10.sp)}, modifier=Modifier.weight(1f), shape=RoundedCornerShape(10.dp), singleLine=true, leadingIcon={Icon(Icons.Filled.AccessTime, null, modifier=Modifier.size(14.dp), tint=CreamMuted)})
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()) {
                    var subjExp by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded=subjExp, onExpandedChange={subjExp=!subjExp}, modifier=Modifier.weight(1f)) {
                        OutlinedTextField(value=subject, onValueChange={}, readOnly=true, label={Text("Subject", fontSize=10.sp)}, modifier=Modifier.menuAnchor().fillMaxWidth(), trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(subjExp)}, shape=RoundedCornerShape(10.dp), singleLine=true)
                        ExposedDropdownMenu(expanded=subjExp, onDismissRequest={subjExp=false}) { subjects.forEach { s-> DropdownMenuItem(text={Text(s)}, onClick={subject=s; subjExp=false}) } }
                    }
                    Box(modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(if(isBatchHeader) AbsentBg else PresentBg).border(1.dp, if(isBatchHeader) AbsentBorder else PresentBorder, RoundedCornerShape(8.dp)).padding(horizontal=8.dp, vertical=7.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            Icon(if(isBatchHeader) Icons.Filled.Handyman else Icons.Filled.MenuBook, null, modifier=Modifier.size(12.dp), tint=if(isBatchHeader) AbsentRed else PresentGreen)
                            Text(if(isBatchHeader) "Practical" else "Lecture", fontSize=10.sp, fontWeight=FontWeight.Bold, color=if(isBatchHeader) AbsentRed else PresentGreen)
                            if(isBatchHeader) Text("• $co", fontSize=10.sp, fontWeight=FontWeight.Bold, color=AbsentRed)
                        }
                    }
                }
                if (isBatchHeader) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Lock, null, modifier=Modifier.size(10.dp), tint=CreamMuted)
                        Text("Auto by $header — locked to Practical • $co", fontSize=9.sp, color=CreamMuted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Absent — tap to toggle (${absentSet.size})", fontSize=11.sp, fontWeight=FontWeight.Bold, color=CreamText)
                Spacer(Modifier.height(6.dp))
                // Mini grid for absent selection
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                    modifier = Modifier.heightIn(max=180.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    gridItems(rolls) { r ->
                        val isAbsent = absentSet.contains(r)
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if(isAbsent) AbsentBg else Color.White).border(1.dp, if(isAbsent) AbsentBorder else CreamBorder, RoundedCornerShape(8.dp)).clickable {
                                val ns = absentSet.toMutableSet()
                                if (ns.contains(r)) ns.remove(r) else ns.add(r)
                                absentSet = ns
                            }.padding(vertical=6.dp), contentAlignment=Alignment.Center
                        ) {
                            Text(r, fontSize=11.sp, fontWeight=FontWeight.Bold, color=if(isAbsent) AbsentRed else CreamText)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick={absentSet = mutableSetOf()}, shape=RoundedCornerShape(8.dp), modifier=Modifier.weight(1f), contentPadding=PaddingValues(4.dp)) { Text("All Present", fontSize=10.sp) }
                    OutlinedButton(onClick={absentSet = rolls.toMutableSet()}, shape=RoundedCornerShape(8.dp), modifier=Modifier.weight(1f), contentPadding=PaddingValues(4.dp)) { Text("All Absent", fontSize=10.sp) }
                }
            }
        },
        confirmButton = {
            Button(onClick={
                val updated = record.copy(date=date, time=time, subject=subject, type=type, coGroup=if(type=="Practical") co else null, header=header, absent=absentSet.toList().sortedWith(compareBy({it.toIntOrNull()?:9999},{it})))
                onSave(updated)
            }, colors=ButtonDefaults.buttonColors(containerColor=CreamPrimary), shape=RoundedCornerShape(10.dp)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick=onDismiss){ Text("Cancel") } }
    )
}
