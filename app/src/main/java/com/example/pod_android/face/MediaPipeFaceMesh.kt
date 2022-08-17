package com.example.pod_android.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.solutions.facemesh.FaceMesh
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions
import com.google.mediapipe.solutions.facemesh.FaceMeshResult

class MediaPipeFaceMesh(private val context: Context) {
    companion object {
        private const val TAG = "MediaPipe Face Mesh"
    }

    private var faceMesh = FaceMesh(
        context,
        FaceMeshOptions.builder()
            .setStaticImageMode(false)
            .setRefineLandmarks(true)
            .setRunOnGpu(true)
            .build()
    )

    private var done: Boolean = true
    var faceMeshResult: FaceMeshResult? = null

    init {
        Log.d(TAG, "init hand detector")
        // Connects MediaPipe Hands solution to the user-defined HandsResultImageView.
        faceMesh.setResultListener { faceMeshResult : FaceMeshResult? ->
            this.faceMeshResult = faceMeshResult
            done = true
        }
        faceMesh.setErrorListener { message: String, _: RuntimeException? -> Log.e(TAG, "MediaPipe Face Mesh Error:$message") }
    }

    fun estimateFaceMesh(bitmap: Bitmap) {
        if (done) {
            done = false
            faceMesh.send(bitmap, System.currentTimeMillis())
        }
    }

    fun close() {
        faceMesh.close()
    }
}