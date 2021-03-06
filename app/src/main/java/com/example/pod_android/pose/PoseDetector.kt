package com.example.pod_android.pose

import android.graphics.Bitmap
import com.example.pod_android.data.Person

interface PoseDetector : AutoCloseable {

    fun estimatePoses(bitmap: Bitmap, orient: Int): List<Person>

    fun lastInferenceTimeNanos(): Long

}