package com.xlink.android.vpn

import android.net.VpnService
import android.util.Log

interface ProtectCallback {
    fun protect(fd: Int): Boolean
}

class VpnServiceProtectCallback(
    private val vpnService: VpnService
) : ProtectCallback {

    companion object {
        private const val TAG = "ProtectCallback"
    }

    override fun protect(fd: Int): Boolean {
        return try {
            val result = vpnService.protect(fd)
            if (!result) {
                Log.w(TAG, "protect(fd=$fd) returned false — socket may loop")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "protect(fd=$fd) threw exception: ${e.message}")
            false
        }
    }
}

class NullProtectCallback : ProtectCallback {
    override fun protect(fd: Int): Boolean = true
}
