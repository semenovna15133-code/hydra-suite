package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.hydra.hydra_client.HydraVpnService
import keyfilekt.Peer
import tunnel.NetworkProbeResult

class AWGTunnelProvider(
    private val context: Context,
    override val serverId: String,
    override val label: String,
    private val peer: Peer = Peer()
) : TunnelProvider {
    companion object {
        private const val TAG = "AWGTunnelProvider"
        /** true пока Go-туннель не восстановлен (gomobile broken) */
        const val AWG_DISABLED = false
    }

    
    override val name = "AmneziaWG"
    override val endpoint = peer.endpoint
    
    override fun score(probe: NetworkProbeResult?): Int {
        // AWG временно отключён (gomobile сломан) — не участвует в скоринге,
        // чтобы Smart Connect выбирал AIVPN/WDTT
        if (AWG_DISABLED) return 0
        if (probe == null) return 50
        // Kotlin property syntax: probe.udpOk -> probe.getUdpOk()
        return when {
            probe.udpOk -> (100 - (probe.latencyMs / 10)).toInt().coerceAtLeast(1)
            probe.tcpOk && !probe.udpOk -> 0  // DPI detected, UDP blocked
            else -> 10
        }
    }
    
    override fun connect(keyId: String): Boolean {
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "[CONNECT] AWG стартует")
        Log.i(TAG, "[CONNECT] endpoint=${peer?.endpoint ?: "нет peer"}, AWG_DISABLED=$AWG_DISABLED")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "=== AWGTunnelProvider.connect() START ===")
        Log.i(TAG, "AWG_DISABLED=$AWG_DISABLED")
        
        if (AWG_DISABLED) {
            Log.e(TAG, "AWG is disabled (gomobile broken), refusing to start")
            return false
        }
        
        val intent = Intent(context, HydraVpnService::class.java).apply {
            putExtra(HydraVpnService.EXTRA_KEY_ID, keyId)
            putExtra(HydraVpnService.EXTRA_PROTOCOL, "awg")  // 0.7.0: явный протокол
        }
        
        Log.i(TAG, "Starting foreground service with protocol=awg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.i(TAG, "=== AWGTunnelProvider.connect() END ===")
        return true
    }
    
    override fun disconnect() {
        Log.i(TAG, "Candidate failure: provider-local cleanup only (service stop removed in 0.4.1)")
        android.util.Log.w("HydraProvider", "candidate failure: provider-local cleanup only, full service stop removed in 0.4.1")
        val intent = Intent(context, HydraVpnService::class.java)
        HydraVpnService.instance?.stopActiveTunnel()
    }
    
    override fun isRunning(): Boolean {
        return HydraVpnService.isRunning
    }
}
