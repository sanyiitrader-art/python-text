package com.pyedit.app

import android.content.Context
import android.content.res.ColorStateList
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

class OutputSheetController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onEditorTapPassthrough: () -> Unit
) {
    private val handleHeightPx = dp(28)
    private val runOpenFractionOfContainer = 0.25f
    private val collapseSnapBelowPx = dp(48)
    private val fullOpenThresholdFraction = 0.96f
    private val pageCommitFraction = 0.3f

    private var containerHeightPx = handleHeightPx
    private var currentHeightPx = handleHeightPx
    private var isSidewaysMode = false
    private var isShowingOutputPage = true
    private var previewingFullOpen = false
    private var isDraggingHandle = false

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

        binding.sidewaysGestureOverlay.setOnTouchListener { _, event ->
            handleSidewaysTouch(event)
            true
        }

        // FIX item 2: whenever the layout changes for ANY reason —
        // keyboard opening/closing being the key case — re-check the
        // sheet's height against the (possibly now smaller) available
        // space and clamp it down if it would overflow. Previously this
        // reclamping only happened during an active drag, so a sheet
        // left expanded above the midpoint would keep its old absolute
        // pixel height even after adjustResize shrank the root view for
        // the keyboard, pushing it up through the top bar. Skipped while
        // a drag is in progress so it can't fight the user's finger.
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            if (isDraggingHandle) return@addOnGlobalLayoutListener
            val previousContainerHeight = containerHeightPx
            refreshContainerHeight()
            if (containerHeightPx != previousContainerHeight && currentHeightPx > containerHeightPx) {
                applyHeight(containerHeightPx)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun refreshContainerHeight() {
        val rootHeight = binding.mainContentRoot.height
        val topBarHeight = binding.topBar.height
        if (rootHeight == 0 || topBarHeight == 0) return

        var available = rootHeight - topBarHeight
        if (binding.pageIndicatorBar.visibility == View.VISIBLE) {
            available -= binding.pageIndicatorBar.height
        }
        if (binding.pythonToolbar.root.visibility == View.VISIBLE) {
            available -= binding.pythonToolbar.root.height
        }
        containerHeightPx = available.coerceAtLeast(handleHeightPx)
    }

    private fun handleDragTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDraggingHandle = true
                refreshContainerHeight()
                dragStartRawY = event.rawY
                dragStartHeightPx = currentHeightPx
                previewingFullOpen = false
            }
            MotionEvent.ACTION_MOVE -> {
                refreshContainerHeight()
                val deltaY = dragStartRawY - event.rawY
                val newHeight = (dragStartHeightPx + deltaY.toInt())
                    .coerceIn(handleHeightPx, containerHeightPx)
                applyHeight(newHeight)

                val fraction = newHeight.toFloat() / containerHeightPx.toFloat()
                if (fraction >= fullOpenThresholdFraction) {
                    if (!previewingFullOpen) {
                        previewingFullOpen = true
                        vibrateOnce()
                        binding.pageIndicatorBar.visibility = View.VISIBLE
                    }
                } else {
                    if (previewingFullOpen) {
                        previewingFullOpen = false
                        binding.pageIndicatorBar.visibility = View.GONE
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingHandle = false
                if (previewingFullOpen) {
                    commitFullOpen()
                } else if (!isSidewaysMode && currentHeightPx < collapseSnapBelowPx) {
                    applyHeight(handleHeightPx)
                }
                previewingFullOpen = false
            }
        }
    }

    private fun applyHeight(newHeightPx: Int) {
        currentHeightPx = newHeightPx
        val params = binding.outputPanel.root.layoutParams as ViewGroup.LayoutParams
        params.height = newHeightPx
        binding.outputPanel.root.layoutParams = params
    }

    fun onRunRequested() {
        refreshContainerHeight()
        binding.outputPanel.root.visibility = View.VISIBLE

        if (isSidewaysMode) {
            if (!isShowingOutputPage) goToOutputPage()
            return
        }

        val collapsedThresholdPx = (containerHeightPx * 0.05f).toInt()
        if (currentHeightPx <= collapsedThresholdPx) {
            val targetHeight = (containerHeightPx * runOpenFractionOfContainer).toInt()
            animateToHeight(targetHeight)
        }
    }

    private fun animateToHeight(targetHeightPx: Int) {
        val start = currentHeightPx
        val steps = 10
        val diff = targetHeightPx - start
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (i in 1..steps) {
            handler.postDelayed({
                applyHeight(start + diff * i / steps)
            }, (i * 18).toLong())
        }
    }

    private fun commitFullOpen() {
        isSidewaysMode = true
        binding.outputPanel.dragHandleTouchArea.visibility = View.GONE
        binding.sidewaysGestureOverlay.visibility = View.VISIBLE
        binding.pageIndicatorBar.visibility = View.VISIBLE

        refreshContainerHeight()
        applyHeight(containerHeightPx)
        binding.editorContainer.translationX = -screenWidth().toFloat()
        binding.outputPanel.root.translationX = 0f
        setActivePage(showingOutput = true)
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
        } catch (t: Throwable) { /* haptics are a nice-to-have */ }
    }

    private fun screenWidth(): Int = activity.resources.displayMetrics.widthPixels

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
                    if (isShowingOutputPage) {
                        val clamped = deltaX.coerceIn(0f, screenWidth().toFloat())
                        binding.outputPanel.root.translationX = clamped
                        binding.editorContainer.translationX = clamped - screenWidth()
                    } else {
                        val clamped = deltaX.coerceIn(-screenWidth().toFloat(), 0f)
                        binding.editorContainer.translationX = clamped
                        binding.outputPanel.root.translationX = clamped + screenWidth()
                    }
                } else if (sidewaysTrackingVertical && isShowingOutputPage) {
                    val clamped = deltaY.coerceIn(0f, containerHeightPx.toFloat())
                    binding.outputPanel.root.translationY = clamped
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when {
                    sidewaysTrackingHorizontal -> {
                        if (isShowingOutputPage) {
                            val committed = binding.outputPanel.root.translationX > screenWidth() * pageCommitFraction
                            if (committed) goToEditorPage() else snapBackToOutputPage()
                        } else {
                            val committed = binding.editorContainer.translationX < -screenWidth() * pageCommitFraction
                            if (committed) goToOutputPage() else snapBackToEditorPage()
                        }
                    }
                    sidewaysTrackingVertical -> {
                        val committed = binding.outputPanel.root.translationY > dp(80)
                        if (committed) exitSidewaysMode()
                        else binding.outputPanel.root.animate().translationY(0f).setDuration(120).start()
                    }
                    else -> {
                        if (!isShowingOutputPage) {
                            onEditorTapPassthrough()
                        }
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
        setActivePage(showingOutput = false)
    }

    private fun snapBackToOutputPage() {
        binding.outputPanel.root.animate().translationX(0f).setDuration(150).start()
        binding.editorContainer.animate().translationX(-screenWidth().toFloat()).setDuration(150).start()
    }

    private fun goToOutputPage() {
        binding.outputPanel.root.animate().translationX(0f).setDuration(150).start()
        binding.editorContainer.animate().translationX(-screenWidth().toFloat()).setDuration(150).start()
        setActivePage(showingOutput = true)
    }

    private fun snapBackToEditorPage() {
        binding.editorContainer.animate().translationX(0f).setDuration(150).start()
        binding.outputPanel.root.animate().translationX(screenWidth().toFloat()).setDuration(150).start()
    }

    private fun setActivePage(showingOutput: Boolean) {
        isShowingOutputPage = showingOutput
        val activeTint = ColorStateList.valueOf(activity.getColor(R.color.dot_active))
        val inactiveTint = ColorStateList.valueOf(activity.getColor(R.color.dot_inactive))
        binding.dotOutput.backgroundTintList = if (showingOutput) activeTint else inactiveTint
        binding.dotEditor.backgroundTintList = if (showingOutput) inactiveTint else activeTint
    }

    private fun exitSidewaysMode() {
        isSidewaysMode = false
        binding.outputPanel.dragHandleTouchArea.visibility = View.VISIBLE
        binding.sidewaysGestureOverlay.visibility = View.GONE
        binding.pageIndicatorBar.visibility = View.GONE
        binding.outputPanel.root.translationY = 0f
        binding.editorContainer.translationX = 0f
        binding.outputPanel.root.translationX = 0f
        isShowingOutputPage = true

        refreshContainerHeight()
        val target = (containerHeightPx * runOpenFractionOfContainer).toInt()
        animateToHeight(target)
    }

    fun resetForNewFileIfNeeded() {
        if (isSidewaysMode) {
            exitSidewaysMode()
        }
    }

    fun isInSidewaysMode(): Boolean = isSidewaysMode
}