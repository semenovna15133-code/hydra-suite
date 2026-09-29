package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.hydra.hydra_client.HydraVpnService
import tunnel.NetworkProbeResult

class AWGTunnelProvider(
    private val context: Context,
    override val serverId: String,
    override val label: String
) : TunnelProvider {
    
    override val name = "AmneziaWG"
    
    override fun score(probe: NetworkProbeResult?): Int {
        if (probe == null) return 50
        // Kotlin property syntax: probe.udpOk -> probe.getUdpOk()
        return when {
            probe.udpOk -> (100 - (probe.latencyMs / 10)).toInt().coerceAtLeast(1)
            probe.tcpOk && !probe.udpOk -> 0  // DPI detected, UDP blocked
            else -> 10
        }
    }
    
    override fun connect(keyId: String): Boolean {
        val intent = Intent(context, HydraVpnService::class.java).apply {
            putExtra(HydraVpnService.EXTRA_KEY_ID, keyId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return true
    }
    
    override fun disconnect() {
        val intent = Intent(context, HydraVpnService::class.java)
        context.stopService(intent)
    }
    
    override fun isRunning(): Boolean {
        return HydraVpnService.isRunning
    }
}
