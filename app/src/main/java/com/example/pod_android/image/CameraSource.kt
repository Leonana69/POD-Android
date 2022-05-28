package com.example.pod_android.image

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.example.pod_android.FloatingService
import com.example.pod_android.pose.PoseDetector
import com.example.pod_android.data.Person
import com.example.pod_android.hand.MediapipeHands
import com.google.android.renderscript.Toolkit
import com.google.android.renderscript.YuvFormat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraSource(
    private val context: Context,
    private val surfaceView: SurfaceView,
    private val listener: CameraSourceListener? = null
) {
    companion object {
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480

        /** Threshold for confidence score. */
        private const val MIN_CONFIDENCE = .2f
        private const val TAG = "Camera Source"
    }

    /** init variables */
    private val lock = Any()
    private val cameraFacing = CameraCharacteristics.LENS_FACING_FRONT
    private var poseDetector: PoseDetector? = null
    private var handDetector: MediapipeHands? = null
    private var floatingService: FloatingService? = null
    private lateinit var imageBitmap: Bitmap

    /** Frame count that have been processed so far in an one second interval to calculate FPS. */
    private var fpsTimer: Timer? = null
    private var frameProcessedInOneSecondInterval = 0
    private var framesPerSecond = 0

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = surfaceView.context
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** Readers used as buffers for camera still shots */
    private var imageReader: ImageReader? = null
    /** The [CameraDevice] that will be opened in this fragment */
    private var camera: CameraDevice? = null
    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private var session: CameraCaptureSession? = null

    /** [HandlerThread] where all buffer reading operations run */
    private var imageReaderThread: HandlerThread? = null
    /** [Handler] corresponding to [imageReaderThread] */
    private var imageReaderHandler: Handler? = null
    private var cameraId: String = ""

    /** open the camera */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (cont.isActive) cont.resumeWithException(Exception("Camera error"))
                }
            }, imageReaderHandler)
        }

    fun setPoseDetector(poseDetector: PoseDetector) {
        synchronized(lock) {
            this.poseDetector?.close()
            this.poseDetector = poseDetector
        }
    }

    fun setHandDetector(handDetector: MediapipeHands) {
        this.handDetector = handDetector
    }

    fun setFloatingService(fs: FloatingService) {
        this.floatingService = fs
    }

    suspend fun initCamera() {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection != null && cameraDirection == cameraFacing) {
                this.cameraId = cameraId
                break
            }
        }

        camera = openCamera(cameraManager, cameraId)
        imageReader = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()

            if (image != null) {
                val imageBA = imageToByteArray(image)
                imageBitmap = Toolkit.yuvToRgbBitmap(imageBA, image.cropRect.width(), image.cropRect.height(), YuvFormat.NV21)

                // Create rotated version for portrait display
                val transMatrix = Matrix()
                if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT)
                    transMatrix.postScale(-1f, 1f)
                transMatrix.postRotate(90.0f)

                imageBitmap = Bitmap.createBitmap(
                    imageBitmap, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    transMatrix, false
                )
                processImage(imageBitmap)
                image.close()
            }

        }, imageReaderHandler)

        imageReader?.surface?.let { surface ->
            val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(surface)),
                ContextCompat.getMainExecutor(context),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        val cameraRequest = camera?.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        )?.apply {
                            addTarget(surface)
                        }

                        cameraRequest?.build()?.let { session?.setRepeatingRequest(it, null, null) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.d(TAG, "onConfigureFailed")
                    }
                })
            camera?.createCaptureSession(sessionConfiguration)
        }
    }

    fun resume() {
        // create a background thread to deal with camera image
        imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
        imageReaderHandler = Handler(imageReaderThread!!.looper)
        fpsTimer = Timer()
        fpsTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    framesPerSecond = frameProcessedInOneSecondInterval
                    listener?.onFPSListener(framesPerSecond)
                    frameProcessedInOneSecondInterval = 0
                }
            },
            0,
            1000
        )
    }

    fun close() {
        session?.close()
        session = null
        camera?.close()
        camera = null
        imageReader?.close()
        imageReader = null
        stopImageReaderThread()
        poseDetector?.close()
        poseDetector = null
        handDetector?.close()
        handDetector = null
        fpsTimer?.cancel()
        fpsTimer = null
        frameProcessedInOneSecondInterval = 0
        framesPerSecond = 0
    }

    // process image for classification
    private fun processImage(bitmap: Bitmap) {
        val persons = mutableListOf<Person>()

        synchronized(lock) {
            poseDetector?.estimatePoses(bitmap)?.let {
                persons.addAll(it)
            }
        }

        handDetector?.estimateHands(bitmap)

        // if the model returns only one item, show that item's score.
        if (persons.isNotEmpty()) {
            listener?.onDetectedInfo(persons[0].score)
        }

        visualize(persons, bitmap)
        frameProcessedInOneSecondInterval++
    }

    private fun visualize(persons: List<Person>, bitmap: Bitmap) {
        val bodyBitmap = VisualizationUtils.drawBodyKeyPoints(
            bitmap,
            persons.filter { it.score > MIN_CONFIDENCE })

        var handBitmap: Bitmap? = null
        handDetector?.handsResult?.let {
            handBitmap = VisualizationUtils.drawHandKeyPoints(
                bodyBitmap,
                it
            )
            if (it.multiHandLandmarks().size > 0) {
                Log.d("Floating", "visualize: run")
                val lm = it.multiHandLandmarks()[0].landmarkList[8]
                floatingService?.setCursor(lm.x, lm.y)
            }
        }

        val holder = surfaceView.holder
        val surfaceCanvas = holder.lockCanvas()
        surfaceCanvas?.let { canvas ->
            val screenWidth: Int
            val screenHeight: Int
            val left: Int
            val top: Int

            if (canvas.height > canvas.width) {
                val ratio = bodyBitmap.height.toFloat() / bodyBitmap.width
                screenWidth = canvas.width
                left = 0
                screenHeight = (canvas.width * ratio).toInt()
                top = (canvas.height - screenHeight) / 2
            } else {
                val ratio = bodyBitmap.width.toFloat() / bodyBitmap.height
                screenHeight = canvas.height
                top = 0
                screenWidth = (canvas.height * ratio).toInt()
                left = (canvas.width - screenWidth) / 2
            }
            val right: Int = left + screenWidth
            val bottom: Int = top + screenHeight

            canvas.drawBitmap(
                handBitmap ?: bodyBitmap, Rect(0, 0, bodyBitmap.width, bodyBitmap.height),
                Rect(left, top, right, bottom), null
            )

            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun imageToByteArray(image: Image): ByteArray {
        assert(image.format == ImageFormat.YUV_420_888)
        val pixelCount: Int = image.cropRect.width() * image.cropRect.height()
        val imageCrop = image.cropRect
        val imagePlanes = image.planes
        val rowData = ByteArray(imagePlanes.first().rowStride)
        val outputBuffer = ByteArray((image.cropRect.width() * image.cropRect.height() * 1.5).toInt())
        imagePlanes.forEachIndexed { planeIndex, plane ->

            // How many values are read in input for each output value written
            // Only the Y plane has a value for every pixel, U and V have half the resolution i.e.
            //
            // Y Plane            U Plane    V Plane
            // ===============    =======    =======
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            val outputStride: Int

            // The index in the output buffer the next value will be written at
            // For Y it's zero, for U and V we start at the end of Y and interleave them i.e.
            //
            // First chunk        Second chunk
            // ===============    ===============
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y    U V U V U V U V
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            var outputOffset: Int

            when (planeIndex) {
                0 -> {
                    outputStride = 1
                    outputOffset = 0
                }
                1 -> {
                    outputStride = 2
                    outputOffset = pixelCount + 1
                }
                2 -> {
                    outputStride = 2
                    outputOffset = pixelCount
                }
                else -> {
                    // Image contains more than 3 planes, something strange is going on
                    return@forEachIndexed
                }
            }

            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // We have to divide the width and height by two if it's not the Y plane
            val planeCrop = if (planeIndex == 0) {
                imageCrop
            } else {
                Rect(
                    imageCrop.left / 2,
                    imageCrop.top / 2,
                    imageCrop.right / 2,
                    imageCrop.bottom / 2
                )
            }

            val planeWidth = planeCrop.width()
            val planeHeight = planeCrop.height()

            buffer.position(rowStride * planeCrop.top + pixelStride * planeCrop.left)
            for (row in 0 until planeHeight) {
                val length: Int
                if (pixelStride == 1 && outputStride == 1) {
                    // When there is a single stride value for pixel and output, we can just copy
                    // the entire row in a single step
                    length = planeWidth
                    buffer.get(outputBuffer, outputOffset, length)
                    outputOffset += length
                } else {
                    // When either pixel or output have a stride > 1 we must copy pixel by pixel
                    length = (planeWidth - 1) * pixelStride + 1
                    buffer.get(rowData, 0, length)
                    for (col in 0 until planeWidth) {
                        outputBuffer[outputOffset] = rowData[col * pixelStride]
                        outputOffset += outputStride
                    }
                }

                if (row < planeHeight - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }
        return outputBuffer
    }

    private fun stopImageReaderThread() {
        imageReaderThread?.quitSafely()
        try {
            imageReaderThread?.join()
            imageReaderThread = null
            imageReaderHandler = null
        } catch (e: InterruptedException) {
            Log.d(TAG, e.message.toString())
        }
    }

    // for fps and detection info update
    interface CameraSourceListener {
        fun onFPSListener(fps: Int)
        fun onDetectedInfo(personScore: Float?)
    }
}