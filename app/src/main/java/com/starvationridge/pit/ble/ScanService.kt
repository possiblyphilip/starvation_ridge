package com.starvationridge.pit.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.starvationridge.pit.R
import com.starvationridge.pit.RidgeApp
import com.starvationridge.pit.engine.AdvertisementParser
import com.starvationridge.pit.engine.BeaconSighting
import com.starvationridge.pit.engine.IBeaconParser
import com.starvationridge.pit.engine.RaceRepository
import com.starvationridge.pit.ui.MainActivity

class ScanService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val repository: RaceRepository by lazy { (application as RidgeApp).repository }
    private var wakeLock: PowerManager.WakeLock? = null
    private var scanning = false
    private var running = false
    private val loggedBeacons = HashSet<String>()

    @Volatile
    private var lastResultAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            repository.tick(System.currentTimeMillis())
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val watchScan = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val silentFor = if (lastResultAt == 0L) 0L else now - lastResultAt
            val runningFor = if (scanStartedAt == 0L) 0L else now - scanStartedAt
            if (silentFor > SILENT_RESTART_MS || runningFor > RESTART_EVERY_MS) {
                Log.i(TAG, "restarting scan, silent ${silentFor}ms, up ${runningFor}ms")
                startScan()
            }
            handler.postDelayed(this, WATCH_MS)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startScan()
                BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    repository.setStatus("Turn Bluetooth on")
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastResultAt = System.currentTimeMillis()
            val record = result.scanRecord ?: return
            val mac = try {
                result.device.address ?: return
            } catch (_: SecurityException) {
                return
            }
            val sighting = AdvertisementParser.parse(record.bytes) ?: sightingFromRecord(record) ?: return
            val advertisedName = sighting.name.ifBlank {
                try {
                    record.deviceName ?: ""
                } catch (_: SecurityException) {
                    ""
                }
            }
            if (loggedBeacons.add("beacon:$mac")) {
                Log.i(TAG, "beacon mac=$mac name=$advertisedName uuid=${sighting.uuid} rssi=${result.rssi}")
            }
            val seenAt = System.currentTimeMillis()
            handler.post {
                repository.noteBeacon(
                    mac,
                    advertisedName,
                    sighting.uuid,
                    sighting.major,
                    sighting.minor,
                    result.rssi,
                    seenAt,
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            repository.setStatus("Bluetooth scan failed ($errorCode). Retrying.")
            handler.postDelayed({ if (running) startScan() }, RETRY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (!running) {
            running = true
            acquireWakeLock()
            handler.post(tick)
            handler.post(watchScan)
        }
        startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        wakeLock?.let { if (it.isHeld) it.release() }
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun startScan() {
        if (!hasScanPermission()) {
            repository.setStatus("Allow Bluetooth to hear the bike")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            repository.setStatus("Turn Bluetooth on")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            repository.setStatus("This tablet has no Bluetooth scanner")
            return
        }
        stopScan()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            val started = System.currentTimeMillis()
            scanStartedAt = started
            lastResultAt = started
            repository.setStatus(null)
        } catch (_: SecurityException) {
            repository.setStatus("Allow Bluetooth to hear the bike")
        } catch (_: IllegalStateException) {
            repository.setStatus("Turn Bluetooth on")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
        scanning = false
    }

    private fun sightingFromRecord(record: ScanRecord): BeaconSighting? {
        var serviceHit = record.serviceUuids.orEmpty().any { parcel ->
            parcel.uuid.toString().startsWith("00002080")
        }
        val eddystoneFrames = setOf(0x00, 0x10, 0x20, 0x21, 0x22, 0x30)
        for ((parcel, bytes) in record.serviceData.orEmpty()) {
            val uuid = parcel.uuid.toString()
            if (uuid.startsWith("00002080")) serviceHit = true
            if (uuid.startsWith("0000feaa") && bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) in eddystoneFrames) {
                serviceHit = true
            }
        }
        var uuid = ""
        var major = -1
        var minor = -1
        var kkm = false
        val data = record.manufacturerSpecificData
        if (data != null) {
            for (index in 0 until data.size()) {
                val companyId = data.keyAt(index)
                val bytes = data.valueAt(index) ?: continue
                val ibeacon = IBeaconParser.parse(companyId, bytes)
                if (ibeacon != null) {
                    uuid = ibeacon.uuid
                    major = ibeacon.major
                    minor = ibeacon.minor
                }
                if (companyId == IBeaconParser.KKM_COMPANY_ID) kkm = true
            }
        }
        val name = record.deviceName ?: ""
        val named = name.uppercase().let { it.startsWith("KB") || it.startsWith("BC") || it.contains("BEACON") }
        if (uuid.isBlank() && !kkm && !serviceHit && !named) return null
        return BeaconSighting(name, uuid, major, minor)
    }

    private fun hasScanPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pit:scan").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Bike scanner", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pit lap is listening")
            .setContentText("Watching for the bike beacon")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "PitScan"
        private const val CHANNEL_ID = "pit-scan"
        private const val NOTIFICATION_ID = 7
        private const val TICK_MS = 500L
        private const val RETRY_MS = 3_000L
        private const val WATCH_MS = 5_000L
        private const val SILENT_RESTART_MS = 12_000L
        private const val RESTART_EVERY_MS = 120_000L

        private var scanStartedAt = 0L
    }
}