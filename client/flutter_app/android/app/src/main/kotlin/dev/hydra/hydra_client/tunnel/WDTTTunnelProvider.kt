package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.hydra.hydra_client.HydraVpnService
import keyfilekt.Peer
import tunnel.NetworkProbeResult

class WDTTTunnelProvider(
    private val context: Context,
    override val serverId: String,
    override val label: String,
    private val peer: Peer
) : TunnelProvider {
    companion object {
        private const val TAG = "WDTTTunnelProvider"
    }

    override val name = "WDTT"
    override val endpoint = peer.endpoint

    override fun score(probe: NetworkProbeResult?): Int {
        if (probe == null) return 30
        // 0.4.7: проверка VK (соблюдение П-03: VK недоступен → WDTT вне пула)
        return when {
            probe.udpOk -> (60 - (probe.latencyMs / 10)).toInt().coerceAtLeast(1)
            probe.tcpOk && !probe.udpOk -> 0
            else -> 5
        }
    }

    override fun connect(keyId: String): Boolean {
        if (peer.password.isEmpty()) {
            Log.e(TAG, "WDTT password is empty")
            return false
        }
        
        Log.i(TAG, "Connecting via WDTT: endpoint=${peer.endpoint}")
        
        val intent = Intent(context, HydraVpnService::class.java).apply {
            putExtra(HydraVpnService.EXTRA_KEY_ID, keyId)
            putExtra(HydraVpnService.EXTRA_PROTOCOL, "wdtt")
            putExtra(HydraVpnService.EXTRA_WDTT_PASSWORD, peer.password)
            putExtra(HydraVpnService.EXTRA_ENDPOINT, peer.endpoint)
            putExtra(HydraVpnService.EXTRA_WDTT_VK_HASHES, dev.hydra.hydra_client.SettingsStore.getVkHashes(context))
            putExtra(HydraVpnService.EXTRA_WDTT_WORKERS, dev.hydra.hydra_client.SettingsStore.resolveWorkers(context))
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return true
    }

    override fun disconnect() {
        Log.i(TAG, "Disconnecting WDTT")
        android.util.Log.w("HydraProvider", "candidate failure: provider-local cleanup only, full service stop removed in 0.4.1")
        val intent = Intent(context, HydraVpnService::class.java)
        HydraVpnService.instance?.stopActiveTunnel()
    }

    override fun isRunning(): Boolean = HydraVpnService.isRunning
}
