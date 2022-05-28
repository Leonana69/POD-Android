package com.example.pod_android

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.pod_android.data.Device
import com.example.pod_android.hand.MediapipeHands
import com.example.pod_android.image.CameraSource
import com.example.pod_android.pose.ModelType
import com.example.pod_android.pose.MoveNet
import com.example.pod_android.pose.PoseNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    /** A [SurfaceView] for camera preview.   */
    private lateinit var surfaceView: SurfaceView

    /** Default pose estimation model is 1 (MoveNet Thunder)
     * 0 == MoveNet Lightning model
     * 1 == MoveNet Thunder model
     * 2 == PoseNet model
     **/
    private var modelPos = 0
    /** Default device is 1
     * 0 == CPU
     * 1 == GPU
     * 2 == NNAPI
     **/
    private var devicePos = 1
    private var device = Device.GPU

    private lateinit var tvScore: TextView
    private lateinit var tvFPS: TextView
    private lateinit var mHandsViewFL: FrameLayout
    private lateinit var spnDevice: Spinner
    private lateinit var spnModel: Spinner
    private lateinit var mBtnStart: Button
    private lateinit var mBtnStop: Button
    private var cameraSource: CameraSource? = null

    /** request permission */
    private fun requestPermission() {
        /** request camera permission */
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (isGranted) {
//                    openCamera()
                } else {
                    Toast.makeText(this, "Request camera permission failed", Toast.LENGTH_SHORT).show()
                }
            }

        /** request overlay permission */
        val overlayPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                // the ACTION_MANAGE_OVERLAY_PERMISSION won't return anything
                // do not need to check the resultCode
                if (Settings.canDrawOverlays(this)) {

                } else
                    Toast.makeText(this, "Request overlay permission failed", Toast.LENGTH_SHORT).show()
            }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)

        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "requestPermission: overlay")
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
        }
    }

    var mFloatingService: FloatingService? = null
    var mBounded = false
    private var mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            mBounded = true
            val mFSBinder: FloatingService.FloatingServiceBinder = service as FloatingService.FloatingServiceBinder
            mFloatingService = mFSBinder.getService()
            cameraSource?.setFloatingService(mFloatingService!!)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mBounded = false
            mFloatingService = null
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
        mHandsViewFL = findViewById(R.id.fl_handsView)
        spnModel = findViewById(R.id.spnModel)
        spnDevice = findViewById(R.id.spnDevice)
        surfaceView = findViewById(R.id.surfaceView)
        mBtnStart = findViewById(R.id.btn_start)
        mBtnStop = findViewById(R.id.btn_stop)

        initSpinner()
        spnModel.setSelection(modelPos)
        spnDevice.setSelection(devicePos)
        requestPermission()
    }

    override fun onStart() {
        super.onStart()
        openCamera()

        // start and bind service
        val mIntent = Intent(this, FloatingService::class.java)
        startService(mIntent)
        bindService(mIntent, mConnection, BIND_AUTO_CREATE)
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

    // open camera
    private fun openCamera() {
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
        createPoseDetector()
        createHandDetector()
    }

    // Change model when app is running
    private fun changeModel(position: Int) {
        if (modelPos == position) return
        modelPos = position
        createPoseDetector()
    }

    // Change device (accelerator) type when app is running
    private fun changeDevice(position: Int) {
        if (devicePos == position) return
        devicePos = position

        device = when (position) {
            0 -> Device.CPU
            1 -> Device.GPU
            2 -> Device.NNAPI
            else -> return
        }
        createPoseDetector()
    }

    private var changeModelListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            changeModel(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private var changeDeviceListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            changeDevice(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    // Initialize spinners to let user select model/accelerator.
    private fun initSpinner() {
        ArrayAdapter.createFromResource(this,
            R.array.tfe_pe_models_array, android.R.layout.simple_spinner_item
        ).also { adapter ->
            // Specify the layout to use when the list of choices appears
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // Apply the adapter to the spinner
            spnModel.adapter = adapter
            spnModel.onItemSelectedListener = changeModelListener
        }

        ArrayAdapter.createFromResource(this,
            R.array.tfe_pe_device_name, android.R.layout.simple_spinner_item
        ).also { adaper ->
            adaper.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spnDevice.adapter = adaper
            spnDevice.onItemSelectedListener = changeDeviceListener
        }
    }

    private fun createPoseDetector() {
        val poseDetector = when (modelPos) {
            0 -> { MoveNet.create(this, device, ModelType.Lightning) }
            1 -> { MoveNet.create(this, device, ModelType.Thunder) }
            2 -> { PoseNet.create(this, device) }
            else -> { null }
        }
        poseDetector?.let { detector -> cameraSource?.setPoseDetector(detector) }
    }

    private fun createHandDetector() {
        val handEstimator: MediapipeHands = MediapipeHands(this, mHandsViewFL)
        cameraSource?.setHandDetector(handEstimator)
    }
}