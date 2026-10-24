package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.hydra.hydra_client.HydraVpnService
import keyfilekt.Peer
import tunnel.NetworkProbeResult

class AIVPNTunnelProvider(
    private val context: Context,
    override val serverId: String,
    override val label: String,
    private val peer: Peer
) : TunnelProvider {
    companion object {
        private const val TAG = "AIVPNTunnelProvider"
    }

    override val name = "AIVPN"
    override val endpoint = peer.endpoint

    override fun score(probe: NetworkProbeResult?): Int {
        if (probe == null) return 40
        // AIVPN — TCP-протокол (443), оценка по TCP-доступности
        return when {
            probe.dpiDetected -> 90                              // DPI = AIVPN лучший выбор
            probe.tcpOk -> (80 - (probe.latencyMs / 10).toInt()).coerceAtLeast(1)
            probe.udpOk && !probe.tcpOk -> 20                    // только UDP — AIVPN не пройдёт
            else -> 10
        }
    }

    override fun connect(keyId: String): Boolean {
        if (peer.key.isEmpty()) {
            Log.e(TAG, "AIVPN key is empty")
            return false
        }
        
        Log.i(TAG, "Connecting via AIVPN: endpoint=${peer.endpoint}")
        
        val intent = Intent(context, HydraVpnService::class.java).apply {
            putExtra(HydraVpnService.EXTRA_KEY_ID, keyId)
            putExtra(HydraVpnService.EXTRA_PROTOCOL, "aivpn")
            putExtra(HydraVpnService.EXTRA_AIVPN_KEY, peer.key)
            putExtra(HydraVpnService.EXTRA_ENDPOINT, peer.endpoint)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return true
    }

    override fun disconnect() {
        Log.i(TAG, "Disconnecting AIVPN")
        android.util.Log.w("HydraProvider", "candidate failure: provider-local cleanup only, full service stop removed in 0.4.1")
        val intent = Intent(context, HydraVpnService::class.java)
        context.stopService(intent)
    }

    override fun isRunning(): Boolean = HydraVpnService.isRunning
}
