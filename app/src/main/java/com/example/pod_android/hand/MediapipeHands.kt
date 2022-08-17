package com.example.pod_android.hand

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import com.google.mediapipe.solutions.hands.HandsResult

class MediaPipeHands(private val context: Context) {
    companion object {
        private const val TAG = "Mediapipe Hands"
    }

    private var hands: Hands = Hands(
        context,
        HandsOptions.builder()
            .setStaticImageMode(false)
            .setMaxNumHands(2)
            .setRunOnGpu(true)
            .build()
    )

//    private val glSurfaceView =
//        SolutionGlSurfaceView<HandsResult>(context, hands.glContext, hands.glMajorVersion).apply {
//            setSolutionResultRenderer(HandsResultGlRenderer())
//            setRenderInputImage(true)
//        }

    private var done: Boolean = true
    var handsResult: HandsResult? = null

    init {
        Log.d(Companion.TAG, "init hand detector")
        // Connects MediaPipe Hands solution to the user-defined HandsResultImageView.
        hands.setResultListener { handsResult: HandsResult? ->
            this.handsResult = handsResult
            done = true
        }
        hands.setErrorListener { message: String, _: RuntimeException? -> Log.e(Companion.TAG, "MediaPipe Hands error:$message") }
    }

    fun estimateHands(bitmap: Bitmap) {
        if (done) {
            done = false
            hands.send(bitmap, System.currentTimeMillis())
        }
    }

    fun close() {
        hands.close()
    }
}