package com.example.androidautodisplay

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ProjectionResolutionOption(
    val key: String,
    val width: Int,
    val height: Int,
    val nativeCode: Int,
    val frameWidth: Int = width,
    val frameHeight: Int = height,
    val marginWidth: Int = 0,
    val marginHeight: Int = 0
)

object ProjectionResolutionOptions {
    private val fixed16x9 = listOf(
        ProjectionResolutionOption(Constants.PROJECTION_RESOLUTION_480P, 800, 480, 1),
        ProjectionResolutionOption(Constants.PROJECTION_RESOLUTION_720P, 1280, 720, 2),
        ProjectionResolutionOption(Constants.PROJECTION_RESOLUTION_1080P, 1920, 1080, 3)
    )

    fun resolve(context: Context, qualityKey: String?, useNativeAspect: Boolean): ProjectionResolutionOption {
        val fixed = fixed16x9.firstOrNull { it.key == qualityKey }
            ?: fixed16x9.first { it.key == Constants.DEFAULT_PROJECTION_RESOLUTION }
        if (!useNativeAspect) {
            return fixed
        }

        val (screenWidth, screenHeight) = screenSize(context)
        val landscapeWidth = max(screenWidth, screenHeight).coerceAtLeast(1)
        val landscapeHeight = min(screenWidth, screenHeight).coerceAtLeast(1)
        val screenAspect = landscapeWidth.toFloat() / landscapeHeight.toFloat()
        val frameAspect = fixed.frameWidth.toFloat() / fixed.frameHeight.toFloat()
        val (activeWidth, activeHeight) = if (screenAspect > frameAspect) {
            val height = roundToEven((fixed.frameWidth / screenAspect).roundToInt()).coerceAtLeast(2)
            fixed.frameWidth to height.coerceAtMost(fixed.frameHeight)
        } else {
            val width = roundToEven((fixed.frameHeight * screenAspect).roundToInt()).coerceAtLeast(2)
            width.coerceAtMost(fixed.frameWidth) to fixed.frameHeight
        }
        return fixed.copy(
            width = activeWidth,
            height = activeHeight,
            marginWidth = (fixed.frameWidth - activeWidth).coerceAtLeast(0),
            marginHeight = (fixed.frameHeight - activeHeight).coerceAtLeast(0)
        )
    }

    fun isKnownQualityKey(key: String?): Boolean {
        return fixed16x9.any { it.key == key }
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
            .getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun roundToEven(value: Int): Int {
        return if (value % 2 == 0) value else value - 1
    }
}
