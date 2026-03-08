package com.reda.focusmine.ui

import android.graphics.Bitmap
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
import com.reda.focusmine.data.FocusSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ReportFragment : Fragment() {

    private val args: ReportFragmentArgs by navArgs()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_report, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val isSuccess  = args.isSuccess
        val durationMs = args.durationMs

        // ─── Bind Views ───────────────────────────────────────────
        val cardBg        = view.findViewById<View>(R.id.reportCard)
        val tvTitle       = view.findViewById<MaterialTextView>(R.id.tvReportTitle)
        val tvSubtitle    = view.findViewById<MaterialTextView>(R.id.tvReportSubtitle)
        val tvTime        = view.findViewById<MaterialTextView>(R.id.tvReportTime)
        val tvTimeLabel   = view.findViewById<MaterialTextView>(R.id.tvReportTimeLabel)
        val tvDate        = view.findViewById<MaterialTextView>(R.id.tvReportDate)
        val tvClassified  = view.findViewById<MaterialTextView>(R.id.tvClassified)
        val btnShare      = view.findViewById<MaterialButton>(R.id.btnShare)
        val btnHome       = view.findViewById<MaterialButton>(R.id.btnBackHome)
        val statusBar     = view.findViewById<View>(R.id.reportStatusBar)

        val RED   = 0xFFCC3300.toInt()
        val GREEN = 0xFF00CC44.toInt()

        // ─── Fill data ────────────────────────────────────────────
        val h = durationMs / 3_600_000
        val m = (durationMs % 3_600_000) / 60_000
        val s = (durationMs % 60_000) / 1_000
        val timeFormatted = String.format("%02d:%02d:%02d", h, m, s)

        val sdf = java.text.SimpleDateFormat("dd.MM.yyyy // HH:mm", java.util.Locale.US)
        val dateStr = sdf.format(java.util.Date())

        if (isSuccess) {
            tvTitle.text      = "MISSION\nACCOMPLISHED"
            tvTitle.setTextColor(GREEN)
            tvSubtitle.text   = "OBJECTIVE SECURED"
            tvSubtitle.setTextColor(GREEN)
            tvTimeLabel.text  = "TIME SECURED"
            tvClassified.text = "[ CLASSIFIED — OPERATION SUCCESS ]"
            statusBar.setBackgroundColor(GREEN)
            cardBg.setBackgroundColor(0xFF020D04.toInt())
        } else {
            tvTitle.text      = "MISSION\nFAILED"
            tvTitle.setTextColor(RED)
            tvSubtitle.text   = "KIA — KILLED IN ACTION"
            tvSubtitle.setTextColor(RED)
            tvTimeLabel.text  = "SURVIVED FOR"
            tvClassified.text = "[ CLASSIFIED — OPERATION FAILURE ]"
            statusBar.setBackgroundColor(RED)
            cardBg.setBackgroundColor(0xFF0D0200.toInt())
        }

        tvTime.text = timeFormatted
        tvTime.setTextColor(if (isSuccess) GREEN else RED)
        tvDate.text = dateStr

        // ─── Save to DB ───────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getInstance(requireContext()).sessionDao().insertSession(
                FocusSession(
                    startTime  = System.currentTimeMillis() - durationMs,
                    durationMs = durationMs,
                    isSuccess  = isSuccess
                )
            )
        }

        // ─── Animations ───────────────────────────────────────────
        listOf(cardBg, tvTitle, tvSubtitle, tvTime, tvTimeLabel, tvDate, tvClassified)
            .forEachIndexed { i, v ->
                v.alpha = 0f; v.translationY = 20f
                v.animate().alpha(1f).translationY(0f)
                    .setDuration(400).setStartDelay((i * 100).toLong())
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            }

        // Scale-in the time number for impact
        tvTime.scaleX = 0.6f; tvTime.scaleY = 0.6f
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAdded) return@postDelayed
            tvTime.animate().scaleX(1f).scaleY(1f)
                .setDuration(500)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        }, 500)

        // ─── Buttons ──────────────────────────────────────────────
        btnShare.setOnClickListener { shareCard(cardBg) }
        btnHome.setOnClickListener {
            findNavController().navigate(R.id.action_report_to_home)
        }
    }

    // ─── Screenshot & Share ───────────────────────────────────────
    private fun shareCard(card: View) {
        try {
            val bmp = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            card.draw(canvas)

            val file = File(requireContext().cacheDir, "focusmine_report.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                file
            )

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_TEXT,
                    if (args.isSuccess) "I secured ${formatTime(args.durationMs)} without touching my phone. #FocusMine"
                    else                "I failed my FocusMine mission. The mine detonated. #FocusMine"
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Share your report"))
        } catch (e: Exception) {
            android.util.Log.e("Report", "Share failed: ${e.message}")
        }
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
