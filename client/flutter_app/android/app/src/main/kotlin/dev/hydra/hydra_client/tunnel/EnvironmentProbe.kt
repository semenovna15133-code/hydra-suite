package dev.hydra.hydra_client.tunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tunnel.Tunnel
import android.content.Context
import tunnel.NetworkProbeResult

object EnvironmentProbe {
    private const val TAG = "EnvironmentProbe"
    
    /** true если мобильная сеть режет прямой интернет (белые списки оператора). */
    @Volatile
    var whiteListDetected: Boolean = false
        private set

    private fun isCellular(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            // 0.8.2: если WiFi активен — это НЕ cellular сеть (whiteList не применяется)
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                false
            } else {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No ACCESS_NETWORK_STATE permission, assuming not cellular")
            false
        }
    }

    private fun googleReachable(): Boolean = runCatching {
        java.net.Socket().connect(java.net.InetSocketAddress("google.com", 443), 3000)
        true
    }.getOrElse { false }

    suspend fun probe(context: Context, endpoint: String): NetworkProbeResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Probing network for endpoint: $endpoint")
                DevLog.d("EnvironmentProbe", "Starting probe: endpoint=$endpoint, whiteListCheck=$whiteListDetected")
                // 5 секунд в наносекундах (gomobile не транслирует Duration)
                whiteListDetected = isCellular(context) && !googleReachable()
                if (whiteListDetected) Log.w(TAG, "White-list network detected: cellular + google.com unreachable")
                val result = Tunnel.probeNetwork(endpoint, 5_000_000_000L)
                DevLog.d("EnvironmentProbe", "Tunnel.probeNetwork result: UDP=${result.udpOk}, TCP=${result.tcpOk}, VK=${result.vkApiOk}, DPI=${result.dpiDetected}, latency=${result.latencyMs}ms")
                // TCP RTT замер (чинит latency=0ms)
                val tcpRtt = measureTcpRtt("google.com", 443)
                val effectiveLatency = if (tcpRtt > 0) tcpRtt else result.latencyMs
                Log.d(TAG, "Probe: UDP=${result.udpOk}, TCP=${result.tcpOk}, VK=${result.vkApiOk}, DPI=${result.dpiDetected}, latency=${effectiveLatency}ms (tcpRtt=$tcpRtt)")
                result.latencyMs = effectiveLatency
                result
            } catch (e: Exception) {
                Log.e(TAG, "Probe failed", e)
                NetworkProbeResult()
            }
        }
    }

    private fun measureTcpRtt(host: String, port: Int): Long {
        return try {
            val start = android.os.SystemClock.elapsedRealtime()
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 3000)
            val rtt = android.os.SystemClock.elapsedRealtime() - start
            socket.close()
            rtt
        } catch (e: Exception) {
            0L
        }
    }
}
