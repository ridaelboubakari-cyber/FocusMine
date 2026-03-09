package com.reda.focusmine.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

// ══════════════════════════════════════════════════════════════
// ChronicleBackup — Zero-backend, zero-friction auto-backup
//
// Strategy:
//   - Writes a JSON file to the user's Downloads folder
//   - Uses MediaStore on Android 10+ (Scoped Storage)
//   - Uses legacy path on Android 9 and below
//   - Called silently after every session insert
//   - Restore checked on first launch if DB is empty
//
// Survival guarantee:
//   The backup file lives in Downloads — it survives
//   app uninstall, data clear, and factory reset
//   (as long as the user does not delete it manually).
// ══════════════════════════════════════════════════════════════

object ChronicleBackup {

    private const val TAG             = "ChronicleBackup"
    private const val BACKUP_FILENAME = "focusmine_chronicle.json"
    private const val BACKUP_VERSION  = 1

    // ══════════════════════════════════════════════════════════
    // AUTO-BACKUP — call after every session save
    // ══════════════════════════════════════════════════════════

    suspend fun autoBackup(
        context: Context,
        dao: SessionDao,
        prefs: SharedPreferences
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sessions = dao.getAllSessionsForCapsule()
            val json     = buildJson(sessions, prefs)
            writeToDownloads(context, json)
            Log.d(TAG, "Backup successful — ${sessions.size} sessions")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}")
            false
        }
    }

    // ══════════════════════════════════════════════════════════
    // RESTORE — call on first launch if DB is empty
    // ══════════════════════════════════════════════════════════

    suspend fun restoreIfNeeded(
        context: Context,
        dao: SessionDao,
        prefs: SharedPreferences
    ): RestoreResult = withContext(Dispatchers.IO) {

        try {
            // Only restore if DB is completely empty
            val existingCount = dao.getTotalCount()
            if (existingCount > 0) {
                return@withContext RestoreResult.NotNeeded
            }

            val json = readFromDownloads(context)
                ?: return@withContext RestoreResult.NoBackupFound

            val root    = JSONObject(json)
            val version = root.optInt("version", 1)

            // Restore user preferences
            val userName = root.optString("userName", "")
            val lifeGoal = root.optString("lifeGoal", "")
            val goalDate = root.optLong("goalDate", 0L)
            val mode     = root.optString(
                "operativeMode",
                OperativeMode.OPERATIVE.name
            )

            prefs.edit()
                .putString("user_name",      userName)
                .putString("life_goal",      lifeGoal)
                .putLong("goal_date",        goalDate)
                .putString("operative_mode", mode)
                .apply()

            // Restore sessions
            val sessionsArr = root.getJSONArray("sessions")
            val restored    = mutableListOf<FocusSession>()

            for (i in 0 until sessionsArr.length()) {
                val s = sessionsArr.getJSONObject(i)
                restored.add(
                    FocusSession(
                        startTime         = s.getLong("startTime"),
                        durationMs        = s.getLong("durationMs"),
                        isSuccess         = s.getBoolean("isSuccess"),
                        wasCompromised    = s.optBoolean("wasCompromised", false),
                        appLeftCount      = s.optInt("appLeftCount", 0),
                        microGoal         = s.optString("microGoal", ""),
                        exitExcuse        = s.optString("exitExcuse", ""),
                        emotionalSnapshot = s.optString("emotionalSnapshot", ""),
                        compromisedAtMs   = s.optLong("compromisedAtMs", 0L),
                        operativeMode     = s.optString(
                            "operativeMode",
                            OperativeMode.OPERATIVE.name
                        ),
                        weekNumber        = s.optInt(
                            "weekNumber",
                            getCurrentWeekNumber()
                        )
                    )
                )
            }

            dao.insertAllSessions(restored)

            Log.d(TAG, "Restored ${restored.size} sessions — backup v$version")
            RestoreResult.Restored(count = restored.size)

        } catch (e: Exception) {
            Log.e(TAG, "Restore failed: ${e.message}")
            RestoreResult.Failed(reason = e.message ?: "Unknown error")
        }
    }

    // ══════════════════════════════════════════════════════════
    // JSON SERIALIZATION
    // ══════════════════════════════════════════════════════════

    private fun buildJson(
        sessions: List<FocusSession>,
        prefs: SharedPreferences
    ): String {
        val root = JSONObject().apply {
            put("version",       BACKUP_VERSION)
            put("exportedAt",    System.currentTimeMillis())
            put("userName",      prefs.getString("user_name", ""))
            put("lifeGoal",      prefs.getString("life_goal", ""))
            put("goalDate",      prefs.getLong("goal_date", 0L))
            put("operativeMode", prefs.getString(
                "operative_mode",
                OperativeMode.OPERATIVE.name
            ))

            val arr = JSONArray()
            sessions.forEach { s ->
                arr.put(JSONObject().apply {
                    put("startTime",         s.startTime)
                    put("durationMs",        s.durationMs)
                    put("isSuccess",         s.isSuccess)
                    put("wasCompromised",    s.wasCompromised)
                    put("appLeftCount",      s.appLeftCount)
                    put("microGoal",         s.microGoal)
                    put("exitExcuse",        s.exitExcuse)
                    put("emotionalSnapshot", s.emotionalSnapshot)
                    put("compromisedAtMs",   s.compromisedAtMs)
                    put("operativeMode",     s.operativeMode)
                    put("weekNumber",        s.weekNumber)
                })
            }
            put("sessions", arr)
        }
        return root.toString(2)
    }

    // ══════════════════════════════════════════════════════════
    // FILE I/O — MediaStore (Android 10+) + legacy fallback
    // ══════════════════════════════════════════════════════════

    private fun writeToDownloads(context: Context, json: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(context, json)
        } else {
            writeLegacy(json)
        }
    }

    private fun readFromDownloads(context: Context): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            readMediaStore(context)
        } else {
            readLegacy()
        }
    }

    // ── Android 10+ ───────────────────────────────────────────

    private fun writeMediaStore(context: Context, json: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val resolver = context.contentResolver
        val existing = findMediaStoreUri(context)

        val uri = existing ?: resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
                put(MediaStore.Downloads.MIME_TYPE,    "application/json")
                put(MediaStore.Downloads.IS_PENDING,   1)
            }
        ) ?: throw IOException("MediaStore insert returned null")

        resolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Cannot open output stream for backup")

        // Clear pending flag — makes file visible in file manager
        if (existing == null) {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                },
                null, null
            )
        }
    }

    private fun readMediaStore(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = findMediaStoreUri(context) ?: return null
        return context.contentResolver
            .openInputStream(uri)
            ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }

    private fun findMediaStoreUri(context: Context): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection  = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val args       = arrayOf(BACKUP_FILENAME)

        val cursor = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, args, null
        ) ?: return null

        return cursor.use {
            if (it.moveToFirst()) {
                val id = it.getLong(
                    it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                )
                android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
            } else null
        }
    }

    // ── Android 9 and below ───────────────────────────────────

    private fun writeLegacy(json: String) {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!dir.exists()) dir.mkdirs()
        File(dir, BACKUP_FILENAME).writeText(json, Charsets.UTF_8)
    }

    private fun readLegacy(): String? {
        val file = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ),
            BACKUP_FILENAME
        )
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    // ══════════════════════════════════════════════════════════
    // RESULT TYPES
    // ══════════════════════════════════════════════════════════

    sealed class RestoreResult {
        object NotNeeded                      : RestoreResult()
        object NoBackupFound                  : RestoreResult()
        data class Restored(val count: Int)   : RestoreResult()
        data class Failed(val reason: String) : RestoreResult()
    }
}

