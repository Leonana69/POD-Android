package com.example.pod_android.droneOnUsb

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.example.pod_android.FloatingService
import com.example.pod_android.R
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface

class PodUsbSerialService: Service() {
    var isConnected: Boolean = false
    var mRxMsg: String? = null
    var mDevName: String? = null
    var mDevVendorId: Int = 0
    var mDevProductId: Int = 0

    private val TAG: String = "PodSerial"

    companion object {
        const val ACTION_USB_MSG_RECEIVED: String = "actionUsbMsgReceived"
        const val ACTION_USB_CONNECTED: String = "actionUsbConnected"
        const val ACTION_USB_PERMISSION = "actionUsbPermission"
        const val ACTION_SEND_PACKET = "actionSendPacket"
    }

    /*! usb */
    lateinit var mUsbManager: UsbManager
    private var mDevice: UsbDevice? = null
    private var mSerial: UsbSerialDevice? = null // from felhr library
    private var mConnection: UsbDeviceConnection? = null

    override fun onCreate() {
        isConnected = false
        setFilter()
        mUsbManager = getSystemService(USB_SERVICE) as UsbManager
    }

    override fun onBind(p0: Intent?): IBinder? {
        return UsbBinder()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mUsbBroadcastReceiver)
        Log.d(TAG, "onDestroy: pod usb")
    }

    fun usbStartConnection() {
        val usbDevices: HashMap<String, UsbDevice>? = mUsbManager.deviceList
        if (!isConnected && !usbDevices?.isEmpty()!!) {
            usbDevices.forEach() { entry ->
                mDevice = entry.value
                val intent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
                mUsbManager.requestPermission(mDevice, intent)
            }
        } else {
            if (isConnected)
                Toast.makeText(applicationContext, "Already connected", Toast.LENGTH_SHORT).show()
            else
                Toast.makeText(applicationContext, "No device is connected", Toast.LENGTH_SHORT).show()
        }
    }

    fun usbSendData(data: String) {
        if (!isConnected) {
            Log.i(TAG, "no device is connected")
        } else {
            mSerial?.write(data.toByteArray())
            Log.i(TAG, "Send data: " + data.toByteArray())
        }
    }

    fun usbSendData(data: ByteArray) {
        if (!isConnected) {
            Log.i(TAG, "no device is connected")
        } else {
            mSerial?.write(data)
            Log.i(TAG, "Send data: $data")
        }
    }

    // set binder for mainActivity to get instance
    inner class UsbBinder : Binder() {
        fun getService() = this@PodUsbSerialService
    }

    private fun setFilter() {
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(mUsbBroadcastReceiver, filter)
    }

    private val usbReceiveCallback: UsbSerialInterface.UsbReadCallback =
        UsbSerialInterface.UsbReadCallback { data ->
            try {
                mRxMsg = String(data!!)
                this.sendBroadcast(Intent(ACTION_USB_MSG_RECEIVED))
                Toast.makeText(applicationContext, "msg", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.i(TAG, "error in receiving message")
            }
        }

    fun usbEndConnection() {
        isConnected = false
        mSerial?.close()
        val i = Intent(ACTION_USB_CONNECTED).apply {
            putExtra("dev", "None")
            sendBroadcast(this)
        }
    }

    private val mUsbBroadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            when (p1?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted: Boolean =
                        p1.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    // p1.extras!!.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
                    if (granted) {
                        Log.i(TAG, "permission granted")
                        isConnected = true
                        mDevice = p1.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        mConnection = mUsbManager.openDevice(mDevice)
                        mSerial = UsbSerialDevice.createUsbSerialDevice(mDevice, mConnection)

                        mDevName = mDevice?.productName!!
                        mDevVendorId = mDevice?.vendorId!!
                        mDevProductId = mDevice?.productId!!

                        val i = Intent(ACTION_USB_CONNECTED).apply {
                            putExtra("dev", mDevName)
                            sendBroadcast(this)
                        }

                        if (mSerial!!.open()) {
                            Log.i(TAG, "port open [SUCCESS]")
                            mSerial!!.setBaudRate(115200)
                            mSerial!!.setDataBits(UsbSerialDevice.DATA_BITS_8)
                            mSerial!!.setStopBits(UsbSerialDevice.STOP_BITS_1)
                            mSerial!!.setParity(UsbSerialDevice.PARITY_NONE)
                            mSerial!!.setFlowControl(UsbSerialDevice.FLOW_CONTROL_OFF)
                            mSerial!!.read(usbReceiveCallback)
                        } else {
                            Log.i(TAG, "port open [FAILED]")
                        }
                    } else {
                        Log.i(TAG, "permission not granted")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "device attached")
                    usbStartConnection()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "device detached")
                    usbEndConnection()
                }

                ACTION_SEND_PACKET -> {
                    val vx = p1.getFloatExtra("vx", 0.0f)
                    val vy = p1.getFloatExtra("vy", 0.0f)
                    val vyaw = p1.getFloatExtra("vyaw", 0.0f)
                    val h = p1.getFloatExtra("height", 0.2f)
                    val cp = CommanderHoverPacket(vx, vy, vyaw, h).apply {
                        usbSendData((this as CrtpPacket).toByteArray())
                    }
                }
            }
        }
    }
}