package com.example.pod_android

import android.annotation.SuppressLint
import android.app.Service
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.os.Environment.getExternalStoragePublicDirectory
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.view.View.OnTouchListener
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.pod_android.data.KalmanFilter
import com.example.pod_android.hand.MediapipeHands
import com.example.pod_android.image.CameraSource
import com.example.pod_android.image.VisualizationUtils
import com.example.pod_android.pose.PoseModel
import com.google.mediapipe.formats.proto.LandmarkProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt


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

    private var currentCapture: Bitmap? = null

    class DrawView(context: Context?): View(context) {
        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val paint = Paint()
            paint.color = Color.RED
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 3f
            canvas.drawCircle(15f, 15f, 15f, paint)
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
        windowParams.width = 900
        windowParams.height = 1200
        windowParams.x = 90
        windowParams.y = 150

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
                currentCapture = image.copy(image.config, true)
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

    fun setPoseModel(m: Int) {
        mPoseModel.setModel(m)
    }

    fun setAcceleration(a: Int) {
        mPoseModel.setAccel(a)
    }

    /*! capture and save and photo */
    @SuppressLint("SimpleDateFormat")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveCapture() {
        if (currentCapture != null) {
            val contentValues = ContentValues()
            val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "CAPTURE_${timeStamp}")
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            val fOut = contentResolver.openOutputStream(imageUri!!)!!

            currentCapture?.compress(Bitmap.CompressFormat.JPEG, 85, fOut)
            fOut.flush()
            fOut.close()
        }
    }

    /** floating window */
    /*! translate index finger location (0, 1) to screen location */
    private fun finger2ScreenLoc(x: Float, y: Float): Point {
        var scaledX = (x - 0.5f - orient * 0.15f) / 2.0f * 3.0f + 0.5f
        var scaledY = (y - 0.35f + orient * 0.15f) / 2.0f * 3.0f + 0.5f
        scaledX = if (scaledX < 0.0f) 0.0f else if (scaledX > 1.0f) 1.0f else scaledX
        scaledY = if (scaledY < 0.0f) 0.0f else if (scaledY > 1.0f) 1.0f else scaledY

        return Point((scaledX * screenWidth).toInt(), (scaledY * screenHeight).toInt())
    }

    /*! kalman filter for trajectory smoothing */
    private val mKFx = KalmanFilter(1.0f, 6.0f)
    private val mKFy = KalmanFilter(1.0f, 6.0f)
    /*! set floating cursor */
    private var screenLoc = Point(0, 0)
    private fun setCursor(x: Float, y: Float) {
        val fx = mKFx.filter(x)
        val fy = mKFy.filter(y)
        screenLoc = finger2ScreenLoc(fx, fy)
        pointParams.x = screenLoc.x
        pointParams.y = screenLoc.y
        floatingServiceHandler.post(Runnable {
            windowManager.updateViewLayout(overlay, pointParams)
        })
    }

    /*! floating surface view */
    inner class PreviewSurfaceView {
        private var left: Int = 0
        private var top: Int = 0
        private var right: Int = 0
        private var bottom: Int = 0
        private var defaultImageWidth: Int = 0
        private var defaultImageHeight: Int = 0
        private var defaultCanvasWidth: Int = 0
        private var defaultCanvasHeight: Int = 0
        private fun setRect() {
            val width: Int
            val height: Int
            if (defaultCanvasHeight > defaultCanvasWidth) {
                val ratio = defaultImageHeight.toFloat() / defaultImageWidth
                width = defaultCanvasWidth
                left = 0
                height = (defaultCanvasWidth * ratio).toInt()
                top = (defaultCanvasHeight - height) / 2
            } else {
                val ratio = defaultImageWidth.toFloat() / defaultImageHeight
                height = defaultCanvasHeight
                top = 0
                width = (defaultCanvasHeight * ratio).toInt()
                left = (defaultCanvasWidth - width) / 2
            }
            right = left + width
            bottom = top + height
        }
        fun setPreviewSurfaceView(image: Bitmap) {
            if (defaultImageWidth != image.width || defaultImageHeight != image.height) {
                defaultImageWidth = image.width
                defaultImageHeight = image.height
                setRect()
            }

            val holder = previewSV.holder
            val surfaceCanvas = holder.lockCanvas()
            surfaceCanvas?.let { canvas ->
                if (defaultCanvasWidth != canvas.width || defaultCanvasHeight != canvas.height) {
                    defaultCanvasWidth = canvas.width
                    defaultCanvasHeight = canvas.height
                    setRect()
                }

                canvas.drawBitmap(
                    image, Rect(0, 0, image.width, image.height),
                    Rect(left, top, right, bottom), null)

                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    /*! floating window touch listener */
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

    /*! adjust floating window size */
    fun setCanvasSize(size: Int) {
        when (size) {
            0 -> {
                windowParams.width = 360
                windowParams.height = 480
            }
            1-> {
                windowParams.x = 90
                windowParams.y = 150
                windowParams.width = 900
                windowParams.height = 1200
            }
        }
        windowManager.updateViewLayout(previewSV, windowParams)
    }

    /*! gravity sensor callback */
    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
    override fun onSensorChanged(p0: SensorEvent?) {
        if (p0?.sensor?.type == Sensor.TYPE_GRAVITY) {
            orient = when {
                p0.values[0] > 7.0 -> 1
                p0.values[0] < -7.0 -> -1
                else -> 0
            }
        }
    }

    /** touch related functions */
    /*! simulate touch event */
    private fun mEventGenerator(event: Int, loc: Point = Point(0, 0)) {
        // we need superuser to generate global touch events
        val process = Runtime.getRuntime().exec("su")
        val dataOutputStream = DataOutputStream(process.outputStream)
        when (event) {
            0 -> {
                dataOutputStream.writeBytes("input tap ${loc.x} ${loc.y + 80}\n")
            }
            1 -> {
                dataOutputStream.writeBytes("input keyevent KEYCODE_BACK\n")
            }
            2-> {
                dataOutputStream.writeBytes("input touchscreen swipe ${swipeBeginLoc.x} ${swipeBeginLoc.y} ${loc.x} ${loc.y} 100\n")
                Log.d(TAG, "mEventGenerator: swipe ${swipeBeginLoc.x} ${swipeBeginLoc.y} ${loc.x} ${loc.y}")
            }
        }

        dataOutputStream.flush()
        dataOutputStream.close()
        process.outputStream.close()
    }

    /*! touch detection */
    private enum class StateMachine {
        WAIT, DEFAULT, FIRST_BEND, FIRST_RELEASE, SECOND_BEND, SECOND_RELEASE,
        SWIPE_BEGIN,
    }
    private var pressState = StateMachine.WAIT
    private var pressCount = 0
    private var swipeBeginLoc = Point(0, 0)
    private fun mTouchService(hand: Array<LandmarkProto.NormalizedLandmark>) {
        val bendThreshold = 0.7F
        val releaseThreshold = 0.25F
        val swipeThreshold = 3e-3F
        val vec1dx = hand[1].x - hand[0].x
        val vec1dy = hand[1].y - hand[0].y

        val vec2dx = hand[2].x - hand[1].x
        val vec2dy = hand[2].y - hand[1].y

        val angle = kotlin.math.acos((vec1dx * vec2dx + vec1dy * vec2dy) / sqrt(vec1dx * vec1dx + vec1dy * vec1dy) / sqrt(vec2dx * vec2dx + vec2dy * vec2dy))
        val dis = (hand[3].x - hand[2].x) * (hand[3].x - hand[2].x) + (hand[3].y - hand[2].y) * (hand[3].y - hand[2].y)

        when (pressState) {
            StateMachine.WAIT -> {
                if (angle < releaseThreshold && dis > swipeThreshold) {
                    pressState = StateMachine.DEFAULT
                }
            }
            StateMachine.DEFAULT -> {
                if (angle > bendThreshold) {
                    pressState = StateMachine.FIRST_BEND
                    pressCount = 0
                }

                if (dis < swipeThreshold) {
                    pressState = StateMachine.SWIPE_BEGIN
                    swipeBeginLoc = finger2ScreenLoc(hand[3].x, hand[3].y)
                    pressCount = 0
                }
            }
            StateMachine.FIRST_BEND -> {
                if (angle < releaseThreshold) {
                    pressState = StateMachine.FIRST_RELEASE
                    pressCount = 0
                } else if (pressCount++ > 20) {
                    pressState = StateMachine.WAIT
                }
            }
            StateMachine.FIRST_RELEASE -> {
                if (angle > bendThreshold) {
                    pressState = StateMachine.SECOND_BEND
                    pressCount = 0
                } else if (pressCount++ > 15) {
                    mEventGenerator(0, screenLoc) // press
                    Log.d(TAG, "mTouchService: press")
                    pressState = StateMachine.WAIT
                }
            }
            StateMachine.SECOND_BEND -> {
                if (angle < releaseThreshold) {
                    pressState = StateMachine.SECOND_RELEASE
                    pressCount = 0
                } else if (pressCount++ > 20) {
                    pressState = StateMachine.WAIT
                }
            }
            StateMachine.SECOND_RELEASE -> {
                mEventGenerator(1) // back
                Log.d(TAG, "mTouchService: back")
                pressState = StateMachine.WAIT
            }
            StateMachine.SWIPE_BEGIN -> {
                if (dis < swipeThreshold) {
                    val move = (screenLoc.x - swipeBeginLoc.x) * (screenLoc.x - swipeBeginLoc.x) +
                            (screenLoc.y - swipeBeginLoc.y) * (screenLoc.y - swipeBeginLoc.y)
                    if (move > 1e4 && (pressCount++ > 20 || move > 8e4)) {
                        Log.d(TAG, "mTouchService: swipe $move")
                        mEventGenerator(2, finger2ScreenLoc(hand[3].x, hand[3].y))
                        pressState = StateMachine.WAIT
                    }
                } else {
                    pressState = StateMachine.WAIT
                }
            }
        }
    }

    /*! kalman filter for distance filtering */
    private val mKFDis = KalmanFilter(1.0f, 3.0f)
    /*! process the captured image */
    fun mProcessImage(image: Bitmap) {
        mHandModel.estimateHands(image)
        val persons = mPoseModel.estimatePoses(image, orient)

        // get score for pose detection
        if (persons.isNotEmpty()) {
            if (persons[0].score > 0.3f) {
                // this distance estimation should be calibrated
                var leftEyeY: Float = 0F
                var rightEyeY: Float = 0F
                var leftShoulderY: Float = 0F
                var rightShoulderY: Float = 0F

                if (orient != 0) {
                    leftEyeY = persons[0].keyPoints[1].coordinate.x
                    rightEyeY = persons[0].keyPoints[2].coordinate.x
                    leftShoulderY = persons[0].keyPoints[5].coordinate.x
                    rightShoulderY = persons[0].keyPoints[6].coordinate.x
                } else {
                    leftEyeY = persons[0].keyPoints[1].coordinate.y
                    rightEyeY = persons[0].keyPoints[2].coordinate.y
                    leftShoulderY = persons[0].keyPoints[5].coordinate.y
                    rightShoulderY = persons[0].keyPoints[6].coordinate.y
                }

                var dis = (leftShoulderY + rightShoulderY) / 2.0F - (leftEyeY + rightEyeY) / 2.0F

                dis = 21348 * dis.pow(-1.223F)
                dis = mKFDis.filter(dis)
                val i = Intent(ACTION_UPDATE_DIS)
                i.putExtra("dis", dis)
                sendBroadcast(i)
            } else {
                // no person detected
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
                this.setCursor(li.x, li.y)

                // thumb single/double taps for press/exit
                val thumbLoc = arrayOf(it.multiHandLandmarks()[0].landmarkList[2],
                    it.multiHandLandmarks()[0].landmarkList[3],
                    it.multiHandLandmarks()[0].landmarkList[4],
                    it.multiHandLandmarks()[0].landmarkList[8])

                mTouchService(thumbLoc)
            }
        }
        handBitmap?.let { psv.setPreviewSurfaceView(it) }
    }
}