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
import com.example.pod_android.droneOnUsb.CommanderHoverPacket
import com.example.pod_android.droneOnUsb.CommanderPacket
import com.example.pod_android.droneOnUsb.CrtpPacket
import com.example.pod_android.droneOnUsb.PodUsbSerialService
import com.example.pod_android.hand.MediapipeHands
import com.example.pod_android.image.CameraSource
import com.example.pod_android.pose.ModelType
import com.example.pod_android.pose.MoveNet
import com.example.pod_android.pose.PoseNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.w3c.dom.Text
import kotlin.math.log

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private val TAG = "MainActivity"
    private val defaultModelPosition = 2
    private val defaultAccelPosition = 1

    private lateinit var tvScore: TextView
    private lateinit var tvFPS: TextView
    private lateinit var tvUsbDev: TextView
    private lateinit var spnDevice: Spinner
    private lateinit var spnModel: Spinner
    private lateinit var mBtnStart: Button
    private lateinit var mBtnStop: Button
    private lateinit var mBtnCnt: Button

    private lateinit var mIntentFS: Intent
    private lateinit var mIntentPUSS: Intent

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
    var mPodUsbSerialService: PodUsbSerialService? = null
    var mBounded = false

    private var mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            when (name.shortClassName) {
                ".FloatingService" -> {
                    Log.d(TAG, "onServiceConnected: float service")
                    val mFSBinder: FloatingService.FloatingServiceBinder = service as FloatingService.FloatingServiceBinder
                    mFloatingService = mFSBinder.getService()
                    mBounded = true
                }

                ".droneOnUsb.PodUsbSerialService" -> {
                    Log.d(TAG, "onServiceConnected: pod usb")
                    val mPUSSBinder: PodUsbSerialService.UsbBinder = service as PodUsbSerialService.UsbBinder
                    mPodUsbSerialService = mPUSSBinder.getService()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // keep screen on while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // find elements
        tvScore = findViewById(R.id.tvScore)
        tvFPS = findViewById(R.id.tvFps)
        tvUsbDev = findViewById(R.id.tvUsbDev)
        spnModel = findViewById(R.id.spnModel)
        spnDevice = findViewById(R.id.spnDevice)
        mBtnStart = findViewById(R.id.btn_start)
        mBtnStop = findViewById(R.id.btn_stop)
        mBtnCnt = findViewById(R.id.btn_cnt)

        mBtnStart.setOnClickListener(this)
        mBtnStop.setOnClickListener(this)
        mBtnCnt.setOnClickListener(this)

        initSpinner()
        spnModel.setSelection(defaultModelPosition)
        spnDevice.setSelection(defaultAccelPosition)

        val filter = IntentFilter()
        filter.addAction(FloatingService.ACTION_UPDATE_FPS)
        filter.addAction(FloatingService.ACTION_UPDATE_DIS)
        filter.addAction(PodUsbSerialService.ACTION_USB_CONNECTED)
        registerReceiver(mBroadcastReceiver, filter)

        mIntentPUSS = Intent(this, PodUsbSerialService::class.java)
        startService(mIntentPUSS)
        bindService(mIntentPUSS, mConnection, BIND_AUTO_CREATE)

        requestPermission()
    }

    override fun onStart() {
        super.onStart()
        mFloatingService?.setCanvasSize(1)
    }

    override fun onStop() {
        super.onStop()
        mFloatingService?.setCanvasSize(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mBroadcastReceiver)
        unbindService(mConnection)
        stopService(mIntentPUSS)
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
                    mIntentFS = Intent(this, FloatingService::class.java)
                    startService(mIntentFS)
                    bindService(mIntentFS, mConnection, BIND_AUTO_CREATE)
                }
            }
            R.id.btn_stop -> {
                if (mBounded) {
                    unbindService(mConnection)
                    stopService(mIntentFS)
                    mFloatingService = null
                    mBounded = false
                }
            }
            R.id.btn_cnt -> {
                mPodUsbSerialService?.usbStartConnection()
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

                FloatingService.ACTION_UPDATE_DIS -> {
                    val dis: Float = p1.getFloatExtra("dis", 0.0f)
                    tvScore.text = getString(R.string.tfe_pe_tv_dis, dis)
                    if (dis == -1F) {
                        // keep the height
//                        val cp: CommanderPacket = CommanderPacket(0F, 0F, 0F, 2000u)
                        val cp: CommanderHoverPacket = CommanderHoverPacket(0F, 0F, 0F, 0.5F)
                        mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
                    } else if (dis < 60) {
//                        val cp: CommanderPacket = CommanderPacket(0F, 0F, 0F, 1000u)
                        val cp: CommanderHoverPacket = CommanderHoverPacket(-0.1F, 0F, 0F, 0.5F)
                        mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
                    } else if (dis > 80) {
//                        val cp: CommanderPacket = CommanderPacket(0F, 0F, 0F, 3000u)
                        val cp: CommanderHoverPacket = CommanderHoverPacket(0.1F, 0F, 0F, 0.5F)
                        mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
                    } else {
//                        val cp: CommanderPacket = CommanderPacket(0F, 0F, 0F, 2000u)
                        val cp: CommanderHoverPacket = CommanderHoverPacket(0F, 0F, 0F, 0.5F)
                        mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
                    }
                }

                PodUsbSerialService.ACTION_USB_CONNECTED -> {
                    val dev: String? = p1.getStringExtra("dev")
                    tvUsbDev.text = getString(R.string.usb_dev, dev)
                }
            }
        }
    }
}