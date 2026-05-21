package com.example.utils

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PingUtility {
    private const val TAG = "PingUtility"

    /**
     * Measures the latency to a given host using a TCP Connection handshake on port 443 (HTTPS) or port 80.
     * This is 100% reliable on Android, bypasses ICMP blocks, and represents true connection latency.
     * Returns the latency in milliseconds, or null if the connection fails or times out.
     */
    suspend fun measureTcpPing(host: String): Int? = withContext(Dispatchers.IO) {
        kotlinx.coroutines.withTimeoutOrNull(2000) {
            // Correct IP mappings requested by the user:
            // - AWS Cloud Anycast: (76.223.122.0) and (13.248.118.0)
            // - Middle East (Bahrain): (15.184.148.0) and (15.185.0.0)
            val targets = when {
                host.contains("anycast", ignoreCase = true) -> listOf("76.223.122.0", "13.248.118.0")
                host.contains("me-south-1", ignoreCase = true) -> listOf("15.184.148.0", "15.185.0.0")
                else -> listOf(host)
            }

            var lowestPing: Int? = null

            for (target in targets) {
                val ports = listOf(443, 80)
                for (port in ports) {
                    val startTime = System.currentTimeMillis()
                    var socket: Socket? = null
                    try {
                        socket = Socket()
                        socket.connect(InetSocketAddress(target, port), 1500)
                        val delay = (System.currentTimeMillis() - startTime).toInt()
                        Log.d(TAG, "TCP Ping to $target:$port succeeded: $delay ms")
                        val currentVal = maxOf(1, delay)
                        lowestPing = if (lowestPing == null) currentVal else minOf(lowestPing, currentVal)
                        break // Succeeded on this port, skip other ports for this target IP
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.d(TAG, "TCP Ping to $target:$port timed out")
                    } catch (e: java.net.UnknownHostException) {
                        Log.e(TAG, "TCP Ping to $target failed: Unknown host")
                        break // Stop if DNS resolution itself failed
                    } catch (e: Throwable) {
                        val delay = (System.currentTimeMillis() - startTime).toInt()
                        val msg = e.message ?: ""
                        if (msg.contains("refused", ignoreCase = true) || 
                            msg.contains("reset", ignoreCase = true) || 
                            msg.contains("ConnectException", ignoreCase = true)) {
                            Log.d(TAG, "TCP Ping connection refused/reset by $target:$port but counted as UP: $delay ms")
                            val currentVal = maxOf(1, delay)
                            lowestPing = if (lowestPing == null) currentVal else minOf(lowestPing, currentVal)
                            break
                        }
                        Log.d(TAG, "TCP Ping to $target:$port failed with: ${e.message}")
                    } finally {
                        try {
                            socket?.close()
                        } catch (ignored: Throwable) {}
                    }
                }
            }
            lowestPing
        }
    }
}
