package com.example.pod_android

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private val TAG = "MainActivity"
    private val defaultModelPosition = 2
    private val defaultAccelPosition = 1

    private lateinit var tvScore: TextView
    private lateinit var tvFPS: TextView
    private lateinit var spnDevice: Spinner
    private lateinit var spnModel: Spinner
    private lateinit var mBtnStart: Button
    private lateinit var mBtnStop: Button

    private lateinit var mServiceIntent: Intent

    /** request permission */
    private fun requestPermission() {
        /** request camera permission */
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (isGranted) {
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
        }

        override fun onServiceDisconnected(name: ComponentName) {
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
        spnModel = findViewById(R.id.spnModel)
        spnDevice = findViewById(R.id.spnDevice)
        mBtnStart = findViewById(R.id.btn_start)
        mBtnStop = findViewById(R.id.btn_stop)

        mBtnStart.setOnClickListener(this)
        mBtnStop.setOnClickListener(this)

        initSpinner()
        spnModel.setSelection(defaultModelPosition)
        spnDevice.setSelection(defaultAccelPosition)
        requestPermission()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter()
        filter.addAction(FloatingService.ACTION_UPDATE_FPS)
        filter.addAction(FloatingService.ACTION_UPDATE_SCORE)
        registerReceiver(mBroadcastReceiver, filter)
    }

    private var changeModelListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            mFloatingService?.setPoseModel(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private var changeDeviceListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            mFloatingService?.setAcceleration(position)
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

    override fun onClick(p0: View?) {
        when (p0?.id) {
            R.id.btn_start -> {
                if (!mBounded) {
                    mServiceIntent = Intent(this, FloatingService::class.java)
                    startService(mServiceIntent)
                    bindService(mServiceIntent, mConnection, BIND_AUTO_CREATE)
                }
            }
            R.id.btn_stop -> {
                if (mBounded) {
                    unbindService(mConnection)
                    stopService(mServiceIntent)
                    mBounded = false
                }
            }
        }
    }

    private val mBroadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            when (p1?.action) {
                FloatingService.ACTION_UPDATE_FPS -> {
                    val fps: Int = p1.getIntExtra("fps", 0)
                    tvFPS.text = getString(R.string.tfe_pe_tv_fps, fps)
                }

                FloatingService.ACTION_UPDATE_SCORE -> {
                    val score: Float = p1.getFloatExtra("score", 0.0f)
                    tvScore.text = getString(R.string.tfe_pe_tv_score, score)
                }
            }
        }
    }
}