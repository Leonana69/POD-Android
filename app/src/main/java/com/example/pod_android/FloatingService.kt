package com.example.pod_android

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.View.OnTouchListener
import android.widget.Toast
import com.example.pod_android.hand.MediapipeHands
import com.example.pod_android.image.CameraSource
import com.example.pod_android.image.VisualizationUtils
import com.example.pod_android.pose.PoseModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FloatingService : Service() {
    companion object {
        const val ACTION_UPDATE_FPS: String = "actionUpdateFps"
        const val ACTION_UPDATE_SCORE: String = "actionUpdateScore"
    }

    private val TAG = "FloatingService"
    private lateinit var pointParams: WindowManager.LayoutParams
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var windowManager: WindowManager

    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var floatingServiceHandler: Handler

    private lateinit var mPoseModel: PoseModel
    private lateinit var mHandModel: MediapipeHands

    private lateinit var overlay: View
    private lateinit var previewSV: SurfaceView
    private lateinit var cameraSource: CameraSource
    private val job = SupervisorJob()
    private val mCoroutineScope = CoroutineScope(Dispatchers.IO + job)

    class DrawView(context: Context?): View(context) {
        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val paint = Paint()
            paint.color = Color.CYAN
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 3f
            val xLoc = 15.toFloat()
            val yLoc = 15.toFloat()
            canvas.drawCircle(xLoc, yLoc, 15f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        val displayMetrics = applicationContext.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        floatingServiceHandler = Handler(Looper.getMainLooper())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        pointParams = WindowManager.LayoutParams()
        pointParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        pointParams.format = PixelFormat.RGBA_8888
        pointParams.gravity = Gravity.START or Gravity.TOP
        pointParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        pointParams.width = 30
        pointParams.height = 30
        pointParams.x = 0
        pointParams.y = 0

        windowParams = WindowManager.LayoutParams()
        windowParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        windowParams.format = PixelFormat.RGBA_8888
        windowParams.gravity = Gravity.START or Gravity.TOP
        windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowParams.width = 360
        windowParams.height = 480
        windowParams.x = 300
        windowParams.y = 300

        overlay = DrawView(this)
        previewSV = SurfaceView(this)
        previewSV.setOnTouchListener(FloatingOnTouchListener());
        windowManager.addView(overlay, pointParams)
        windowManager.addView(previewSV, windowParams)

        mPoseModel = PoseModel(applicationContext)
        mHandModel = MediapipeHands(applicationContext)

        cameraSource = CameraSource(this, object: CameraSource.CameraSourceListener {
            override fun processImage(image: Bitmap) {
                mProcessImage(image)
            }

            override fun onFPSListener(fps: Int) {
                val i = Intent(ACTION_UPDATE_FPS)
                i.putExtra("fps", fps)
                sendBroadcast(i)
            }
        })
        mCoroutineScope.launch {
            cameraSource.initCamera()
        }
    }

    inner class FloatingServiceBinder : Binder() {
        fun getService() = this@FloatingService
    }

    override fun onBind(intent: Intent): IBinder {
        return FloatingServiceBinder()
    }

    override fun onDestroy() {
        super.onDestroy()
        Toast.makeText(applicationContext, "Stop Service", Toast.LENGTH_SHORT).show()
        cameraSource.closeCamera()
        mPoseModel.close()
        mHandModel.close()
        windowManager.removeView(overlay)
        windowManager.removeView(previewSV)
        job.cancel()
    }

    private fun setCursor(x: Float, y: Float) {
        pointParams.x = (x * screenWidth).toInt()
        pointParams.y = (y * screenHeight).toInt()
        Log.d(TAG, "setCursor: " + pointParams.x + " " + pointParams.y)

        floatingServiceHandler.post(Runnable {
            windowManager.updateViewLayout(overlay, pointParams)
        })
    }

    fun setPoseModel(m: Int) {
        mPoseModel.setModel(m)
    }

    fun setAcceleration(a: Int) {
        mPoseModel.setAccel(a)
    }

    private fun setPreviewSurfaceView(image: Bitmap) {
        val holder = previewSV.holder
        val surfaceCanvas = holder.lockCanvas()
        surfaceCanvas?.let { canvas ->
            val screenWidth: Int
            val screenHeight: Int
            val left: Int
            val top: Int

            if (canvas.height > canvas.width) {
                val ratio = image.height.toFloat() / image.width
                screenWidth = canvas.width
                left = 0
                screenHeight = (canvas.width * ratio).toInt()
                top = (canvas.height - screenHeight) / 2
            } else {
                val ratio = image.width.toFloat() / image.height
                screenHeight = canvas.height
                top = 0
                screenWidth = (canvas.height * ratio).toInt()
                left = (canvas.width - screenWidth) / 2
            }
            val right: Int = left + screenWidth
            val bottom: Int = top + screenHeight

            canvas.drawBitmap(
                image, Rect(0, 0, image.width, image.height),
                Rect(left, top, right, bottom), null
            )
            holder.unlockCanvasAndPost(canvas)
        }
    }

    inner class FloatingOnTouchListener : OnTouchListener {
        private var x = 0
        private var y = 0
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    x = event.rawX.toInt()
                    y = event.rawY.toInt()
                }
                MotionEvent.ACTION_MOVE -> {
                    val nowX = event.rawX.toInt()
                    val nowY = event.rawY.toInt()
                    val movedX = nowX - x
                    val movedY = nowY - y
                    x = nowX
                    y = nowY
                    windowParams.x = windowParams.x + movedX
                    windowParams.y = windowParams.y + movedY
                    windowManager.updateViewLayout(view, windowParams)
                }
                else -> {}
            }
            return true
        }
    }

    fun mProcessImage(image: Bitmap) {
        val persons = mPoseModel.estimatePoses(image)
        mHandModel.estimateHands(image)

        // get score for pose detection
        if (persons.isNotEmpty()) {
            val i = Intent(ACTION_UPDATE_SCORE)
            i.putExtra("score", persons[0].score)
            sendBroadcast(i)
        }

        val bodyBitmap = VisualizationUtils.drawBodyKeyPoints(image, persons.filter { it.score > 0.2f })
        var handBitmap: Bitmap? = null
        mHandModel.handsResult?.let {
            handBitmap = VisualizationUtils.drawHandKeyPoints(
                bodyBitmap,
                it
            )
            if (it.multiHandLandmarks().size > 0) {
                val lm = it.multiHandLandmarks()[0].landmarkList[8]
                this.setCursor(lm.x, lm.y)
            }
        }
        handBitmap?.let { setPreviewSurfaceView(it) }
    }
}