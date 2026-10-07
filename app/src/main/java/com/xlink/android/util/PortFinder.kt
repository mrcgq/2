
package com.xlink.android.util

import java.net.ServerSocket

object PortFinder {
    fun findFree(startPort: Int = 10808): Int {
        for (port in startPort..65535) {
            try {
                ServerSocket(port).use {
                    return port
                }
            } catch (_: Exception) {}
        }
        return 10808
    }
}
