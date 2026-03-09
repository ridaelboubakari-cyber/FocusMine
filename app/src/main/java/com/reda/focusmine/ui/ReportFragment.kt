package com.reda.focusmine.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.reda.focusmine.R
import com.reda.focusmine.data.AppDatabase
import com.reda.focusmine.data.ChronicleBackup
import com.reda.focusmine.data.FocusSession
import com.reda.focusmine.data.OperativeMode
import com.reda.focusmine.data.getCurrentWeekNumber
import com.reda.focusmine.pdf.PdfMonumentGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ══════════════════════════════════════════════════════════════
// ReportFragment — PHASE 3
//
// CHANGES:
//   ✓ Reads microGoal, exitExcuse, wasCompromised, appLeftCount
//     from SafeArgs (all new Phase 2/3 fields)
//   ✓ Displays excuse and compromise count on failure
//   ✓ Saves full FocusSession with all new fields to Room DB
//   ✓ Triggers auto-backup after every save
//   ✓ btnGeneratePdf generates a single-session PDF
// ══════════════════════════════════════════════════════════════

class ReportFragment : Fragment() {

    private val args: ReportFragmentArgs by navArgs()

    // ── Saved session ID (for PDF generation) ─────────────────
    private var savedSessionId: Long = -1L

    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = i.inflate(R.layout.fragment_report, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        // ── Bind ──────────────────────────────────────────────
        val reportCard       = view.findViewById<View>(R.id.reportCard)
        val statusBar        = view.findViewById<View>(R.id.reportStatusBar)
        val tvTitle          = view.findViewById<MaterialTextView>(R.id.tvReportTitle)
        val tvSubtitle       = view.findViewById<MaterialTextView>(R.id.tvReportSubtitle)
        val tvGoal           = view.findViewById<MaterialTextView>(R.id.tvReportGoal)
        val tvTime           = view.findViewById<MaterialTextView>(R.id.tvReportTime)
        val tvTimeLabel      = view.findViewById<MaterialTextView>(R.id.tvReportTimeLabel)
        val tvDate           = view.findViewById<MaterialTextView>(R.id.tvReportDate)
        val tvClassified     = view.findViewById<MaterialTextView>(R.id.tvClassified)
        val tvCompromise     = view.findViewById<MaterialTextView>(R.id.tvCompromiseCount)
        val excuseDivider    = view.findViewById<View>(R.id.excuseDivider)
        val tvExcuseLabel    = view.findViewById<MaterialTextView>(R.id.tvExcuseLabel)
        val tvExcuse         = view.findViewById<MaterialTextView>(R.id.tvReportExcuse)
        val btnShare         = view.findViewById<MaterialButton>(R.id.btnShare)
        val btnGeneratePdf   = view.findViewById<MaterialButton>(R.id.btnGeneratePdf)
        val btnHome          = view.findViewById<MaterialButton>(R.id.btnBackHome)

        // ── Data from args ────────────────────────────────────
        val isSuccess         = args.isSuccess
        val durationMs        = args.durationMs
        val microGoal         = args.microGoal         ?: ""
        val exitExcuse        = args.exitExcuse        ?: ""
        val wasCompromised    = args.wasCompromised
        val appLeftCount      = args.appLeftCount
        val emotionalSnapshot = args.emotionalSnapshot ?: ""

        val RED   = 0xFFCC3300.toInt()
        val GREEN = 0xFF00CC44.toInt()

        // ── Time display ──────────────────────────────────────
        val h = durationMs / 3_600_000
        val m = (durationMs % 3_600_000) / 60_000
        val s = (durationMs % 60_000) / 1_000
        val timeFormatted = String.format("%02d:%02d:%02d", h, m, s)

        val sdf     = SimpleDateFormat("dd.MM.yyyy // HH:mm", Locale.US)
        val dateStr = sdf.format(Date())

        // ── Fill success / failure UI ─────────────────────────
        if (isSuccess) {
            tvTitle.text      = "MISSION\nACCOMPLISHED"
            tvTitle.setTextColor(GREEN)
            tvSubtitle.text   = "OBJECTIVE SECURED"
            tvSubtitle.setTextColor(GREEN)
            tvTimeLabel.text  = "TIME SECURED"
            tvClassified.text = "[ CLASSIFIED — SUCCESS ]"
            statusBar.setBackgroundColor(GREEN)
            reportCard.setBackgroundColor(0xFF020D04.toInt())
            tvTime.setTextColor(GREEN)
        } else {
            tvTitle.text      = "MISSION\nFAILED"
            tvTitle.setTextColor(RED)
            tvSubtitle.text   = "SESSION ABANDONED"
            tvSubtitle.setTextColor(RED)
            tvTimeLabel.text  = "SURVIVED FOR"
            tvClassified.text = "[ CLASSIFIED — FAILURE ]"
            statusBar.setBackgroundColor(RED)
            reportCard.setBackgroundColor(0xFF0D0200.toInt())
            tvTime.setTextColor(RED)

            // Compromise count
            if (wasCompromised && appLeftCount > 0) {
                tvCompromise.text       = "LEFT APP $appLeftCount TIME(S) DURING SESSION"
                tvCompromise.visibility = View.VISIBLE
            }

            // Excuse
            if (exitExcuse.isNotBlank()) {
                excuseDivider.visibility = View.VISIBLE
                tvExcuseLabel.visibility = View.VISIBLE
                tvExcuse.text            = "\"$exitExcuse\""
                tvExcuse.visibility      = View.VISIBLE
            }
        }

        // Micro-goal
        if (microGoal.isNotBlank()) {
            tvGoal.text = "\"$microGoal\""
        }

        tvTime.text = timeFormatted
        tvDate.text = dateStr

        // ── Save session to Room DB ───────────────────────────
        val prefs = requireContext()
            .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
        val modeStr = prefs.getString("operative_mode", OperativeMode.OPERATIVE.name)
            ?: OperativeMode.OPERATIVE.name

        val session = FocusSession(
            startTime         = System.currentTimeMillis() - durationMs,
            durationMs        = durationMs,
            isSuccess         = isSuccess,
            wasCompromised    = wasCompromised,
            appLeftCount      = appLeftCount,
            microGoal         = microGoal,
            exitExcuse        = exitExcuse,
            emotionalSnapshot = emotionalSnapshot,
            operativeMode     = modeStr,
            weekNumber        = getCurrentWeekNumber()
        )

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(requireContext()).sessionDao()
            dao.insertSession(session)

            // Retrieve the saved session with its auto-generated ID
            val saved = dao.getLastSession()
            saved?.let { savedSessionId = it.id.toLong() }

            // Silent auto-backup
            ChronicleBackup.autoBackup(requireContext(), dao, prefs)
        }

        // ── Animations ────────────────────────────────────────
        listOf(reportCard, tvTitle, tvSubtitle, tvGoal, tvTime,
               tvTimeLabel, tvDate, tvClassified)
            .forEachIndexed { i, v ->
                v.alpha       = 0f
                v.translationY = 20f
                v.animate().alpha(1f).translationY(0f)
                    .setDuration(400)
                    .setStartDelay((i * 90).toLong())
                    .setInterpolator(DecelerateInterpolator(2f))
                    .start()
            }

        tvTime.scaleX = 0.6f
        tvTime.scaleY = 0.6f
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAdded) return@postDelayed
            tvTime.animate().scaleX(1f).scaleY(1f)
                .setDuration(500)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        }, 500)

        // ── Buttons ───────────────────────────────────────────
        btnShare.setOnClickListener { shareCard(reportCard, isSuccess, durationMs) }

        btnGeneratePdf.setOnClickListener {
            generateSessionPdf(
                btnGeneratePdf, session, isSuccess, durationMs
            )
        }

        btnHome.setOnClickListener {
            findNavController().navigate(R.id.action_report_to_home)
        }
    }

    // ══════════════════════════════════════════════════════════
    // SHARE — screenshot of the card
    // ══════════════════════════════════════════════════════════

    private fun shareCard(card: View, isSuccess: Boolean, durationMs: Long) {
        try {
            val bmp    = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            card.draw(canvas)

            val file = File(requireContext().cacheDir, "focusmine_report.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                file
            )

            val shareText = if (isSuccess)
                "I secured ${formatTime(durationMs)} without touching my phone. #FocusMine"
            else
                "Session abandoned after ${formatTime(durationMs)}. The Chronicle records everything. #FocusMine"

            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share your report"
            ))
        } catch (e: Exception) {
            android.util.Log.e("Report", "Share failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════
    // PDF GENERATION — single session
    // ══════════════════════════════════════════════════════════

    private fun generateSessionPdf(
        btn: MaterialButton,
        session: FocusSession,
        isSuccess: Boolean,
        durationMs: Long
    ) {
        btn.isEnabled = false
        btn.text      = "GENERATING..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val prefs = requireContext()
                    .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)

                val file = withContext(Dispatchers.IO) {
                    PdfMonumentGenerator(requireContext())
                        .generateSessionPdf(session, prefs)
                }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    file
                )

                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT,
                            "FocusMine Session — ${
                                if (isSuccess) formatTime(durationMs) + " secured"
                                else "abandoned"
                            }")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Save or share PDF"
                ))

                btn.text = "⬇  SAVED"

            } catch (e: Exception) {
                android.util.Log.e("Report", "PDF failed: ${e.message}")
                btn.isEnabled = true
                btn.text      = "⬇  SAVE SESSION PDF"
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════

    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

