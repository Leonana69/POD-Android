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


class MediapipeHands(private val context: Context, private val mHandsViewFL: FrameLayout) {
    private val TAG = "MediapipeHands"
    private var hands: Hands = Hands(
        context,
        HandsOptions.builder()
            .setStaticImageMode(false)
            .setMaxNumHands(2)
            .setRunOnGpu(true)
            .build()
    )

    private val glSurfaceView =
        SolutionGlSurfaceView<HandsResult>(context, hands.glContext, hands.glMajorVersion).apply {
            setSolutionResultRenderer(HandsResultGlRenderer())
            setRenderInputImage(true)
        }

    private var done: Boolean = true
    private val extraVisualize = false
    var handsResult: HandsResult? = null

    init {
        Log.d(TAG, "init")
        // Connects MediaPipe Hands solution to the user-defined HandsResultImageView.
        hands.setResultListener { handsResult: HandsResult? ->
            this.handsResult = handsResult
            if (extraVisualize) {
                glSurfaceView.setRenderData(handsResult)
                glSurfaceView.requestRender()
            }
            done = true
        }
        hands.setErrorListener { message: String, _: RuntimeException? -> Log.e(TAG, "MediaPipe Hands error:$message") }
        if (extraVisualize) {
            mHandsViewFL.removeAllViewsInLayout()
            mHandsViewFL.addView(glSurfaceView)
            glSurfaceView.visibility = View.VISIBLE
            mHandsViewFL.requestLayout()
        }
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