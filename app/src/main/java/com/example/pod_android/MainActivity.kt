package com.example.pod_android

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Process
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.pod_android.data.Device
import com.example.pod_android.image.CameraSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    /** A [SurfaceView] for camera preview.   */
    private lateinit var surfaceView: SurfaceView

    /** Default pose estimation model is 1 (MoveNet Thunder)
     * 0 == MoveNet Lightning model
     * 1 == MoveNet Thunder model
     * 2 == PoseNet model
     **/
    private var modelPos = 0
    /** Default device is GPU */
    private var devicePos = 1
    private var device = Device.GPU

    private lateinit var tvScore: TextView
    private lateinit var tvFPS: TextView
    private lateinit var spnDevice: Spinner
    private lateinit var spnModel: Spinner
    private var cameraSource: CameraSource? = null

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted.
                openCamera()
            } else {
                ErrorDialog.newInstance(getString(R.string.tfe_pe_request_permission))
                    .show(supportFragmentManager, "Dialog")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // keep screen on while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // find elements
        tvScore = findViewById(R.id.tvScore)
        tvFPS = findViewById(R.id.tvFps)
        spnModel = findViewById(R.id.spnModel)
        spnDevice = findViewById(R.id.spnDevice)
        surfaceView = findViewById(R.id.surfaceView)
        initSpinner()
        spnModel.setSelection(modelPos)
        spnDevice.setSelection(devicePos)
        if (!isCameraPermissionGranted())
            requestPermission()
    }

    override fun onStart() {
        super.onStart()
        openCamera()
    }

    override fun onResume() {
        cameraSource?.resume()
        super.onResume()
    }

    override fun onPause() {
        cameraSource?.close()
        cameraSource = null
        super.onPause()
    }

    // check if permission is granted or not.
    private fun isCameraPermissionGranted(): Boolean {
        return checkPermission(
            Manifest.permission.CAMERA,
            Process.myPid(),
            Process.myUid()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) -> {
                // You can use the API that requires the permission.
                openCamera()
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                requestPermissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // open camera
    private fun openCamera() {
        if (!isCameraPermissionGranted())
            return
        if (cameraSource == null) {
            cameraSource =
                CameraSource(this, surfaceView, object : CameraSource.CameraSourceListener {
                    override fun onFPSListener(fps: Int) {
                        tvFPS.text = getString(R.string.tfe_pe_tv_fps, fps)
                    }

                    override fun onDetectedInfo(personScore: Float?) {
                        tvScore.text = getString(R.string.tfe_pe_tv_score, personScore ?: 0f)
                    }

                })
            lifecycleScope.launch(Dispatchers.Main) {
                cameraSource?.initCamera()
            }
        }
        createPoseEstimator()
    }

    // Change model when app is running
    private fun changeModel(position: Int) {
        if (modelPos == position) return
        modelPos = position
        createPoseEstimator()
    }

    // Change device (accelerator) type when app is running
    private fun changeDevice(position: Int) {
        if (devicePos == position) return
        devicePos = position

        val targetDevice = when (position) {
            0 -> Device.CPU
            1 -> Device.GPU
            2 -> Device.NNAPI
            else -> return
        }

        if (device == targetDevice) return
        device = targetDevice
        createPoseEstimator()
    }

    private var changeModelListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {}

        override fun onItemSelected(
            parent: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) { changeModel(position) }
    }

    private var changeDeviceListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            changeDevice(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private var changeTrackerListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
//            changeTracker(position)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    // Initialize spinners to let user select model/accelerator/tracker.
    private fun initSpinner() {
        ArrayAdapter.createFromResource(
            this,
            R.array.tfe_pe_models_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            // Specify the layout to use when the list of choices appears
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // Apply the adapter to the spinner
            spnModel.adapter = adapter
            spnModel.onItemSelectedListener = changeModelListener
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.tfe_pe_device_name, android.R.layout.simple_spinner_item
        ).also { adaper ->
            adaper.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            spnDevice.adapter = adaper
            spnDevice.onItemSelectedListener = changeDeviceListener
        }
    }

    private fun createPoseEstimator() {
        val poseDetector = when (modelPos) {
            0 -> {
                // MoveNet Lightning (SinglePose)
                showDetectionScore(true)
                MoveNet.create(this, device, ModelType.Lightning)
            }
            1 -> {
                // MoveNet Thunder (SinglePose)
                showDetectionScore(true)
                MoveNet.create(this, device, ModelType.Thunder)
            }
            2 -> {
                // PoseNet (SinglePose)
                showDetectionScore(true)
                PoseNet.create(this, device)
            }
            else -> {
                null
            }
        }
        poseDetector?.let { detector -> cameraSource?.setDetector(detector) }
    }

    // Show/hide the detection score.
    private fun showDetectionScore(isVisible: Boolean) {
        tvScore.visibility = if (isVisible) View.VISIBLE else View.GONE
    }

    /**
     * Shows an error message dialog.
     */
    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            AlertDialog.Builder(activity)
                .setMessage(requireArguments().getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // do nothing
                }
                .create()

        companion object {

            @JvmStatic
            private val ARG_MESSAGE = "message"

            @JvmStatic
            fun newInstance(message: String): ErrorDialog = ErrorDialog().apply {
                arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
            }
        }
    }
}