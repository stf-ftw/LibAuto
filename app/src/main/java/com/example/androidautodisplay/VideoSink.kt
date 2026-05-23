package com.example.androidautodisplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.SurfaceHolder
import android.view.SurfaceView

class VideoSink(surfaceView: SurfaceView) : SurfaceHolder.Callback {
    private val holder = surfaceView.holder
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var frame = 0

    init {
        holder.addCallback(this)
    }

    fun startTest() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    drawFrame(canvas)
                    holder.unlockCanvasAndPost(canvas)
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    running = false
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun drawFrame(canvas: Canvas) {
        frame += 1
        val color = if (frame % 2 == 0) Color.rgb(15, 25, 30) else Color.rgb(30, 15, 20)
        canvas.drawColor(color)
        canvas.drawText("Video test frame $frame", 24f, 64f, paint)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // no-op
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // no-op
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }
}
