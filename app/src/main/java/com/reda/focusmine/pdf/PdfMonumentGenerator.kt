package com.reda.focusmine.pdf

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.reda.focusmine.data.FocusSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

// ══════════════════════════════════════════════════════════════
// PdfMonumentGenerator — Phase 3
//
// Generates ink-elegant, white-background PDFs.
// Zero external libraries — uses android.graphics.pdf.PdfDocument
//
// Two entry points:
//   generateSessionPdf()  ← single session report (ReportFragment)
//   generateMonumentPdf() ← full chronicle (Time Capsule — Phase 4)
//
// All pages: white background, black ink, typographic hierarchy.
// Designed to print cleanly on any printer.
// ══════════════════════════════════════════════════════════════

class PdfMonumentGenerator(private val context: Context) {

    // ── Page dimensions — A4 at 72dpi ─────────────────────────
    private val W      = 595f
    private val H      = 842f
    private val MARGIN = 52f

    // ── Ink palette — print-safe ──────────────────────────────
    private val INK_BLACK  = Color.parseColor("#0A0A0A")
    private val INK_RED    = Color.parseColor("#8B0000")   // dark red — prints clean
    private val INK_GREY   = Color.parseColor("#555555")
    private val INK_DIM    = Color.parseColor("#999999")
    private val INK_FAINT  = Color.parseColor("#DDDDDD")
    private val PAGE_WHITE = Color.WHITE

    // ══════════════════════════════════════════════════════════
    // SINGLE SESSION PDF
    // Called from ReportFragment after each session
    // ══════════════════════════════════════════════════════════

    fun generateSessionPdf(
        session: FocusSession,
        prefs: SharedPreferences
    ): File {
        val doc  = PdfDocument()
        val page = doc.startPage(
            PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), 1).create()
        )

        drawSessionPage(page.canvas, session, prefs)
        doc.finishPage(page)

        val file = saveToCacheAndDownloads(doc, "focusmine_session_${session.startTime}.pdf")
        doc.close()
        return file
    }

    // ══════════════════════════════════════════════════════════
    // FULL MONUMENT PDF (Phase 4 entry point)
    // ══════════════════════════════════════════════════════════

    fun generateMonumentPdf(
        sessions: List<FocusSession>,
        prefs: SharedPreferences
    ): File {
        val doc        = PdfDocument()
        var pageNumber = 1

        // Cover page
        val coverPage = doc.startPage(
            PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNumber++).create()
        )
        drawCoverPage(coverPage.canvas, sessions, prefs)
        doc.finishPage(coverPage)

        // Chronicle pages — 14 sessions per page
        sessions.chunked(14).forEach { batch ->
            val p = doc.startPage(
                PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNumber++).create()
            )
            drawChronicle(p.canvas, batch)
            doc.finishPage(p)
        }

        // Final letter page
        val letterPage = doc.startPage(
            PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNumber).create()
        )
        drawLetterPage(letterPage.canvas, sessions, prefs)
        doc.finishPage(letterPage)

        val file = saveToCacheAndDownloads(
            doc,
            "focusmine_monument_${System.currentTimeMillis()}.pdf"
        )
        doc.close()
        return file
    }

    // ══════════════════════════════════════════════════════════
    // PAGE: SINGLE SESSION
    // ══════════════════════════════════════════════════════════

    private fun drawSessionPage(
        canvas: Canvas,
        session: FocusSession,
        prefs: SharedPreferences
    ) {
        canvas.drawColor(PAGE_WHITE)

        val sdf      = SimpleDateFormat("dd MMMM yyyy  //  HH:mm", Locale.US)
        val dateStr  = sdf.format(Date(session.startTime))
        val mins     = session.durationMs / 60_000
        val userName = prefs.getString("user_name", "") ?: ""

        // Top double rule
        hRule(canvas, 72f, INK_BLACK, 2f)
        hRule(canvas, 76f, INK_BLACK, 0.5f)

        // Header labels
        txt(canvas, "FOCUSMINE  //  SESSION RECORD", MARGIN, 64f, 7f, INK_DIM, ls = 0.3f)
        txt(canvas, userName.uppercase(), W - MARGIN - 80f, 64f, 7f, INK_DIM, ls = 0.2f)

        // Status
        val statusText  = if (session.isSuccess) "MISSION ACCOMPLISHED" else "SESSION ABANDONED"
        val statusColor = if (session.isSuccess) Color.parseColor("#005522") else INK_RED
        txt(canvas, statusText, MARGIN, 130f, 28f, statusColor, bold = true, ls = 0.05f)

        hRule(canvas, 148f, INK_FAINT, 0.5f)

        // Goal
        if (session.microGoal.isNotBlank()) {
            txt(canvas, "OBJECTIVE", MARGIN, 178f, 8f, INK_DIM, ls = 0.3f)
            wrapTxt(canvas, "\"${session.microGoal}\"", MARGIN, 200f, 14f, INK_GREY, 54)
        }

        // Time
        val timeLabel = if (session.isSuccess) "TIME SECURED" else "TIME ELAPSED"
        txt(canvas, timeLabel, MARGIN, 270f, 8f, INK_DIM, ls = 0.3f)
        txt(canvas, formatMinutes(mins), MARGIN, 330f, 56f, INK_BLACK, bold = true)
        txt(canvas, "MINUTES", MARGIN + measureText(formatMinutes(mins), 56f) + 12f,
            310f, 11f, INK_DIM, ls = 0.2f)

        hRule(canvas, 348f, INK_FAINT, 0.5f)

        var y = 378f

        // Date
        txt(canvas, "DATE", MARGIN, y, 8f, INK_DIM, ls = 0.3f)
        txt(canvas, dateStr, W / 2f, y, 10f, INK_GREY)
        y += 30f

        // Compromise
        if (session.wasCompromised && session.appLeftCount > 0) {
            hRule(canvas, y, INK_FAINT, 0.3f)
            y += 18f
            txt(canvas, "LEFT APP", MARGIN, y, 8f, INK_DIM, ls = 0.3f)
            txt(canvas, "${session.appLeftCount}  TIME(S) DURING SESSION",
                W / 2f, y, 10f, INK_RED)
            y += 30f
        }

        // Emotional snapshot
        if (session.emotionalSnapshot.isNotBlank()) {
            hRule(canvas, y, INK_FAINT, 0.3f)
            y += 18f
            txt(canvas, "STATE OF MIND", MARGIN, y, 8f, INK_DIM, ls = 0.3f)
            txt(canvas, session.emotionalSnapshot, W / 2f, y, 10f, INK_GREY)
            y += 30f
        }

        // Excuse — failure only
        if (!session.isSuccess && session.exitExcuse.isNotBlank()) {
            hRule(canvas, y, INK_FAINT, 0.3f)
            y += 18f
            txt(canvas, "RECORDED EXCUSE", MARGIN, y, 8f, INK_RED, ls = 0.3f)
            y += 18f
            wrapTxt(canvas, "\"${session.exitExcuse}\"", MARGIN, y, 11f, INK_RED, 70)
            y += 50f
        }

        // Bottom seal
        hRule(canvas, H - 60f, INK_FAINT, 0.5f)
        hRule(canvas, H - 56f, INK_BLACK, 2f)
        txt(canvas, "FOCUSMINE  //  ONE SESSION  //  ${dateStr.substringAfter("//").trim()}",
            MARGIN, H - 36f, 7f, INK_DIM, ls = 0.2f)
    }

    // ══════════════════════════════════════════════════════════
    // PAGE: COVER
    // ══════════════════════════════════════════════════════════

    private fun drawCoverPage(
        canvas: Canvas,
        sessions: List<FocusSession>,
        prefs: SharedPreferences
    ) {
        canvas.drawColor(PAGE_WHITE)

        val userName  = prefs.getString("user_name", "") ?: ""
        val lifeGoal  = prefs.getString("life_goal",  "") ?: ""
        val sdf       = SimpleDateFormat("dd . MM . yyyy", Locale.US)
        val startDate = sessions.firstOrNull()?.let { sdf.format(Date(it.startTime)) } ?: "—"
        val endDate   = sessions.lastOrNull()?.let  { sdf.format(Date(it.startTime)) } ?: "—"
        val totalMins = sessions.filter { it.isSuccess }.sumOf { it.durationMs } / 60_000
        val score     = calcConsistency(sessions)

        hRule(canvas, 72f, INK_BLACK, 2f)
        hRule(canvas, 76f, INK_BLACK, 0.5f)
        txt(canvas, "PERSONAL RECORD  —  CONFIDENTIAL", MARGIN, 64f, 7f, INK_DIM, ls = 0.4f)
        txt(canvas, "FOCUSMINE", W - MARGIN - 58f, 64f, 7f, INK_DIM, ls = 0.4f)

        // Life goal — dominant
        val goalLines = wrapWords(lifeGoal.uppercase(), 30)
        var y = 160f
        goalLines.forEach { line ->
            txt(canvas, line, MARGIN, y, 30f, INK_BLACK, bold = true, ls = 0.03f)
            y += 42f
        }

        hRule(canvas, y + 8f, INK_RED, 1f)
        y += 28f

        txt(canvas, "OPERATIVE  //  ${userName.uppercase()}", MARGIN, y, 10f, INK_RED, bold = true, ls = 0.2f)
        y += 22f
        txt(canvas, "$startDate  →  $endDate", MARGIN, y, 9f, INK_DIM)

        // Stats boxes
        val boxTop = H - 200f
        val boxBot = H - 80f
        val mid    = W / 2f

        rect(canvas, MARGIN, boxTop, mid - 8f, boxBot, INK_FAINT, 0.8f)
        txt(canvas, score.toInt().toString(), MARGIN + 16f, boxTop + 68f, 48f, INK_BLACK, bold = true)
        txt(canvas, "CONSISTENCY", MARGIN + 16f, boxTop + 88f, 8f, INK_DIM, ls = 0.3f)
        txt(canvas, "OUT OF 100",  MARGIN + 16f, boxTop + 103f, 8f, Color.parseColor("#BBBBBB"), ls = 0.2f)

        rect(canvas, mid + 8f, boxTop, W - MARGIN, boxBot, INK_FAINT, 0.8f)
        txt(canvas, "$totalMins", mid + 24f, boxTop + 68f, 48f, INK_BLACK, bold = true)
        txt(canvas, "MINUTES",         mid + 24f, boxTop + 88f, 8f, INK_DIM, ls = 0.3f)
        txt(canvas, "SECURED IN SILENCE", mid + 24f, boxTop + 103f, 8f, Color.parseColor("#BBBBBB"), ls = 0.2f)

        hRule(canvas, H - 52f, INK_BLACK, 0.5f)
        hRule(canvas, H - 48f, INK_BLACK, 2f)
    }

    // ══════════════════════════════════════════════════════════
    // PAGE: CHRONICLE
    // ══════════════════════════════════════════════════════════

    private fun drawChronicle(canvas: Canvas, sessions: List<FocusSession>) {
        canvas.drawColor(PAGE_WHITE)
        val sdf = SimpleDateFormat("dd MMM  HH:mm", Locale.US)

        txt(canvas, "THE CHRONICLE", MARGIN, 64f, 9f, INK_RED, bold = true, ls = 0.4f)
        hRule(canvas, 76f, INK_BLACK, 1f)

        // Column headers
        txt(canvas, "STATUS", MARGIN,        100f, 7f, Color.parseColor("#BBBBBB"), ls = 0.3f)
        txt(canvas, "GOAL",   MARGIN + 44f,  100f, 7f, Color.parseColor("#BBBBBB"), ls = 0.3f)
        txt(canvas, "DATE",   W - MARGIN - 130f, 100f, 7f, Color.parseColor("#BBBBBB"), ls = 0.3f)
        txt(canvas, "MIN",    W - MARGIN - 24f,  100f, 7f, Color.parseColor("#BBBBBB"), ls = 0.3f)
        hRule(canvas, 108f, INK_FAINT, 0.5f)

        var y = 128f

        sessions.forEach { s ->
            val mins     = s.durationMs / 60_000
            val color    = if (s.isSuccess) Color.parseColor("#005522") else INK_RED
            val statusTx = if (s.isSuccess) "DONE" else "FAIL"

            txt(canvas, statusTx, MARGIN, y, 8f, color, bold = s.isSuccess)
            txt(canvas, s.microGoal.take(50), MARGIN + 44f, y, 9f, INK_BLACK)
            txt(canvas, sdf.format(Date(s.startTime)), W - MARGIN - 130f, y, 8f, INK_GREY)
            txt(canvas, "$mins", W - MARGIN - 16f, y, 9f, INK_BLACK, bold = true)

            if (!s.isSuccess && s.exitExcuse.isNotBlank()) {
                y += 14f
                txt(canvas, "  → ${s.exitExcuse.take(72)}", MARGIN + 44f, y, 7.5f,
                    Color.parseColor("#BBBBBB"))
            }

            hRule(canvas, y + 8f, INK_FAINT, 0.3f)
            y += 24f
        }
    }

    // ══════════════════════════════════════════════════════════
    // PAGE: DYNAMIC LETTER
    // ══════════════════════════════════════════════════════════

    private fun drawLetterPage(
        canvas: Canvas,
        sessions: List<FocusSession>,
        prefs: SharedPreferences
    ) {
        canvas.drawColor(PAGE_WHITE)

        val userName  = prefs.getString("user_name", "") ?: ""
        val lifeGoal  = prefs.getString("life_goal", "") ?: ""
        val goalDate  = prefs.getLong("goal_date", 0L)
        val sdf       = SimpleDateFormat("dd MMMM yyyy", Locale.US)
        val goalStr   = if (goalDate > 0) sdf.format(Date(goalDate)) else "your day"
        val score     = calcConsistency(sessions)
        val totalMins = sessions.filter { it.isSuccess }.sumOf { it.durationMs } / 60_000
        val successes = sessions.count { it.isSuccess }
        val successPct= if (sessions.isNotEmpty()) successes * 100 / sessions.size else 0

        val state = when {
            score >= 70f -> "MASTER"
            score >= 35f -> "STRUGGLER"
            else         -> "UNFINISHED"
        }

        hRule(canvas, 72f, INK_BLACK, 2f)
        hRule(canvas, 76f, INK_BLACK, 0.5f)
        txt(canvas, "OPERATIONAL ASSESSMENT  //  THE $state", MARGIN, 64f, 7f,
            if (state == "UNFINISHED") INK_RED else INK_DIM, ls = 0.3f)

        val letter = buildLetter(state, userName, lifeGoal, goalStr,
            totalMins, sessions.size, successPct, score)

        var y = 116f
        letter.lines().forEach { line ->
            val isGoal  = line == lifeGoal
            val isData  = line.startsWith("  ")
            txt(canvas, line,
                x     = MARGIN,
                y     = y,
                size  = when { isGoal -> 11f; isData -> 9f; else -> 10f },
                color = when { isGoal -> INK_BLACK; isData -> INK_GREY; else -> INK_BLACK },
                bold  = isGoal
            )
            y += if (line.isBlank()) 8f else 18f
        }

        hRule(canvas, H - 60f, INK_FAINT, 0.5f)
        hRule(canvas, H - 56f, INK_BLACK, 2f)
        txt(canvas, "FOCUSMINE  //  ${userName.uppercase()}  //  ${sessions.size} SESSIONS",
            MARGIN, H - 36f, 7f, INK_DIM, ls = 0.2f)
    }

    private fun buildLetter(
        state: String,
        userName: String,
        lifeGoal: String,
        goalDate: String,
        totalMins: Long,
        totalSessions: Int,
        successPct: Int,
        score: Float
    ): String = buildString {
        appendLine("To $userName,")
        appendLine()
        when (state) {
            "MASTER" -> {
                appendLine("On $goalDate, this document exists")
                appendLine("because you refused to be ordinary.")
                appendLine()
                appendLine("The record:")
                appendLine()
                appendLine("  $totalMins minutes of silence.")
                appendLine("  $totalSessions sessions.")
                appendLine("  $successPct% completion rate.")
                appendLine()
                appendLine("You said you wanted to become:")
                appendLine(lifeGoal)
                appendLine()
                appendLine("That percentage is rare.")
                appendLine("Most people never measure it.")
                appendLine("You wanted to know.")
                appendLine("That is the difference.")
            }
            "STRUGGLER" -> {
                appendLine("This document tells the truth.")
                appendLine("Not the truth you wanted — the truth you made.")
                appendLine()
                appendLine("  $totalSessions sessions.")
                appendLine("  $successPct% completed.")
                appendLine("  $totalMins minutes of actual work.")
                appendLine()
                appendLine("That is not mastery.")
                appendLine("That is also not nothing.")
                appendLine()
                appendLine("You said you wanted to become:")
                appendLine(lifeGoal)
                appendLine()
                appendLine("The Chronicle shows the pattern.")
                appendLine("Read the excuses. They are a map.")
                appendLine("A map of where you lost — and where to win.")
            }
            else -> {
                appendLine("This is not a monument.")
                appendLine("A monument requires building something.")
                appendLine()
                appendLine("  Consistency: ${score.toInt()} / 100")
                appendLine("  Sessions completed: $successPct%")
                appendLine("  Total real work: $totalMins minutes")
                appendLine()
                appendLine("You said you wanted to become:")
                appendLine(lifeGoal)
                appendLine()
                appendLine("The Chronicle does not show that person yet.")
                appendLine()
                appendLine("The question is simple:")
                appendLine("Was this your real effort —")
                appendLine("or did you still have more to give?")
                appendLine()
                appendLine("Only you know.")
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // FILE I/O
    // ══════════════════════════════════════════════════════════

    private fun saveToCacheAndDownloads(doc: PdfDocument, filename: String): File {
        // 1. Write to cache (for FileProvider sharing)
        val cacheFile = File(context.cacheDir, filename)
        FileOutputStream(cacheFile).use { doc.writeTo(it) }

        // 2. Also copy to Downloads (persistent backup)
        try {
            saveToDownloads(cacheFile, filename)
        } catch (e: Exception) {
            android.util.Log.w("PDF", "Downloads copy failed: ${e.message}")
        }

        return cacheFile
    }

    private fun saveToDownloads(source: File, filename: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val existing = findInMediaStore(filename)

            val uri = existing ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            ) ?: throw IOException("MediaStore insert failed")

            resolver.openOutputStream(uri, "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }

            if (existing == null) {
                resolver.update(uri,
                    ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            source.copyTo(File(dir, filename), overwrite = true)
        }
    }

    private fun findInMediaStore(filename: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cursor = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(filename), null
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            } else null
        }
    }

    // ══════════════════════════════════════════════════════════
    // DRAWING PRIMITIVES
    // ══════════════════════════════════════════════════════════

    private fun txt(
        canvas: Canvas, text: String,
        x: Float, y: Float, size: Float,
        color: Int, bold: Boolean = false,
        ls: Float = 0f
    ) {
        if (text.isBlank() && ls == 0f) return
        canvas.drawText(text, x, y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color         = color
                textSize           = size * 2.2f  // PDF density compensation
                isFakeBoldText     = bold
                letterSpacing      = ls
            }
        )
    }

    private fun wrapTxt(
        canvas: Canvas, text: String,
        x: Float, startY: Float, size: Float,
        color: Int, maxChars: Int
    ) {
        var y = startY
        wrapWords(text, maxChars).forEach { line ->
            txt(canvas, line, x, y, size, color)
            y += size * 2.2f * 1.4f
        }
    }

    private fun hRule(canvas: Canvas, y: Float, color: Int, stroke: Float) {
        canvas.drawLine(MARGIN, y, W - MARGIN, y,
            Paint().apply { this.color = color; strokeWidth = stroke })
    }

    private fun rect(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        color: Int, stroke: Float
    ) {
        canvas.drawRect(left, top, right, bottom,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color  = color
                style       = Paint.Style.STROKE
                strokeWidth = stroke
            })
    }

    private fun wrapWords(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val words   = text.split(" ")
        val lines   = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            if ((current + word).length > maxChars) {
                if (current.isNotEmpty()) lines.add(current.trim())
                current = "$word "
            } else {
                current += "$word "
            }
        }
        if (current.isNotEmpty()) lines.add(current.trim())
        return lines
    }

    private fun measureText(text: String, size: Float): Float {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 2.2f
        }.measureText(text)
    }

    private fun formatMinutes(mins: Long): String = mins.toString()

    // ── Consistency score (mirrors ChronicleBackup logic) ─────
    private fun calcConsistency(sessions: List<FocusSession>): Float {
        if (sessions.isEmpty()) return 0f
        var wSuccess = 0f
        var wTotal   = 0f
        sessions.forEachIndexed { i, s ->
            val w = (i + 1).toFloat() / sessions.size
            wTotal   += w
            if (s.isSuccess) wSuccess += w
        }
        val raw = wSuccess / wTotal
        return (raw * raw * 100).coerceIn(0f, 100f)
    }
}

