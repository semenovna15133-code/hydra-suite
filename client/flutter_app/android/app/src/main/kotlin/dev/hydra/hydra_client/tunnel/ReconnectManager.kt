package dev.hydra.hydra_client.tunnel

import android.util.Log
import kotlinx.coroutines.*

/**
 * Kill Switch + auto-reconnect (MANIFEST 6.4, 7.5).
 * При потере туннеля: реконнект под живым TUN (трафик НЕ уходит мимо VPN,
 * т.к. TUN продолжает перехватывать пакеты, а мёртвый апстрим их дропает).
 * После 3 неудач — состояние BLOCKED (kill switch активен), backoff 5/15/60Lс.
 */
object ReconnectManager {
    private const val TAG = "ReconnectManager"

    enum class State { STABLE, RECONNECTING, BLOCKED }

    @Volatile
    var state: State = State.STABLE
        private set

    var stateListener: ((State) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var attempts = 0
    private var reconnectJob: Job? = null

    fun onHealth(status: HealthMonitor.HealthStatus) {
        Log.d(TAG, "onHealth called: $status")
        when (status) {
            HealthMonitor.HealthStatus.HEALTHY -> {
                if (state != State.STABLE) {
                    Log.i(TAG, "Tunnel recovered")
                }
                attempts = 0
                setState(State.STABLE)
            }
            HealthMonitor.HealthStatus.UNSTABLE -> {
                if (state == State.STABLE) {
                    Log.w(TAG, "Tunnel unstable, scheduling reconnect")
                    setState(State.RECONNECTING) // guard от гонки дублей
                    scheduleReconnect(delaySec = 0L)
                }
            }
            HealthMonitor.HealthStatus.DOWN -> {
                if (state == State.STABLE) scheduleReconnect(delaySec = 0L)
            }
        }
    }

    private fun setState(s: State) {
        if (state != s) {
            state = s
            Log.i(TAG, "State -> $s")
            stateListener?.invoke(s)
        }
    }

    private fun scheduleReconnect(delaySec: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (delaySec > 0) {
                setState(State.RECONNECTING)
                delay(delaySec * 1000)
            }
            tryReconnect()
        }
    }

    private suspend fun tryReconnect() {
        setState(State.RECONNECTING)
        attempts++
        Log.i(TAG, "Reconnect attempt $attempts")

        val ok = withContext(Dispatchers.IO) {
            try {
                dev.hydra.hydra_client.HydraVpnService.restartTunnel()
            } catch (e: Exception) {
                Log.e(TAG, "Restart failed", e)
                false
            }
        }

        if (ok) {
            // Даём handshake 5 сек, потом HealthMonitor сам подтвердит HEALTHY
            delay(5000)
            if (dev.hydra.hydra_client.HydraVpnService.handshakeAgeMs() in 0L..15_000L) {
                attempts = 0
                setState(State.STABLE)
            } else {
                nextBackoff()
            }
        } else {
            nextBackoff()
        }
    }

    private fun nextBackoff() {
        val delaySec: Long = when {
            attempts >= 3 -> {
                setState(State.BLOCKED)  // Kill switch активен
                60L
            }
            attempts == 2 -> 15L
            else -> 5L
        }
        Log.w(TAG, "Reconnect failed, next in ${delaySec}s (attempts=$attempts)")
        scheduleReconnect(delaySec)
    }

    fun reset() {
        reconnectJob?.cancel()
        attempts = 0
        setState(State.STABLE)
    }
}
