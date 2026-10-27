package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Android has a default network that reaches the internet now
 * ([io.github.matiyaaa.fuse.ui.shell.store.FuseServices.online]). A handheld woken from sleep finds
 * its Wi-Fi a few seconds after the screen comes back: that moment, not the next try a minute later,
 * is when a download the sleep stopped carries on.
 */
internal object NetworkWatch {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private var started = false

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        _online.value = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.reaches() } ?: false }.getOrDefault(true)
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.reaches()
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        } catch (e: RuntimeException) {
            // Too many callbacks, or not allowed: transfers try again by themselves within a minute.
        }
    }

    private fun NetworkCapabilities.reaches() = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
