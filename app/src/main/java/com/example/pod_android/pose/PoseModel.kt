package com.example.pod_android.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.pod_android.data.Device
import com.example.pod_android.data.Person
import kotlin.math.log

class PoseModel(private val context: Context) {
    private var model = 0
    private var acceleration = 1
    private var accelDevice: Device = Device.GPU
    private var poseDetector: PoseDetector? = PoseNet.create(context, accelDevice)
    private var lock = Any()

    fun setModel(m: Int) {
        if (m != model) {
            model = m
            createModel()
        }
    }

    fun setAccel(a: Int) {
        if (a != acceleration) {
            acceleration = a
            accelDevice = when(acceleration) {
                0 -> { Device.CPU }
                1 -> { Device.GPU }
                2 -> { Device.NNAPI }
                else -> { Device.CPU }
            }
            createModel()
        }
    }

    private fun createModel() {
        synchronized(lock) {
            poseDetector?.close()
            poseDetector = when (model) {
                0 -> { MoveNet.create(context, accelDevice, ModelType.Lightning) }
                1 -> { MoveNet.create(context, accelDevice, ModelType.Thunder) }
                2 -> { PoseNet.create(context, accelDevice) }
                else -> { null }
            }
        }
    }

    fun estimatePoses(bitmap: Bitmap): List<Person> {
        val persons = mutableListOf<Person>()
        synchronized(lock) {
            poseDetector?.estimatePoses(bitmap)?.let {
                persons.addAll(it)
            }
        }
        return persons
    }

    fun close() {
        poseDetector?.close()
        poseDetector = null
    }
}