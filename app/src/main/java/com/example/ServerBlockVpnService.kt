package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class ServerBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.START_BLOCK"
        const val ACTION_STOP = "com.example.STOP_BLOCK"
        const val EXTRA_BLOCKED_HOSTS = "blocked_hosts"
        const val EXTRA_BLOCKED_REGIONS = "blocked_regions"
        
        const val CHANNEL_ID = "efootball_vpn_service_channel"
        const val VPN_NOTIFICATION_ID = 4133

        var isRunning = false
            private set

        fun startService(context: Context, blockedHosts: ArrayList<String>, blockedRegions: ArrayList<String>) {
            val intent = Intent(context, ServerBlockVpnService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_BLOCKED_HOSTS, blockedHosts)
                putStringArrayListExtra(EXTRA_BLOCKED_REGIONS, blockedRegions)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ServerBlockVpn", "Failed to start VPN as foreground: ${e.message}. Trying background fallback...")
                try {
                    context.startService(intent)
                } catch (ex: Exception) {
                    Log.e("ServerBlockVpn", "Fatal: Failed to start VPN service: ${ex.message}")
                }
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ServerBlockVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ServerBlockVpn", "Failed to stop VPN as foreground: ${e.message}. Trying background fallback...")
                try {
                    context.startService(intent)
                } catch (ex: Exception) {
                    Log.e("ServerBlockVpn", "Fatal: Failed to stop VPN service: ${ex.message}")
                }
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var udpChannel: kotlinx.coroutines.channels.Channel<UdpForwardTask>? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ServerBlockVpn", "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    VPN_NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(VPN_NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Throwable) {
            Log.e("ServerBlockVpn", "Failed to startForeground with type: ${e.message}", e)
            try {
                startForeground(VPN_NOTIFICATION_ID, buildNotification())
            } catch (ex: Throwable) {
                Log.e("ServerBlockVpn", "Failed to startForeground fallback: ${ex.message}", ex)
                // If foreground setup fails completely, gracefully stop self to prevent system crash
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    val hosts = intent.getStringArrayListExtra(EXTRA_BLOCKED_HOSTS) ?: arrayListOf()
                    val regions = intent.getStringArrayListExtra(EXTRA_BLOCKED_REGIONS) ?: arrayListOf()
                    startBlocking(hosts, regions)
                }
                ACTION_STOP -> {
                    stopBlocking()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "eFootball 0 Ping Premium Firewall",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Dedicated Game Optimization Firewall & Traffic Shaper active."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        val blockBgApps = prefs.getBoolean("block_bg_apps", false)
        val speedLimiterActive = prefs.getBoolean("speed_limit_enabled", false)
        val maxDl = prefs.getFloat("download_speed_limit", 5.0f)
        val maxUl = prefs.getFloat("upload_speed_limit", 2.0f)

        var statusMessage = "Firewall blocking unselected servers."
        if (blockBgApps) {
            statusMessage = "All background apps internet restricted! Focus Mode ON."
        }
        if (speedLimiterActive) {
            statusMessage += " Limiters: DL ${maxDl}M / UL ${maxUl}M."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_card_title))
            .setContentText(statusMessage)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startBlocking(hosts: ArrayList<String>, regions: ArrayList<String>) {
        stopBlocking()
        isRunning = true

        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        val blockBgApps = prefs.getBoolean("block_bg_apps", false)
        val isSpeedLimiterActive = prefs.getBoolean("speed_limit_enabled", false)
        val downloadMb = prefs.getFloat("download_speed_limit", 5.0f)
        val uploadMb = prefs.getFloat("upload_speed_limit", 2.0f)

        Log.d("ServerBlockVpn", "VPN starting. BlockBgApps: $blockBgApps, SpeedLimiter: $isSpeedLimiterActive (DL: $downloadMb Mbps, UL: $uploadMb Mbps)")

        vpnJob = serviceScope.launch {
            try {
                // High-Performance Wi-Fi Lock requested in background thread
                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    if (wifiManager != null) {
                        @Suppress("DEPRECATION")
                        val lock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EFootballPingWifiLock").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                        wifiLock = lock
                        Log.d("ServerBlockVpn", "High-Performance Wi-Fi Lock acquired in background.")
                    }
                } catch (e: Exception) {
                    Log.e("ServerBlockVpn", "Failed to acquire WifiLock in background: ${e.message}")
                }

                // 1. Resolve hostnames and AWS regions dynamically to find their current IP addresses
                val ipAddresses = mutableSetOf<String>()

                // Primary host entries
                for (host in hosts) {
                    try {
                        val addresses = InetAddress.getAllByName(host)
                        for (addr in addresses) {
                            val ip = addr.hostAddress
                            if (ip != null && !ip.contains(":")) { // Focus on IPv4 for simple routing
                                ipAddresses.add(ip)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ServerBlockVpn", "Could not resolve primary host $host: ${e.message}")
                    }
                }

                // Generically resolve major regional AWS endpoints for the given regional subnets
                for (region in regions) {
                    if (region == "aws-anycast" || region.contains("anycast", ignoreCase = true)) {
                        ipAddresses.add("76.223.122.0")
                        ipAddresses.add("13.248.118.0")
                        continue
                    }
                    if (region == "me-south-1" || region.contains("south-1", ignoreCase = true)) {
                        ipAddresses.add("15.184.148.0")
                        ipAddresses.add("15.185.0.0")
                    }
                    
                    val regionalHosts = listOf(
                        "dynamodb.$region.amazonaws.com",
                        "ec2.$region.amazonaws.com",
                        "$region.amazonaws.com"
                    )
                    for (host in regionalHosts) {
                        try {
                            val addresses = InetAddress.getAllByName(host)
                            for (addr in addresses) {
                                val ip = addr.hostAddress
                                if (ip != null && !ip.contains(":")) {
                                    ipAddresses.add(ip)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ServerBlockVpn", "Could not resolve regional host $host: ${e.message}")
                        }
                    }
                }

                // Create and configure VpnInterface with full exception handling
                var pfd: ParcelFileDescriptor? = null
                try {
                    val builder = Builder()
                        .setSession("eFootball Server Blocker")
                        .setMtu(1410)
                        .addAddress("10.0.0.2", 24)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("1.0.0.1")

                    // EXTREME GAME FOCUS MODE: If enabled or speed limiter is active, route all device traffic (0.0.0.0/0) through our VPN,
                    // and EXCLUDE only gaming apps so background apps get restricted/blocked instantly!
                    if (blockBgApps || isSpeedLimiterActive) {
                        builder.addRoute("0.0.0.0", 0)
                        
                        // Exclude/Whitelist the game apps we want to bypass the VPN blocks and run at extreme 100% native speeds
                        val whitelistedApps = listOf(
                            "com.konami.pesam",       // eFootball Standard
                            "com.konami.pes2012",     // older eFootball
                            "com.ea.gp.fifamobile",   // EA FC Mobile
                            "com.tencent.ig",         // PUBG Mobile
                            "com.dts.freefireth",     // Free Fire
                            "com.mobile.legends",     // Mobile Legends
                            packageName              // Allow our own app so ping tracking still operates!
                        )

                        for (appPkg in whitelistedApps) {
                            try {
                                builder.addDisallowedApplication(appPkg)
                                Log.d("ServerBlockVpn", "Whitelisted application from firewall: $appPkg")
                            } catch (e: Exception) {
                                // Expected: app is not installed on user's device
                            }
                        }
                    } else {
                        // Regular specific routing: only block the targeted IP routes
                        for (ip in ipAddresses) {
                            try {
                                builder.addRoute(ip, 32)
                            } catch (e: Exception) {
                                Log.e("ServerBlockVpn", "Error adding route for $ip: ${e.message}")
                            }
                        }
                    }

                    pfd = builder.establish()
                } catch (e: Exception) {
                    Log.e("ServerBlockVpn", "Failed to build VPN interface: ${e.message}", e)
                    isRunning = false
                    return@launch
                }

                if (pfd == null) {
                    Log.e("ServerBlockVpn", "Failed to establish VPN Interface (pfd was null).")
                    isRunning = false
                    return@launch
                }
                vpnInterface = pfd

                // Initialize highly robust, non-blocking Channel-based forwarding pipeline
                val channel = kotlinx.coroutines.channels.Channel<UdpForwardTask>(capacity = 256)
                udpChannel = channel

                // Spawn 6 dedicated, non-blocking background workers off the Main thread to forward UDP packets
                repeat(6) {
                    serviceScope.launch(Dispatchers.IO) {
                        for (task in channel) {
                            try {
                                forwardUdpPacket(
                                    ipPacket = task.ipPacket,
                                    udpPacket = task.udpPacket,
                                    outputStream = task.outputStream,
                                    applyThrottle = task.applyThrottle,
                                    ulLimiter = task.ulLimiter,
                                    dlLimiter = task.dlLimiter
                                )
                            } catch (ignored: Exception) {}
                        }
                    }
                }

                // Draining loop with programmatically integrated traffic shaping and peer QoS
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val outputStream = java.io.FileOutputStream(pfd.fileDescriptor)
                val buffer = ByteBuffer.allocate(32768)

                val dlLimiter = if (isSpeedLimiterActive) RateLimiter(downloadMb) else null
                val ulLimiter = if (isSpeedLimiterActive) RateLimiter(uploadMb) else null

                while (isActive) {
                    try {
                        val bytesRead = inputStream.read(buffer.array())
                        if (bytesRead <= 0) {
                            delay(10)
                            continue
                        }

                        // 1. DYNAMIC UI AWS REGIONS FIREWALL BLOCK (IP Blocking based on active Avoid choices)
                        var isBlockedIp = false
                        try {
                            if (bytesRead >= 20) {
                                val firstByte = buffer.get(0).toInt()
                                val version = (firstByte ushr 4) and 0x0F
                                if (version == 4) {
                                    val srcIp = "${buffer.get(12).toInt() and 0xFF}.${buffer.get(13).toInt() and 0xFF}.${buffer.get(14).toInt() and 0xFF}.${buffer.get(15).toInt() and 0xFF}"
                                    val destIp = "${buffer.get(16).toInt() and 0xFF}.${buffer.get(17).toInt() and 0xFF}.${buffer.get(18).toInt() and 0xFF}.${buffer.get(19).toInt() and 0xFF}"
                                    if (ipAddresses.contains(srcIp) || ipAddresses.contains(destIp)) {
                                        isBlockedIp = true
                                    }
                                }
                            }
                        } catch (e: Exception) {}

                        if (isBlockedIp) {
                            buffer.clear()
                            continue
                        }

                        // 2. GOOGLE CLOUD DOMAIN AND GAME PORTS BLOCKER
                        val isGoogleBlockingEnabled = prefs.getBoolean("is_google_blocking_enabled", false)
                        if (isGoogleBlockingEnabled) {
                            var shouldDropGooglePacket = false
                            try {
                                if (bytesRead >= 20) {
                                    val firstByte = buffer.get(0).toInt()
                                    val version = (firstByte ushr 4) and 0x0F
                                    if (version == 4) {
                                        val protocol = buffer.get(9).toInt() and 0xFF
                                        val ihl = (firstByte and 0x0F) * 4
                                        if (bytesRead >= ihl + 4) {
                                            val destPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)
                                            // Port Block Check (destPort 5735 or 5736)
                                            if (destPort == 5735 || destPort == 5736) {
                                                shouldDropGooglePacket = true
                                            }

                                            // DNS Domain Blocking (UDP Port 53)
                                            if (!shouldDropGooglePacket && protocol == 17 && destPort == 53 && bytesRead >= ihl + 8) {
                                                val udpLen = ((buffer.get(ihl + 4).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 5).toInt() and 0xFF)
                                                val payloadOffset = ihl + 8
                                                val payloadLen = udpLen - 8
                                                if (payloadLen > 0 && payloadOffset + payloadLen <= bytesRead) {
                                                    val payloadBytes = ByteArray(payloadLen)
                                                    System.arraycopy(buffer.array(), payloadOffset, payloadBytes, 0, payloadLen)

                                                    val payloadStr = String(payloadBytes, Charsets.US_ASCII)
                                                    if (payloadStr.contains("bc.googleusercontent.com") || payloadStr.contains("googleusercontent")) {
                                                        shouldDropGooglePacket = true
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ServerBlockVpn", "Error in Google/Ports filter logic: ${e.message}")
                            }

                            if (shouldDropGooglePacket) {
                                buffer.clear()
                                continue
                            }
                        }

                        // Real-time IPv4 Packet parsing to dynamically bypass latency limits and prioritize eFootball!
                        val isGamePacket = isEFootballPacket(buffer, bytesRead, ipAddresses)

                        if (!isGamePacket) {
                            if (blockBgApps) {
                                // Black-hole / Firewall mode: Drop packet instantly
                                buffer.clear()
                                continue
                            }

                            // Standard background processes traffic shaping (Token Bucket algorithm)
                            if (isSpeedLimiterActive) {
                                ulLimiter?.throttle(bytesRead)
                            } else {
                                delay(1)
                            }
                        }

                        // Software-based Traffic Shaper forwarding Loop
                        try {
                            if (bytesRead >= 20) {
                                // Optimization: Copy ONLY the actual bytesRead rather than cloning the entire 32KB buffer array
                                val packetData = ByteArray(bytesRead)
                                System.arraycopy(buffer.array(), 0, packetData, 0, bytesRead)
                                
                                val ipPacket = IpPacket(packetData, bytesRead)
                                if (ipPacket.version == 4 && ipPacket.protocol == 17) { // UDP / DNS
                                    val udpPacket = UdpPacket(ipPacket)
                                    if (udpPacket.payload.isNotEmpty()) {
                                        val task = UdpForwardTask(
                                            ipPacket = ipPacket,
                                            udpPacket = udpPacket,
                                            outputStream = outputStream,
                                            applyThrottle = isSpeedLimiterActive && !isGamePacket,
                                            ulLimiter = ulLimiter,
                                            dlLimiter = dlLimiter
                                        )
                                        udpChannel?.trySend(task)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Non-UDP packets (like TCP) are dropped when blockBgApps is true or speed limiter is active.
                            // Dropping background TCP is the most powerful way to completely eliminate TCP buffer congestion and prioritize gaming.
                        }

                        buffer.clear()
                    } catch (e: Exception) {
                        if (!isActive) break
                        Log.e("ServerBlockVpn", "Error reading loop: ${e.message}")
                        delay(100)
                    }
                }

            } catch (e: CancellationException) {
                Log.d("ServerBlockVpn", "VPN job cancelled.")
            } catch (e: Exception) {
                Log.e("ServerBlockVpn", "Critical VPN service error: ${e.message}", e)
            } finally {
                udpChannel?.close()
                udpChannel = null
                closeInterface()
            }
        }
    }

    private fun stopBlocking() {
        Log.d("ServerBlockVpn", "Stopping VPN blocking.")
        vpnJob?.cancel()
        vpnJob = null

        udpChannel?.close()
        udpChannel = null

        // Release High-Performance Wi-Fi Lock
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("ServerBlockVpn", "Wi-Fi Lock released in background.")
                }
            }
        } catch (e: Exception) {
            Log.e("ServerBlockVpn", "Error releasing WifiLock: ${e.message}")
        }
        wifiLock = null

        closeInterface()
        isRunning = false
    }

    private fun closeInterface() {
        try {
            vpnInterface?.close()
        } catch (ignored: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        stopBlocking()
        serviceScope.cancel()
        super.onDestroy()
        Log.d("ServerBlockVpn", "VPN Service destroyed")
    }

    private fun isEFootballPacket(buffer: java.nio.ByteBuffer, length: Int, gameIps: Set<String>): Boolean {
        if (length < 20) return false
        try {
            val firstByte = buffer.get(0).toInt()
            val version = (firstByte ushr 4) and 0x0F
            if (version != 4) return false

            val srcIp = "${buffer.get(12).toInt() and 0xFF}.${buffer.get(13).toInt() and 0xFF}.${buffer.get(14).toInt() and 0xFF}.${buffer.get(15).toInt() and 0xFF}"
            val destIp = "${buffer.get(16).toInt() and 0xFF}.${buffer.get(17).toInt() and 0xFF}.${buffer.get(18).toInt() and 0xFF}.${buffer.get(19).toInt() and 0xFF}"

            if (gameIps.contains(srcIp) || gameIps.contains(destIp)) {
                return true
            }

            val protocol = buffer.get(9).toInt() and 0xFF
            if (protocol == 6 || protocol == 17) {
                val ihl = (firstByte and 0x0F) * 4
                if (length >= ihl + 4) {
                    val srcPort = ((buffer.get(ihl).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 1).toInt() and 0xFF)
                    val destPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)

                    // eFootball ports
                    if (srcPort in 5730..5740 || destPort in 5730..5740 ||
                        srcPort in 10000..20000 || destPort in 10000..20000 ||
                        srcPort in 30000..40000 || destPort in 30000..40000) {
                        return true
                    }
                }
            }
        } catch (ignored: Exception) {}
        return false
    }

    private class IpPacket(val data: ByteArray, val length: Int) {
        val version: Int
        val headerLength: Int
        val protocol: Int
        val srcIpBytes = ByteArray(4)
        val destIpBytes = ByteArray(4)
        val srcIp: String
        val destIp: String
        val transportOffset: Int

        init {
            val firstByte = data[0].toInt() and 0xFF
            version = (firstByte ushr 4) and 0x0F
            headerLength = (firstByte and 0x0F) * 4
            protocol = data[9].toInt() and 0xFF
            
            System.arraycopy(data, 12, srcIpBytes, 0, 4)
            System.arraycopy(data, 16, destIpBytes, 0, 4)
            
            srcIp = "${srcIpBytes[0].toInt() and 0xFF}.${srcIpBytes[1].toInt() and 0xFF}.${srcIpBytes[2].toInt() and 0xFF}.${srcIpBytes[3].toInt() and 0xFF}"
            destIp = "${destIpBytes[0].toInt() and 0xFF}.${destIpBytes[1].toInt() and 0xFF}.${destIpBytes[2].toInt() and 0xFF}.${destIpBytes[3].toInt() and 0xFF}"
            transportOffset = headerLength
        }
    }

    private class UdpPacket(val ip: IpPacket) {
        val srcPort: Int
        val destPort: Int
        val length: Int
        val payloadOffset: Int
        val payload: ByteArray

        init {
            val data = ip.data
            val offset = ip.transportOffset
            srcPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            destPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            length = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
            
            payloadOffset = offset + 8
            val payloadLen = length - 8
            payload = ByteArray(maxOf(0, payloadLen))
            if (payloadLen > 0 && payloadOffset + payloadLen <= ip.length) {
                System.arraycopy(data, payloadOffset, payload, 0, payloadLen)
            }
        }
    }

    private fun buildUdpPacket(
        srcIp: ByteArray,
        destIp: ByteArray,
        srcPort: Int,
        destPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payload.size
        val packet = ByteArray(totalLen)

        // Version 4, IHL 5 (20 bytes header)
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x01.toByte()
        packet[6] = 0x40.toByte() // DF
        packet[7] = 0x00.toByte()
        packet[8] = 0x40.toByte() // TTL
        packet[9] = 17.toByte()   // UDP Protocol
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(destIp, 0, packet, 16, 4)

        // Compute IP Checksum
        val ipChecksum = computeChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        val udpOffset = ipHeaderLen
        packet[udpOffset] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((destPort ushr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (destPort and 0xFF).toByte()
        val udpLen = udpHeaderLen + payload.size
        packet[udpOffset + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        packet[udpOffset + 6] = 0x00.toByte()
        packet[udpOffset + 7] = 0x00.toByte()

        System.arraycopy(payload, 0, packet, udpOffset + udpHeaderLen, payload.size)
        return packet
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private suspend fun forwardUdpPacket(
        ipPacket: IpPacket,
        udpPacket: UdpPacket,
        outputStream: java.io.FileOutputStream,
        applyThrottle: Boolean,
        ulLimiter: RateLimiter?,
        dlLimiter: RateLimiter?
    ) {
        var socket: java.net.DatagramSocket? = null
        try {
            socket = java.net.DatagramSocket()
            protect(socket)
            socket.soTimeout = 3000 // 3 seconds timeout

            val destAddr = InetAddress.getByAddress(ipPacket.destIpBytes)
            val sendPacket = java.net.DatagramPacket(udpPacket.payload, udpPacket.payload.size, destAddr, udpPacket.destPort)

            if (applyThrottle) {
                ulLimiter?.throttle(udpPacket.payload.size)
            }

            socket.send(sendPacket)

            val recvBuffer = ByteArray(32768)
            val recvPacket = java.net.DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(recvPacket)

            val responsePayload = ByteArray(recvPacket.length)
            System.arraycopy(recvBuffer, 0, responsePayload, 0, recvPacket.length)

            if (applyThrottle) {
                dlLimiter?.throttle(responsePayload.size)
            }

            // Reconstruct returning raw IP/UDP packet
            val responsePacketData = buildUdpPacket(
                ipPacket.destIpBytes,
                ipPacket.srcIpBytes,
                udpPacket.destPort,
                udpPacket.srcPort,
                responsePayload
            )

            synchronized(outputStream) {
                outputStream.write(responsePacketData)
                outputStream.flush()
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {}
        }
    }

    private class RateLimiter(limitMbps: Float) {
        private val bytesPerSecond = (limitMbps * 1024f * 1024f / 8f).toLong()
        private var lastCheckTime = System.currentTimeMillis()
        private var tokens = bytesPerSecond // Start with a full bucket

        suspend fun throttle(bytes: Int) {
            if (bytesPerSecond <= 0) return
            val now = System.currentTimeMillis()
            val elapsed = now - lastCheckTime
            if (elapsed > 0) {
                // Refill tokens based on elapsed time (up to maximum burst capacity of bytesPerSecond)
                tokens = minOf(bytesPerSecond, tokens + (elapsed * bytesPerSecond / 1000))
                lastCheckTime = now
            }
            tokens -= bytes
            if (tokens < 0) {
                // Calculate sleep time needed to refill tokens to 0
                val sleepMs = (-tokens * 1000 / bytesPerSecond).coerceAtLeast(1)
                try {
                    delay(sleepMs)
                } catch (ignored: Exception) {}
                lastCheckTime = System.currentTimeMillis()
                tokens = 0
            }
        }
    }

    private class UdpForwardTask(
        val ipPacket: IpPacket,
        val udpPacket: UdpPacket,
        val outputStream: java.io.FileOutputStream,
        val applyThrottle: Boolean,
        val ulLimiter: RateLimiter?,
        val dlLimiter: RateLimiter?
    )
}
