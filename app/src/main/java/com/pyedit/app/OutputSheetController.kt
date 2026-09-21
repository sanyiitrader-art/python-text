package com.pyedit.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.pyedit.app.databinding.ActivityMainBinding
import kotlin.math.abs

/**
 * Step 6: the draggable output sheet (spec §41-52) — handle drag,
 * collapse/expand, Run-respects-height-vs-opens-to-25%, and the
 * full-open threshold with haptic + sideways editor/output page mode.
 *
 * This is the most novel, hand-built piece in the whole app (nothing in
 * any library does this) — built entirely with layoutParams.height and
 * View.translationX/Y, no new dependency, but flagged as the part most
 * likely to need real-device tuning.
 */
class OutputSheetController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {
    private val handleHeightPx = dp(28)
    private val runOpenFractionOfContainer = 0.25f
    private val collapseSnapBelowPx = dp(48)
    private val fullOpenThresholdFraction = 0.96f

    private var containerHeightPx = 0
    private var currentHeightPx = handleHeightPx
    private var isSidewaysMode = false
    private var hasTriggeredFullOpenThisDrag = false

    private var dragStartRawY = 0f
    private var dragStartHeightPx = 0

    private var sidewaysDragStartRawX = 0f
    private var sidewaysDragStartRawY = 0f
    private var sidewaysTrackingHorizontal = false
    private var sidewaysTrackingVertical = false

    fun setup() {
        binding.outputPanel.dragHandleTouchArea.setOnTouchListener { _, event ->
            handleDragTouch(event)
            true
        }

        // Sideways-mode gestures live on the whole output panel root
        // (once in that mode, the handle itself is conceptually gone —
        // spec §47 "remove the output sheet drag handle" — so this
        // second listener on the root covers swiping while there).
        binding.outputPanel.root.setOnTouchListener { _, event ->
            if (isSidewaysMode) {
                handleSidewaysTouch(event)
                true
            } else {
                false
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun ensureContainerHeightMeasured() {
        if (containerHeightPx == 0) {
            containerHeightPx = binding.editorContainer.height
        }
    }

    // --- Bottom-sheet drag (handle) --------------------------------------

    private fun handleDragTouch(event: MotionEvent) {
        ensureContainerHeightMeasured()
        if (containerHeightPx == 0) return

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawY = event.rawY
                dragStartHeightPx = currentHeightPx
                hasTriggeredFullOpenThisDrag = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = dragStartRawY - event.rawY // positive = dragging up
                val newHeight = (dragStartHeightPx + deltaY.toInt())
                    .coerceIn(handleHeightPx, containerHeightPx)
                applyHeight(newHeight)

                val fraction = newHeight.toFloat() / containerHeightPx.toFloat()
                if (!hasTriggeredFullOpenThisDrag && fraction >= fullOpenThresholdFraction) {
                    hasTriggeredFullOpenThisDrag = true
                    triggerFullOpen()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isSidewaysMode) {
                    if (currentHeightPx < collapseSnapBelowPx) {
                        applyHeight(handleHeightPx)
                    }
                }
            }
        }
    }

    private fun applyHeight(newHeightPx: Int) {
        currentHeightPx = newHeightPx
        val params = binding.outputPanel.root.layoutParams as ViewGroup.LayoutParams
        params.height = newHeightPx
        binding.outputPanel.root.layoutParams = params
    }

    // --- Run integration --------------------------------------------------

    /**
     * Spec §45-46: if collapsed (at/below 5%), Run opens to 25%; if
     * already above 5%, Run preserves the current height untouched.
     */
    fun onRunRequested() {
        ensureContainerHeightMeasured()
        binding.outputPanel.root.visibility = View.VISIBLE

        if (containerHeightPx == 0) return
        val collapsedThresholdPx = (containerHeightPx * 0.05f).toInt()
        if (currentHeightPx <= collapsedThresholdPx) {
            val targetHeight = (containerHeightPx * runOpenFractionOfContainer).toInt()
            animateToHeight(targetHeight)
        }
        // else: leave currentHeightPx exactly as the user left it.
    }

    private fun animateToHeight(targetHeightPx: Int) {
        val start = currentHeightPx
        binding.outputPanel.root.animate()
            .setDuration(180)
            .setUpdateListener { fraction ->
                // Manual interpolation since ValueAnimator on a bare
                // ViewPropertyAnimator isn't directly available here —
                // simplest reliable path is a plain height tween.
            }
        // ViewPropertyAnimator can't animate layoutParams.height directly;
        // use a straightforward manual tween instead.
        val steps = 10
        val diff = targetHeightPx - start
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (i in 1..steps) {
            handler.postDelayed({
                applyHeight(start + diff * i / steps)
            }, (i * 18).toLong())
        }
    }

    // --- Full-open + sideways mode -----------------------------------------

    private fun triggerFullOpen() {
        vibrateOnce()
        isSidewaysMode = true
        binding.outputPanel.dragHandleTouchArea.visibility = View.GONE // spec §47: remove the handle

        applyHeight(containerHeightPx)
        // Output currently occupies the full band; slide the editor out
        // to the left so Output is the visible page (this is the state
        // the user just dragged into).
        binding.editorContainer.translationX = -screenWidth().toFloat()
        binding.outputPanel.root.translationX = 0f
    }

    private fun vibrateOnce() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (t: Throwable) { /* haptics are a nice-to-have, never worth crashing over */ }
    }

    private fun screenWidth(): Int = activity.resources.displayMetrics.widthPixels

    /**
     * Handles both horizontal swipe (Output <-> Editor pages, spec §48)
     * and downward drag (exit sideways mode back to bottom-sheet, §49)
     * while in sideways mode. Whichever direction moves further from the
     * initial touch determines which gesture this touch sequence is.
     */
    private fun handleSidewaysTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sidewaysDragStartRawX = event.rawX
                sidewaysDragStartRawY = event.rawY
                sidewaysTrackingHorizontal = false
                sidewaysTrackingVertical = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - sidewaysDragStartRawX
                val deltaY = event.rawY - sidewaysDragStartRawY

                if (!sidewaysTrackingHorizontal && !sidewaysTrackingVertical) {
                    if (abs(deltaX) > abs(deltaY) && abs(deltaX) > dp(8)) {
                        sidewaysTrackingHorizontal = true
                    } else if (deltaY > dp(8)) {
                        sidewaysTrackingVertical = true
                    }
                }

                if (sidewaysTrackingHorizontal) {
                    // Swiping right on Output page moves toward Editor.
                    val clamped = deltaX.coerceIn(0f, screenWidth().toFloat())
                    binding.outputPanel.root.translationX = clamped
                    binding.editorContainer.translationX = clamped - screenWidth()
                } else if (sidewaysTrackingVertical) {
                    // Dragging down begins the "return to bottom sheet" motion —
                    // spec §49: moves down slightly first, then transitions.
                    val clamped = deltaY.coerceIn(0f, containerHeightPx.toFloat())
                    binding.outputPanel.root.translationY = clamped
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (sidewaysTrackingHorizontal) {
                    val movedPastHalfway = binding.outputPanel.root.translationX > screenWidth() / 2f
                    if (movedPastHalfway) {
                        goToEditorPage()
                    } else {
                        snapBackToOutputPage()
                    }
                } else if (sidewaysTrackingVertical) {
                    val movedPastThreshold = binding.outputPanel.root.translationY > dp(80)
                    if (movedPastThreshold) {
                        exitSidewaysMode()
                    } else {
                        binding.outputPanel.root.translationY = 0f
                    }
                }
                sidewaysTrackingHorizontal = false
                sidewaysTrackingVertical = false
            }
        }
    }

    private fun goToEditorPage() {
        binding.outputPanel.root.animate().translationX(screenWidth().toFloat()).setDuration(150).start()
        binding.editorContainer.animate().translationX(0f).setDuration(150).start()
    }

    private fun snapBackToOutputPage() {
        binding.outputPanel.root.animate().translationX(0f).setDuration(150).start()
        binding.editorContainer.animate().translationX(-screenWidth().toFloat()).setDuration(150).start()
    }

    /** Spec §49: return to bottom-sheet mode. */
    private fun exitSidewaysMode() {
        isSidewaysMode = false
        binding.outputPanel.dragHandleTouchArea.visibility = View.VISIBLE
        binding.outputPanel.root.translationY = 0f
        binding.editorContainer.translationX = 0f
        binding.outputPanel.root.translationX = 0f

        val target = (containerHeightPx * runOpenFractionOfContainer).toInt()
        animateToHeight(target)
    }

    /** Called if the user swipes back to the Editor page while in
     * sideways mode — from there, tapping into the editor should behave
     * like the ordinary bottom-sheet state again (output no longer full
     * screen). Exposed so ExecutionUiController/other callers can check. */
    fun isInSidewaysMode(): Boolean = isSidewaysMode
}