package com.starvationridge.pit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.starvationridge.pit.RidgeApp
import com.starvationridge.pit.ble.ScanService
import com.starvationridge.pit.engine.RaceRepository

@Composable
private fun PitNav(
    repository: RaceRepository,
    bluetoothGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!bluetoothGranted) {
        PermissionScreen(onAllow = onRequestPermissions, onOpenSettings = onOpenSettings)
        return
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    if (showSettings) {
        SettingsScreen(repository = repository, onClose = { showSettings = false })
    } else {
        RaceScreen(repository = repository, onOpenSettings = { showSettings = true })
    }
}

class MainActivity : ComponentActivity() {
    private val repository: RaceRepository by lazy { (application as RidgeApp).repository }
    private var bluetoothGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        bluetoothGranted = hasBluetoothPermission()
        if (bluetoothGranted) startScanService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        bluetoothGranted = hasBluetoothPermission()
        if (!bluetoothGranted) {
            permissionLauncher.launch(requiredPermissions())
        }
        setContent {
            PitNav(
                repository = repository,
                bluetoothGranted = bluetoothGranted,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                onOpenSettings = { openAppSettings(this) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        hideSystemBars()
        if (hasBluetoothPermission()) {
            bluetoothGranted = true
            startScanService()
        }
    }

    private fun startScanService() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun hasBluetoothPermission(): Boolean {
        return requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = requiredBluetoothPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun requiredBluetoothPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
