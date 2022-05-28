package com.example.pod_android.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.example.pod_android.data.BodyPart
import com.example.pod_android.data.Person
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsResult

object VisualizationUtils {
    private fun paintCircle(c: Int) = Paint().apply {
        strokeWidth = CIRCLE_RADIUS
        color = c
        style = Paint.Style.FILL
    }
    private fun paintLine(c: Int) = Paint().apply {
        strokeWidth = LINE_WIDTH
        color = c
        style = Paint.Style.STROKE
    }
    private fun paintText(c: Int) = Paint().apply {
        textSize = PERSON_ID_TEXT_SIZE
        color = c
        textAlign = Paint.Align.LEFT
    }
    /** Radius of circle used to draw keypoints.  */
    private const val CIRCLE_RADIUS = 6f
    /** Width of line used to connected two keypoints.  */
    private const val LINE_WIDTH = 4f
    /** The text size of the person id that will be displayed when the tracker is available.  */
    private const val PERSON_ID_TEXT_SIZE = 30f
    /** Distance from person id to the nose keypoint.  */
    private const val PERSON_ID_MARGIN = 6f

    /** Pair of keypoints to draw lines between.  */
    private val bodyJoints = listOf(
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        Pair(BodyPart.NOSE, BodyPart.LEFT_SHOULDER),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )

    private const val LEFT_HAND_COLOR: Int = 0xffffff00.toInt()
    private const val RIGHT_HAND_COLOR: Int = 0xff00ff00.toInt()
    private const val BODY_COLOR: Int = 0xffff0000.toInt()
    private const val DEBUG_COLOR: Int = 0xff00ffff.toInt()
    private const val LANDMARK_RADIUS = 0.008f
    private const val NUM_SEGMENTS = 120
    fun drawHandKeyPoints(
        input: Bitmap,
        hands: HandsResult,
        isTrackerEnabled: Boolean = false
    ): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val originalSizeCanvas = Canvas(output)
        val width = output.width
        val height = output.height
        val numHands: Int = hands.multiHandLandmarks().size
        for (i in 0 until numHands) {
            val isLeftHand = hands.multiHandedness()[i].label == "Left"
            Log.d("TAG", "drawHandKeyPoints: " + isLeftHand)
            for (c in Hands.HAND_CONNECTIONS) {
                val start: NormalizedLandmark = hands.multiHandLandmarks()[i].landmarkList[c.start()]
                val end: NormalizedLandmark = hands.multiHandLandmarks()[i].landmarkList[c.end()]
                originalSizeCanvas.drawLine(start.x * width, start.y * height, end.x * width, end.y * height,
                    paintLine(if (isLeftHand) LEFT_HAND_COLOR else RIGHT_HAND_COLOR))
            }
            for (landmark in hands.multiHandLandmarks()[i].landmarkList) {
                originalSizeCanvas.drawCircle(landmark.x * width, landmark.y * height, CIRCLE_RADIUS,
                    paintCircle(if (isLeftHand) LEFT_HAND_COLOR else RIGHT_HAND_COLOR))
            }
        }
        return output
    }

    // Draw line and point indicate body pose
    fun drawBodyKeyPoints(
        input: Bitmap,
        persons: List<Person>,
    ): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val originalSizeCanvas = Canvas(output)
        persons.forEach { person ->
            bodyJoints.forEach {
                val pointA = person.keyPoints[it.first.position].coordinate
                val pointB = person.keyPoints[it.second.position].coordinate
                originalSizeCanvas.drawLine(pointA.x, pointA.y, pointB.x, pointB.y, paintLine(BODY_COLOR))
            }

            person.keyPoints.forEach { point ->
                originalSizeCanvas.drawCircle(
                    point.coordinate.x,
                    point.coordinate.y,
                    CIRCLE_RADIUS,
                    paintCircle(BODY_COLOR)
                )
            }
        }
        return output
    }
}
