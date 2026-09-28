package dev.hydra.hydra_client

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
        const val EXTRA_KEY_ID = "key_id"
        const val CHANNEL_ID = "hydra_vpn_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "HydraVpnService"
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var tunnel: Tunnel_? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                startTunnel(keyId)
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel failed", e)
                stopSelf()
            }
        }.start()

        return START_STICKY
    }

    private fun startTunnel(keyId: String) {
        val keysDir = File(filesDir, "keys")
        val keyFile = File(keysDir, "${sanitizeKeyId(keyId)}.key")
        if (!keyFile.exists()) {
            throw IllegalStateException("Key file not found: ${keyFile.absolutePath}")
        }

        Keyfile.initRuntime()
        val kf: KeyFile = Keyfile.parse(keyFile.absolutePath)
        val peer: Peer = Keyfile.peerAt(kf, 0) ?: throw IllegalStateException("No peers in key")

        if (peer.protocol != "awg") {
            throw UnsupportedOperationException("Only awg protocol supported for 3b-i, got: ${peer.protocol}")
        }

        val cfg = Config().apply {
            privateKey = peer.privateKey
            peerPublicKey = peer.publicKey
            endpoint = peer.endpoint
            address = peer.address
            dns = peer.dns
            mtu = 1420
            jc = peer.jc.toLong()
            jmin = peer.jmin.toLong()
            jmax = peer.jmax.toLong()
            s1 = peer.s1.toLong()
            s2 = peer.s2.toLong()
            s3 = peer.s3.toLong()
            s4 = peer.s4.toLong()
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
    }

    override fun onDestroy() {
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
