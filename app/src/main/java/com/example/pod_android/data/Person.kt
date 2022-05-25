package com.example.pod_android.data

import android.graphics.PointF
import android.graphics.RectF
enum class BodyPart(val position: Int) {
    NOSE(0),
    LEFT_EYE(1),
    RIGHT_EYE(2),
    LEFT_EAR(3),
    RIGHT_EAR(4),
    LEFT_SHOULDER(5),
    RIGHT_SHOULDER(6),
    LEFT_ELBOW(7),
    RIGHT_ELBOW(8),
    LEFT_WRIST(9),
    RIGHT_WRIST(10),
    LEFT_HIP(11),
    RIGHT_HIP(12),
    LEFT_KNEE(13),
    RIGHT_KNEE(14),
    LEFT_ANKLE(15),
    RIGHT_ANKLE(16);
    companion object{
        private val map = values().associateBy(BodyPart::position)
        fun fromInt(position: Int): BodyPart = map.getValue(position)
    }
}

data class KeyPoint(val bodyPart: BodyPart, var coordinate: PointF, val score: Float)

data class Person(
    var id: Int = -1, // default id is -1
    val keyPoints: List<KeyPoint>,
    val boundingBox: RectF? = null, // Only MoveNet MultiPose return bounding box.
    val score: Float
)

data class TorsoAndBodyDistance(
    val maxTorsoYDistance: Float,
    val maxTorsoXDistance: Float,
    val maxBodyYDistance: Float,
    val maxBodyXDistance: Float
)