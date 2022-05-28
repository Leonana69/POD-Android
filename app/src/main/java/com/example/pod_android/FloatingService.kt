package com.example.pod_android

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager


class FloatingService : Service() {
    private val TAG = "FloatingService"
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var windowManager: WindowManager
    private lateinit var overlay: View
    private var screenWidth = 0
    private var screenHeight = 0

    class DrawView(context: Context?): View(context) {
        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val paint = Paint() //设置一个笔刷大小是3的黄色的画笔
            paint.color = Color.RED
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 3f
            // 创建画笔
            val xLoc = 15.toFloat()
            val yLoc = 15.toFloat()
            canvas.drawCircle(xLoc, yLoc, 15f, paint)
        }
    }
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = DrawView(applicationContext)
        params = WindowManager.LayoutParams()
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params.format = PixelFormat.RGBA_8888
        params.gravity = Gravity.START or Gravity.TOP
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.width = 200
        params.height = 200
        params.x = 0
        params.y = 0
        windowManager.addView(overlay, params)

        val displayMetrics = applicationContext.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
    }

    inner class FloatingServiceBinder : Binder() {
        fun getService() = this@FloatingService
    }

    override fun onBind(intent: Intent): IBinder {
        return FloatingServiceBinder()
    }

    fun setCursor(x: Float, y: Float) {
        params.x = (x * screenWidth).toInt()
        params.y = (y * screenHeight).toInt()
        Log.d(TAG, "setCursor: " + params.x + " " + params.y)
        windowManager.updateViewLayout(overlay, params)
    }
}