package dev.hydra.hydra_client.tunnel

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

object HealthMonitor {
    private const val TAG = "HealthMonitor"
    private const val INTERVAL_MS = 25_000L
    private const val PING_TIMEOUT_MS = 3000
    private const val FAIL_THRESHOLD = 2
    
    private var job: Job? = null
    private var failCount = 0
    var statusListener: ((HealthStatus) -> Unit)? = null
    
    enum class HealthStatus {
        HEALTHY, UNSTABLE, DOWN
    }
    
    fun start() {
        if (job != null) return
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val ok = pingThroughTunnel()
                if (ok) {
                    failCount = 0
                    statusListener?.invoke(HealthStatus.HEALTHY)
                } else {
                    failCount++
                    if (failCount >= FAIL_THRESHOLD) {
                        statusListener?.invoke(HealthStatus.UNSTABLE)
                        Log.w(TAG, "Tunnel unstable after $failCount failures")
                    }
                }
                delay(INTERVAL_MS)
            }
        }
        Log.i(TAG, "Health monitor started")
    }
    
    fun stop() {
        job?.cancel()
        job = null
        failCount = 0
        Log.i(TAG, "Health monitor stopped")
    }
    
    private fun pingThroughTunnel(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress("8.8.8.8", 53), PING_TIMEOUT_MS)
            socket.close()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed: ${e.message}")
            false
        }
    }
}
