package io.github.matiyaaa.fuse.platform

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.SystemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Battery, Wi-Fi, network and Bluetooth state for the status area, from system broadcasts and
 * network callbacks (no polling). Anything Android does not report to Fuse stays UNKNOWN or null.
 * Bluetooth reports only on/off: which devices are connected needs BLUETOOTH_CONNECT, which Fuse
 * does not request.
 */
class SystemStatusMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val bluetooth: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _status = MutableStateFlow(SystemStatus())
    val status: StateFlow<SystemStatus> = _status.asStateFlow()

    /** Last sticky battery intent, for the temperature metric. */
    @Volatile var batteryTemperatureC: Float? = null
        private set

    private var started = false
    private var wifiNetwork: Network? = null

    fun start() {
        if (started) return
        started = true
        registerBattery()
        registerWifi()
        registerDefaultNetwork()
        registerBluetooth()
    }

    private fun registerBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
        }
        val sticky = ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::onBattery)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        batteryTemperatureC = if (temperature != Int.MIN_VALUE && present) temperature / 10f else null
        val percent = if (present && level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        _status.update { it.copy(batteryPercent = percent, charging = percent != null && charging) }
    }

    private fun registerWifi() {
        val cm = connectivity ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                _status.update { it.copy(wifi = ConnectionState.CONNECTED) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                wifiNetwork = network
                _status.update { it.copy(wifi = ConnectionState.CONNECTED, wifiStrength = signalLevel(caps)) }
            }

            override fun onLost(network: Network) {
                if (wifiNetwork == network) wifiNetwork = null
                _status.update { it.copy(wifi = wifiRadioState(), wifiStrength = null) }
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            // Too many callbacks or no permission: Wi-Fi stays as the radio state says.
        }
        _status.update { it.copy(wifi = if (wifiNetwork != null) ConnectionState.CONNECTED else wifiRadioState()) }

        val radio = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (wifiNetwork == null) _status.update { it.copy(wifi = wifiRadioState(), wifiStrength = null) }
            }
        }
        ContextCompat.registerReceiver(
            appContext, radio, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun wifiRadioState(): ConnectionState = try {
        when (wifiManager?.isWifiEnabled) {
            true -> ConnectionState.ON
            false -> ConnectionState.OFF
            null -> ConnectionState.UNKNOWN
        }
    } catch (e: SecurityException) {
        ConnectionState.UNKNOWN
    }

    /** Signal level 0..4 from the network's signal strength (RSSI), when Android reports it. */
    private fun signalLevel(caps: NetworkCapabilities): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rssi = caps.signalStrength
        if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) return null
        val wm = wifiManager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val max = wm.maxSignalLevel.coerceAtLeast(1)
            (wm.calculateSignalLevel(rssi) * 4 / max).coerceIn(0, 4)
        } else {
            @Suppress("DEPRECATION")
            WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
        }
    }

    private fun registerDefaultNetwork() {
        val cm = connectivity ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _status.update { it.copy(network = if (online) ConnectionState.CONNECTED else ConnectionState.ON) }
            }

            override fun onLost(network: Network) {
                _status.update { it.copy(network = ConnectionState.OFF) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            if (cm.activeNetwork == null) _status.update { it.copy(network = ConnectionState.OFF) }
        } catch (e: RuntimeException) {
            // Network state stays unknown.
        }
    }

    private fun registerBluetooth() {
        val adapter = bluetooth ?: run {
            _status.update { it.copy(bluetooth = ConnectionState.UNKNOWN) }
            return
        }
        fun read(): ConnectionState = try {
            if (adapter.isEnabled) ConnectionState.ON else ConnectionState.OFF
        } catch (e: SecurityException) {
            ConnectionState.UNKNOWN
        }
        _status.update { it.copy(bluetooth = read()) }
        val receiver = object : BroadcastReceiver() {
            // The broadcast only triggers a re-read; its extras are not trusted.
            override fun onReceive(context: Context, intent: Intent) {
                _status.update { it.copy(bluetooth = read()) }
            }
        }
        // Sent by the Bluetooth app, not the system, so the receiver must be exported to get it.
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
