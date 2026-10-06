package com.attendance.desk.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupHelper {
    fun backupDatabase(context: Context): File? {
        return try {
            val dbFile = context.getDatabasePath("attendance_desk.db")
            if (!dbFile.exists()) {
                Toast.makeText(context, "No database file found", Toast.LENGTH_SHORT).show()
                return null
            }
            // Ensure WAL checkpoint
            try {
                AttendanceDatabase.getInstance(context).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
            } catch (_: Exception) {}
            val cacheCopy = File(context.cacheDir, "attendance_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.db")
            dbFile.copyTo(cacheCopy, overwrite = true)
            // Also copy wal/shm if exist
            val wal = File(dbFile.path + "-wal")
            val shm = File(dbFile.path + "-shm")
            // ignore wal/shm for simple backup; the checkpoint should have merged

            // Save to Downloads via MediaStore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, cacheCopy.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-sqlite3")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/AttendanceDesk/Backups")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        cacheCopy.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dir = File(dl, "AttendanceDesk/Backups")
                dir.mkdirs()
                val out = File(dir, cacheCopy.name)
                cacheCopy.copyTo(out, overwrite = true)
            }
            // Also keep in app external files for sharing
            val extDir = context.getExternalFilesDir(null) ?: context.filesDir
            val extCopy = File(extDir, cacheCopy.name)
            cacheCopy.copyTo(extCopy, overwrite = true)
            cacheCopy
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    fun shareBackupFile(context: Context, file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/x-sqlite3"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share Backup"))
        } catch (e: Exception) {
            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Simple restore: expects a .db file picked via file picker; caller should copy to database path and reopen
    fun restoreFromFile(context: Context, sourceFile: File): Boolean {
        return try {
            val dbFile = context.getDatabasePath("attendance_desk.db")
            // Close DB
            AttendanceDatabase.closeInstance()
            // Copy
            sourceFile.copyTo(dbFile, overwrite = true)
            // Remove wal/shm if stale
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

// Extension to close singleton for restore
fun AttendanceDatabase.Companion.closeInstance() {
    try {
        val field = AttendanceDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        val instance = field.get(null) as? AttendanceDatabase
        instance?.close()
        field.set(null, null)
    } catch (_: Exception) {}
}
