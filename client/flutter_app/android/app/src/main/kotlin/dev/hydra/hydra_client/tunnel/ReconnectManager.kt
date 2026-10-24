package dev.hydra.hydra_client.tunnel

import android.util.Log
import kotlinx.coroutines.*
import dev.hydra.hydra_client.HydraVpnService

/**
 * Kill Switch + auto-reconnect (MANIFEST 6.4, 7.5).
 * При потере туннеля: реконнект под живым TUN (трафик НЕ уходит мимо VPN,
 * т.к. TUN продолжает перехватывать пакеты, а мёртвый апстрим их дропает).
 * После 3 неудач — состояние BLOCKED (kill switch активен), backoff 5/15/60Lс.
 */
object ReconnectManager {
    var context: android.content.Context? = null

    private const val TAG = "ReconnectManager"

    enum class State { STABLE, RECONNECTING, BLOCKED }

    @Volatile
    var state: State = State.STABLE
        private set

    var stateListener: ((State) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var attempts = 0
    private var lastRxBytes: Long = 0L
    private var firstStallTime: Long = 0L
    private var lastRxCheckTime: Long = 0L
    private val rxStallThresholdMs = 60_000L
    private var reconnectJob: Job? = null

    private var unstableCount = 0
    
    fun onHealth(status: HealthMonitor.HealthStatus) {
        checkRxStall()
        Log.d(TAG, "onHealth called: $status")
        // Честный статус: здоровье туннеля = факт рукопожатия/трафика
        if (HydraVpnService.isRunning &&
            (status == HealthMonitor.HealthStatus.HEALTHY ||
             android.os.SystemClock.elapsedRealtime() - HydraVpnService.sessionStartMs > 20000)) {
            HydraVpnService.isHandshakeComplete = (status == HealthMonitor.HealthStatus.HEALTHY)
        }
        when (status) {
            HealthMonitor.HealthStatus.HEALTHY -> {
                if (state != State.STABLE) {
                    Log.i(TAG, "Tunnel recovered")
                }
                unstableCount = 0
                attempts = 0
                setState(State.STABLE)
            }
            HealthMonitor.HealthStatus.UNSTABLE -> {
                unstableCount++
                Log.w(TAG, "Tunnel unstable (count=$unstableCount)")
                if (unstableCount >= 2) {
                    // Failover: переключение на другой протокол
                    Log.w(TAG, "UNSTABLE ×2 — triggering failover")
                    setState(State.RECONNECTING)
                    scope.launch {
                        val ok = dev.hydra.hydra_client.tunnel.SmartConnectManager.failover(
                            dev.hydra.hydra_client.tunnel.SmartConnectManager.active
                        )
                        if (ok) {
                            unstableCount = 0
                            attempts = 0
                            setState(State.STABLE)
                        } else {
                            nextBackoff()
                        }
                    }
                } else if (state == State.STABLE) {
                    // Первый UNSTABLE — пробуем restart того же протокола
                    setState(State.RECONNECTING)
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


    @Volatile private var wdttFailoverInProgress = false

    private fun readHbStats(): Pair<Long, Long>? {
        return try {
            val inst = HydraVpnService.instance ?: return null
            val f = java.io.File(inst.filesDir, "hb_stats.json")
            if (!f.exists()) return null
            val j = org.json.JSONObject(f.readText())
            Pair(j.optLong("rx_bytes"), j.optLong("tx_bytes"))
        } catch (_: Exception) { null }
    }

    fun onWdttDead(reason: String) {
        if (!HydraVpnService.isRunning) return
        if (!HydraVpnService.activeProviderName.equals("WDTT", ignoreCase = true)) return
        if (wdttFailoverInProgress) return
        wdttFailoverInProgress = true
        Log.e(TAG, "WDTT dead: $reason — recording failure and failing over")
        val ctx = context ?: HydraVpnService.instance
        if (ctx != null) ProtocolMemory.recordFailure(ctx, "WDTT")
        scope.launch {
            val ok = dev.hydra.hydra_client.tunnel.SmartConnectManager.failover(
                dev.hydra.hydra_client.tunnel.SmartConnectManager.active
            )
            Log.i(TAG, "WDTT failover result: $ok")
            wdttFailoverInProgress = false
        }
    }

    private fun checkRxStall() {
        if (!HydraVpnService.isRunning) return
        if (!HydraVpnService.isHandshakeComplete) return // 0.4.3: stall только после подтверждённого коннекта
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRxCheckTime < 10_000L) return // проверяем раз в 10 сек
        lastRxCheckTime = now
        // Тихая смерть бэкенда: isRunning=true, но процессы wdtt/aivpn мертвы
        val proto = HydraVpnService.activeProviderName
        if (proto != null && !proto.equals("AmneziaWG", ignoreCase = true) && !HydraVpnService.processesAlive()) {
            if (!wdttFailoverInProgress) {
                wdttFailoverInProgress = true
                Log.e(TAG, "Backend processes dead while isRunning=true (proto=$proto) — failover")
                scope.launch {
                    val ok = dev.hydra.hydra_client.tunnel.SmartConnectManager.failover(
                        dev.hydra.hydra_client.tunnel.SmartConnectManager.active
                    )
                    Log.i(TAG, "Process-death failover result: $ok")
                    wdttFailoverInProgress = false
                }
            }
            return
        }
        
        DevLog.d("ReconnectManager", "rx-stall state: lastRx=$lastRxBytes, firstStallTime=$firstStallTime, state=$state")
        
        val hb = readHbStats()
        val isWdtt = HydraVpnService.activeProviderName.equals("WDTT", ignoreCase = true)
        val rx = if (isWdtt && hb != null) hb.first else HydraVpnService.readTunStats(HydraVpnService.tunName).first
        val tx = if (isWdtt && hb != null) hb.second else HydraVpnService.readTunStats(HydraVpnService.tunName).second
        if (rx == lastRxBytes && tx > 0L) {
            // rx заморожен, но tx идёт (keepalive/пинги проходят, трафик не идёт)
            if (firstStallTime == 0L) {
                firstStallTime = now
                Log.w(TAG, "rx-stall started: rx=$rx frozen, tx=$tx")
            }
            val stallDuration = now - firstStallTime
            if (stallDuration >= rxStallThresholdMs && state == State.STABLE) {
                val protocol = HydraVpnService.activeProviderName ?: "unknown"
                Log.e(TAG, "rx-stall detected: rx frozen for ${stallDuration}ms (threshold=${rxStallThresholdMs}ms), protocol=$protocol — triggering failover")
                val failCtx = context ?: HydraVpnService.instance
                if (failCtx != null) ProtocolMemory.recordFailure(failCtx, protocol)
                setState(State.RECONNECTING)
                scope.launch {
                    val ok = dev.hydra.hydra_client.tunnel.SmartConnectManager.failover(
                        dev.hydra.hydra_client.tunnel.SmartConnectManager.active
                    )
                    if (ok) {
                        lastRxBytes = 0L
                        lastRxCheckTime = 0L
                        firstStallTime = 0L
                        setState(State.STABLE)
                    } else {
                        nextBackoff()
                    }
                }
            }
        } else {
            // rx растёт — сбрасываем stall-счётчик
            if (firstStallTime != 0L) {
                Log.i(TAG, "rx recovered: rx=$rx, tx=$tx — stall reset")
            }
            firstStallTime = 0L
            lastRxBytes = rx
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
