package dev.hydra.hydra_client

import dev.hydra.hydra_client.tunnel.ReconnectManager
import dev.hydra.hydra_client.tunnel.HealthMonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import keyfile.Keyfile
import keyfile.KeyFile
import keyfile.Peer
import tunnel.Config
import tunnel.SocketProtector
import tunnel.Tunnel
import tunnel.Tunnel_
import java.io.File

class HydraVpnService : VpnService() {
    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
        
        @Volatile
        var isHandshakeComplete: Boolean = false
            private set

        @Volatile
        var instance: HydraVpnService? = null

        /** true после ручного отключения из UI — блокирует авто-реконнект receiver'а */
        @Volatile
        var userInitiatedStop = false
            private set

        /** Время (ms) когда мультипротокольный бинарник рапортовал connected; -1 = нет */
        @Volatile
        var multiProtoConnectedAt: Long = -1
            private set

        /** Вызывается из MultiProtocolManager при парсинге stdout дочернего процесса */
        fun onMultiProtoStatus(line: String) {
            if (line.contains("AIVPN-STATUS connected") || line.contains("WDTT-STATUS connected")) {
                multiProtoConnectedAt = System.currentTimeMillis()
                isHandshakeComplete = true
                android.util.Log.i("HydraVpnService", "Multiprotocol status: CONNECTED")
            }
        }

        fun resetMultiProtoStatus() {
            multiProtoConnectedAt = -1
        }

        /** Возраст последнего handshake (мс); -1 = туннель не запущен */
        /** JSON статистики туннеля: rx/tx bytes, session_ms */
        fun statsJson(): String {
            instance?.tunnel?.let { return it.stats() }
            val at = multiProtoConnectedAt
            return if (at > 0)
                "{\"rx\":0,\"tx\":0,\"session_ms\":${System.currentTimeMillis() - at}}"
            else "{}"
        }

        fun handshakeAgeMs(): Long {
            instance?.tunnel?.let { return it.lastHandshakeMs() }
            val at = multiProtoConnectedAt
            return if (at > 0) System.currentTimeMillis() - at else -1
        }

        /** Пересоздать Go-туннель под живым TUN (Kill Switch reconnect) */
        fun restartTunnel(): Boolean {
            val svc = instance ?: return false
            return try {
                svc.tunnel?.restart()
                isHandshakeComplete = false
                true
            } catch (e: Exception) {
                android.util.Log.e("HydraVpnService", "restartTunnel failed", e)
                false
            }
        }

        const val EXTRA_KEY_ID = "key_id"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_AIVPN_KEY = "aivpn_key"
        const val EXTRA_WDTT_PASSWORD = "wdtt_password"
        const val EXTRA_ENDPOINT = "endpoint"
        const val CHANNEL_ID = "hydra_vpn_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "HydraVpnService"
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var multiProtocolManager: dev.hydra.hydra_client.tunnel.MultiProtocolManager? = null
    private var tunnel: Tunnel_? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val keyId = intent?.getStringExtra(EXTRA_KEY_ID)
        if (keyId == null) {
            Log.e(TAG, "Missing key_id")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        Thread {
            try {
                startTunnel(keyId, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel failed", e)
                stopSelf()
            }
        }.start()

        return START_STICKY
    }

    private fun startTunnel(keyId: String, intent: Intent?) {
        val keysDir = File(filesDir, "keys")
        val keyFile = File(keysDir, "${sanitizeKeyId(keyId)}.key")
        if (!keyFile.exists()) {
            throw IllegalStateException("Key file not found: ${keyFile.absolutePath}")
        }

        val protocol = intent?.getStringExtra(EXTRA_PROTOCOL) ?: "awg"

        if (protocol == "aivpn" || protocol == "wdtt") {
            startMultiprotocolTunnel(intent, protocol)
            return
        }

        Keyfile.initRuntime()
        val kf: KeyFile = Keyfile.parse(keyFile.absolutePath)
        val peer: Peer = kf.peers.firstOrNull { it.protocol == protocol }
            ?: throw IllegalStateException("No peer for protocol $protocol in key")

        val cfg = Config().apply {
            privateKey = peer.privateKey
            peerPublicKey = peer.publicKey
            endpoint = peer.endpoint
            address = peer.address
            dns = peer.dns
            mtu = 1420
            jc = peer.jc.toLongOrNull() ?: 0
            jmin = peer.jmin.toLongOrNull() ?: 0
            jmax = peer.jmax.toLongOrNull() ?: 0
            s1 = peer.s1.toLongOrNull() ?: 0
            s2 = peer.s2.toLongOrNull() ?: 0
            s3 = peer.s3.toLongOrNull() ?: 0
            s4 = peer.s4.toLongOrNull() ?: 0
            headerProtectionKey = peer.headerProtectionKey
        }

        val builder = Builder()
            .addAddress(peer.address.substringBefore("/"), peer.address.substringAfter("/").toInt())
            .addDnsServer(peer.dns)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1420)
            .setSession("Hydra VPN")

        val pfd = builder.establish() ?: throw IllegalStateException("VpnService.establish() returned null")
        tunFd = pfd
        isRunning = true
        // Передаём владение fd в Go: после detachFd() ParcelFileDescriptor
        // не владеет дескриптором, и fdsan не abort'ит на close() внутри Go
        val rawFd = pfd.detachFd()

        val protector = object : SocketProtector {
            override fun protect(fd: Int): Boolean {
                // VpnService.protect(fd) исключает сокет из туннеля
                val ok = this@HydraVpnService.protect(fd)
                Log.d(TAG, "protect(fd=$fd) = $ok")
                return ok
            }
        }

        tunnel = Tunnel.new_(cfg, protector)
        tunnel!!.start(rawFd, cfg)
        Log.i(TAG, "Tunnel started for key $keyId")
        isHandshakeComplete = true
    }

    
    private fun startMultiprotocolTunnel(intent: Intent?, protocol: String) {
        Log.i(TAG, "Starting multiprotocol tunnel: $protocol")
        multiProtocolManager = dev.hydra.hydra_client.tunnel.MultiProtocolManager(this)
        
        val aivpnKey = intent?.getStringExtra(EXTRA_AIVPN_KEY) ?: ""
        val wdttPassword = intent?.getStringExtra(EXTRA_WDTT_PASSWORD) ?: ""
        
        val started = when (protocol) {
            "aivpn" -> kotlinx.coroutines.runBlocking { multiProtocolManager!!.startAivpn(aivpnKey) }
            "wdtt" -> kotlinx.coroutines.runBlocking { multiProtocolManager!!.startWdtt("", wdttPassword) }
            else -> false
        }
        
        if (!started) throw IllegalStateException("Failed to start $protocol binary")
        
        val clientIp = try {
            val payload = aivpnKey.removePrefix("aivpn://")
            val json = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
            org.json.JSONObject(json).optString("i", "10.0.0.2")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode client IP from aivpn key: ${e.message}")
            "10.0.0.2"
        }
        Log.i(TAG, "TUN client IP: $clientIp")

        val builder = Builder()
            .addAddress(clientIp, 32)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .setSession("Hydra VPN ($protocol)")

        // Исключаем собственное приложение из VPN-роутинга:
        // дочерний процесс aivpn-client имеет тот же UID, и его транспортный
        // трафик к серверу должен идти НАПРЯМУЮ, иначе routing loop
        // (его UDP попадает в наш TUN и возвращается в него же)
        try {
            builder.addDisallowedApplication(packageName)
            Log.i(TAG, "Excluded own package from VPN routing")
        } catch (e: Exception) {
            Log.e(TAG, "addDisallowedApplication failed: ${e.message}")
        }
        
        val pfd = builder.establish() ?: throw IllegalStateException("establish() returned null")
        tunFd = pfd
        isRunning = true
        
        multiProtocolManager!!.startBridge(pfd, protocol)
        Log.i(TAG, "$protocol started with SOCKS5 bridge")
        isHandshakeComplete = true
    }
    
    /** Публичный метод для отключения из UI */
    fun stop() {
        userInitiatedStop = true
        Log.i(TAG, "stop() called — stopping VPN service")
        tunnel?.stop()
        multiProtocolManager?.stopAll()
        resetMultiProtoStatus()
        HealthMonitor.stop()
        ReconnectManager.reset()
        isHandshakeComplete = false
        isRunning = false
        instance = null
        stopForeground(true)
        stopSelf()
    }

override fun onDestroy() {
        isRunning = false
        isHandshakeComplete = false
        instance = null
        Log.i(TAG, "Stopping tunnel")
        try {
            tunnel?.stop()
            // tunFd?.close() не вызываем: fd уже передан в Go через detachFd()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping", e)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hydra VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, HydraVpnService::class.java).apply { action = "STOP" }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hydra VPN")
                .setContentText("Подключено")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stopPending)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Hydra VPN")
                .setContentText("Подключено")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .build()
        }
    }

    private fun sanitizeKeyId(raw: String): String {
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "key" }
    }
}
