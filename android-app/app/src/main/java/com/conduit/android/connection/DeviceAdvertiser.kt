package com.conduit.android.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises this phone on the local network via NSD (mDNS / Bonjour) so the Mac can find it.
 * Service type is [SERVICE_TYPE]; the instance name carries the human device name.
 */
class DeviceAdvertiser(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int, deviceId: String, deviceName: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "Tether ${deviceName.take(40)}"
            serviceType = "$SERVICE_TYPE."
            setPort(port)
            // TXT records let the Mac show name/id before connecting.
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
            setAttribute("v", PROTOCOL_VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            // Block bodies on purpose: Log.i/e return Int, so `=` expression bodies would make these
            // override Int instead of the Unit the RegistrationListener interface requires.
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "registered ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.e(TAG, "register failed $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) { Log.i(TAG, "unregistered") }
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) { Log.e(TAG, "unregister failed $code") }
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    private companion object { const val TAG = "TetherNSD" }
}
