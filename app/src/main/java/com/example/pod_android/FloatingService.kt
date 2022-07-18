package com.example.pod_android

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.View.OnTouchListener
import android.widget.Toast
import com.example.pod_android.data.BodyPart
import com.example.pod_android.droneOnUsb.PodUsbSerialService
import com.example.pod_android.hand.MediapipeHands
import com.example.pod_android.image.CameraSource
import com.example.pod_android.image.VisualizationUtils
import com.example.pod_android.pose.PoseModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.DataOutputStream

class FloatingService : Service(), SensorEventListener {
    companion object {
        const val ACTION_UPDATE_FPS: String = "actionUpdateFps"
        const val ACTION_UPDATE_DIS: String = "actionUpdateDis"

    }
    private val TAG: String = "FloatingService"

    private lateinit var pointParams: WindowManager.LayoutParams
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var windowManager: WindowManager

    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var floatingServiceHandler: Handler

    private var orient = 0
    private lateinit var mPoseModel: PoseModel
    private lateinit var mHandModel: MediapipeHands

    private lateinit var overlay: View
    private lateinit var previewSV: SurfaceView
    private val psv = PreviewSurfaceView()
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

        // detect phone rotation
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY), SensorManager.SENSOR_DELAY_NORMAL)

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
        var scaledX = (x - 0.5f) / 9.0f * 10.0f + 0.5f
        var scaledY = (y - 0.5f) / 9.0f * 10.0f + 0.5f
        scaledX = if (scaledX < 0.0f) 0.0f else if (scaledX > 1.0f) 1.0f else scaledX
        scaledY = if (scaledY < 0.0f) 0.0f else if (scaledY > 1.0f) 1.0f else scaledY

        pointParams.x = (scaledX * screenWidth).toInt()
        pointParams.y = (scaledY * screenHeight).toInt()

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

    inner class PreviewSurfaceView {
        private var left: Int = 0
        private var top: Int = 0
        private var right: Int = 0
        private var bottom: Int = 0
        private var defaultImageWidth: Int = 0
        private var defaultImageHeight: Int = 0
        fun setPreviewSurfaceView(image: Bitmap) {
            val holder = previewSV.holder
            val surfaceCanvas = holder.lockCanvas()
            surfaceCanvas?.let { canvas ->
                if (defaultImageWidth != image.width || defaultImageHeight != image.height) {
                    defaultImageWidth = image.width
                    defaultImageHeight = image.height
                    val screenWidth: Int
                    val screenHeight: Int

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
                    right = left + screenWidth
                    bottom = top + screenHeight
                }

                canvas.drawBitmap(
                    image, Rect(0, 0, image.width, image.height),
                    Rect(left, top, right, bottom), null)
                holder.unlockCanvasAndPost(canvas)
            }
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

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}

    override fun onSensorChanged(p0: SensorEvent?) {
        if (p0?.sensor?.type == Sensor.TYPE_GRAVITY) {
            orient = if (p0.values[0] > 7.0) 1
            else if (p0.values[0] < -7.0) -1
            else 0
        }
    }

    private var pressCount = 0
    fun mProcessImage(image: Bitmap) {
        mHandModel.estimateHands(image)

        val persons = mPoseModel.estimatePoses(image, orient)

        // get score for pose detection
        if (persons.isNotEmpty()) {
            if (persons[0].score > 0.3f) {
                // this distance estimation should be calibrated
                val leftEyeY = persons[0].keyPoints[1].coordinate.y
                val rightEyeY = persons[0].keyPoints[2].coordinate.y
                val leftShoulderY = persons[0].keyPoints[5].coordinate.y
                val rightShoulderY = persons[0].keyPoints[6].coordinate.y
                var dis = (leftShoulderY + rightShoulderY) / 2.0f - (leftEyeY + rightEyeY) / 2.0f
                dis = 110 - dis / 3.0f
                val i = Intent(ACTION_UPDATE_DIS)
                i.putExtra("dis", dis)
                sendBroadcast(i)
            } else {
                // no person
                val i = Intent(ACTION_UPDATE_DIS)
                i.putExtra("dis", -1F)
                sendBroadcast(i)
            }
        }

        val bodyBitmap = VisualizationUtils.drawBodyKeyPoints(image, persons.filter { it.score > 0.2f })
        var handBitmap: Bitmap? = null
        mHandModel.handsResult?.let {
            handBitmap = VisualizationUtils.drawHandKeyPoints(bodyBitmap, it)
            if (it.multiHandLandmarks().size > 0) {
                val li = it.multiHandLandmarks()[0].landmarkList[8] // index tip
                val lm = it.multiHandLandmarks()[0].landmarkList[12] // middle tip
                this.setCursor(li.x, li.y)

                val dis = (lm.x - li.x) * (lm.x - li.x) + (lm.y - li.y) * (lm.y - li.y)
                if (dis < 0.001f && pressCount > 15) {
                    val tx = (li.x * screenWidth).toInt()
                    val ty = (li.y * screenHeight).toInt()
                    // we need superuser to generate global touch events
//                    val process = Runtime.getRuntime().exec("su")
//                    val dataOutputStream = DataOutputStream(process.outputStream)
//                    dataOutputStream.writeBytes("input tap $tx $ty\n")
//                    dataOutputStream.flush()
//                    dataOutputStream.close()
//                    process.outputStream.close()
//                    pressCount = 0
                }
                pressCount++
            }
        }
        handBitmap?.let { psv.setPreviewSurfaceView(it) }
    }
}