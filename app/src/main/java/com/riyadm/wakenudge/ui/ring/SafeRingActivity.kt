package com.riyadm.wakenudge.ui.ring

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.engine.RingController
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.engine.RingTarget
import com.riyadm.wakenudge.engine.policy.RingPolicy
import com.riyadm.wakenudge.engine.policy.RingProof
import com.riyadm.wakenudge.missions.math.MathProblem
import com.riyadm.wakenudge.missions.math.MathProblemGenerator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The ring screen for when the real one kept crashing (safe mode). Plain Views only: no Compose, no camera, no sensors,
 * nothing that could be what crashed. An alarm that ends with one tap gets its buttons; any other alarm is ended with a few
 * plain sums on a keypad ([RingPolicy.safeSums]), which the controller counts itself, so safe mode is never a one-tap way
 * out of a mission. Emergency is here too.
 */
@AndroidEntryPoint
class SafeRingActivity : ComponentActivity() {
    @Inject lateinit var controller: RingController
    @Inject lateinit var store: RingSessionStore

    private val generator = MathProblemGenerator()
    private var problem: MathProblem? = null
    private var typed = ""
    private lateinit var root: LinearLayout
    private lateinit var progress: TextView
    private lateinit var question: TextView
    private lateinit var answer: TextView
    private lateinit var feedback: TextView
    private lateinit var snooze: Button
    private lateinit var dismiss: Button
    private lateinit var keypad: GridLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (store.current == null) finish() else controller.noteInteraction()
            }
        })
        setContentView(build())
        lifecycleScope.launch {
            var seen = false
            store.session.collect { s ->
                if (s != null) seen = true
                if (s == null && seen) finishAndRemoveTask()
                if (s != null) render(s)
            }
        }
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2_500)
            if (store.current == null) finishAndRemoveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        store.setUiResumed(true, isInMultiWindowMode)
    }

    override fun onPause() {
        store.setUiResumed(false, isInMultiWindowMode)
        super.onPause()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun text(sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(FOREGROUND)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        minHeight = dp(56)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun build(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        root.addView(text(16f).apply { setText(R.string.safe_ring_note) })
        progress = text(18f)
        question = text(44f, bold = true).apply { contentDescription = null }
        answer = text(36f).apply { hint = getString(R.string.mission_math_answer_hint); setHintTextColor(HINT) }
        feedback = text(16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        keypad = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", DELETE, "0", SUBMIT)
        for (key in keys) {
            keypad.addView(
                Button(this).apply {
                    text = when (key) {
                        DELETE -> "⌫"
                        SUBMIT -> "✓"
                        else -> key
                    }
                    contentDescription = when (key) {
                        DELETE -> getString(R.string.mission_math_delete)
                        SUBMIT -> getString(R.string.mission_math_submit)
                        else -> key
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    minHeight = dp(64)
                    layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
                    setOnClickListener { press(key) }
                },
            )
        }
        snooze = button(getString(R.string.action_snooze)) { store.current?.let { s -> lifecycleScope.launch { controller.snooze(RingTarget.of(s), RingProof.Gesture) } } }
        dismiss = button(getString(R.string.action_dismiss)) { store.current?.let { s -> lifecycleScope.launch { controller.dismiss(RingTarget.of(s), RingProof.Gesture) } } }
        val emergency = button(getString(R.string.ring_emergency)) { askEmergency() }
        listOf(progress, question, answer, feedback, keypad, snooze, dismiss, emergency).forEach(root::addView)
        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(root)
        }
    }

    private fun render(s: RingSession) {
        val sums = RingPolicy.safeSums(s)
        val needsSums = sums > 0 && s.phase == RingPhase.RINGING
        val visibility = if (needsSums) View.VISIBLE else View.GONE
        listOf(progress, question, answer, keypad).forEach { it.visibility = visibility }
        dismiss.visibility = if (needsSums) View.GONE else View.VISIBLE
        snooze.visibility = if (s.canSnooze) View.VISIBLE else View.GONE
        if (needsSums) {
            progress.text = getString(R.string.mission_math_progress, (s.safeSumsDone + 1).coerceAtMost(sums), sums)
            if (problem == null) problem = generator.next(Difficulty.MEDIUM)
            question.text = problem?.expression
            question.contentDescription = problem?.expression
            answer.text = typed
        }
    }

    private fun press(key: String) {
        val s = store.current ?: return
        controller.noteInteraction()
        when (key) {
            DELETE -> typed = typed.dropLast(1)
            SUBMIT -> {
                val p = problem ?: return
                if (typed.toIntOrNull() == p.answer) {
                    val done = s.safeSumsDone + 1
                    controller.noteSafeSum(done)
                    feedback.text = ""
                    typed = ""
                    problem = generator.next(Difficulty.MEDIUM)
                    if (done >= RingPolicy.safeSums(s)) lifecycleScope.launch { controller.dismiss(RingTarget.of(s), RingProof.SafeSumsComplete) }
                } else {
                    feedback.setText(R.string.mission_math_wrong)
                    typed = ""
                }
            }
            else -> if (typed.length < 4) typed += key
        }
        store.current?.let(::render)
    }

    private fun askEmergency() {
        val s = store.current ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.ring_emergency)
            .setMessage(if (s.pauses < RingPolicy.SILENT_PAUSES) R.string.ring_emergency_body else R.string.ring_emergency_body_loud)
            .setPositiveButton(R.string.ring_emergency_call) { _, _ -> RingActivity.emergencyCall(this, controller, RingTarget.of(s)) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private companion object {
        const val DELETE = "del"
        const val SUBMIT = "ok"
        val BACKGROUND = Color.rgb(0x12, 0x14, 0x18)
        val FOREGROUND = Color.WHITE
        val HINT = Color.rgb(0x9A, 0xA0, 0xA6)
    }
}
