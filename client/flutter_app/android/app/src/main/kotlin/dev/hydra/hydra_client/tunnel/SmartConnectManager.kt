package dev.hydra.hydra_client.tunnel

import dev.hydra.hydra_client.HydraVpnService

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import tunnel.NetworkProbeResult

object SmartConnectManager {
    private var lastNetworkType: String? = null
    private var healthMonitorJob: kotlinx.coroutines.Job? = null  // 0.7.11

    private const val TAG = "SmartConnectManager"
    
    private val providers = mutableListOf<TunnelProvider>()
    private var activeProvider: TunnelProvider? = null
    private var context: Context? = null
    private var keyId: String? = null
    
    /** Публичный доступ к активному провайдеру (для UI) */
    val active: TunnelProvider? get() = activeProvider
    
    @Volatile
    var lastLatencyMs: Long = 0
        private set
    
    @Volatile
    var lastProbe: NetworkProbeResult? = null
        private set

    /** true если для подключения не хватает VK-хешей (UI покажет онбординг). */
    @Volatile
    var needVkHashes: Boolean = false
        private set
    
    /** Callback для UI-сообщений о переключении протокола */
    var statusMessageCallback: ((String) -> Unit)? = null
    @Volatile
    var lastStatusMessage: String? = null
    
    fun registerProvider(provider: TunnelProvider) {
        providers.add(provider)
        Log.i(TAG, "Registered provider: ${provider.name} (${provider.serverId})")
    }
    private val connectMutex = kotlinx.coroutines.sync.Mutex()
    private var supervisedRetryJob: kotlinx.coroutines.Job? = null

    /** Single-flight: один connect-поток за раз; при null — надзорные ретраи с любых путей. */
    suspend fun autoConnect(ctx: Context, kid: String, retryAfterUnlock: Boolean = true): TunnelProvider?  {
        val result = connectMutex.withLock { autoConnectInner(ctx, kid, retryAfterUnlock) }
        if (result == null) scheduleSupervisedRetries(ctx, kid)
        return result
    }

    private fun scheduleSupervisedRetries(ctx: Context, kid: String) {
        supervisedRetryJob?.cancel()
        supervisedRetryJob = ncScope.launch {
            // 0.7.4: не перехватываем сессию в explicit-режиме
            if (dev.hydra.hydra_client.MainActivity.explicitProtocol != null) {
                Log.i(TAG, "Supervised retries skipped: explicit mode")
                return@launch
            }
            var delayMs = 15_000L
            repeat(4) { i ->
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(120_000L)
                if (!HydraVpnService.isRunning) {
                    Log.i(TAG, "Supervised retry #$i (autoConnect returned null)")
                    val p = connectMutex.withLock { autoConnectInner(ctx, kid, true) }
                    if (p != null) { Log.i(TAG, "Supervised retry OK: ${p?.name}"); return@launch }
                }
            }
            Log.e(TAG, "Supervised retries exhausted — waiting for network event")
        }
    }

    private suspend fun autoConnectInner(ctx: Context, kid: String, retryAfterUnlock: Boolean): TunnelProvider?  {
        // Сброс штрафов при смене сети (WiFi ↔ cellular)
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val currentType = when {
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            else -> "unknown"
        }
        if (lastNetworkType != null && lastNetworkType != currentType) {
            Log.i(TAG, "Network changed $lastNetworkType -> $currentType: resetting all penalties")
            ProtocolMemory.resetAll(ctx)
        }
        lastNetworkType = currentType

        context = ctx
        keyId = kid
        dev.hydra.hydra_client.HydraVpnService.sessionExplicit = false  // 0.7.6: AUTO-путь
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "[MODE] AUTO — Smart Connect ищет оптимальный протокол")
        Log.i(TAG, "[LOGIC] probe → score → pick → connect → monitor → failover")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        
        // Парсим keyfile и строим провайдеры динамически
        val keysDir = java.io.File(ctx.filesDir, "keys")
        val keyFile = java.io.File(keysDir, "${sanitizeKeyId(kid)}.key")
        if (!keyFile.exists()) {
            Log.e(TAG, "Key file not found: ${keyFile.absolutePath}")
            return null
        }
        
        val kf = keyfilekt.Keyfile.parse(keyFile.absolutePath)
        
        // Очищаем старые провайдеры
        providers.clear()
        
        // Регистрируем провайдер для каждого peer
        for (peer in kf.peers) {
            val provider = when (peer.protocol) {
                "awg" -> AWGTunnelProvider(ctx, peer.serverId, peer.label, peer)
                "aivpn" -> AIVPNTunnelProvider(ctx, peer.serverId, peer.label, peer)
                "wdtt" -> WDTTTunnelProvider(ctx, peer.serverId, peer.label, peer)
                else -> {
                    Log.w(TAG, "Unknown protocol: ${peer.protocol}")
                    continue
                }
            }
            registerProvider(provider)
        }
        
        // Пробегаем по всем endpoint'ам для скоринга
        val firstEndpoint = kf.peers.firstOrNull()?.endpoint ?: "31.77.202.131:51820"
        val probe = EnvironmentProbe.probe(ctx, firstEndpoint)
        Log.i(TAG, "[PROBE] UDP=${probe.udpOk}, TCP=${probe.tcpOk}, VK=${probe.vkApiOk}, DPI=${probe.dpiDetected}, latency=${probe.latencyMs}ms, whiteList=${EnvironmentProbe.whiteListDetected}")
        lastProbe = probe
        lastLatencyMs = probe.latencyMs
        
        var whiteList = EnvironmentProbe.whiteListDetected
        if (whiteList) {  // 0.7.9: сеть не рестриктивная, если AWG/AIVPN недавно успели
            Log.i(TAG, "whiteList=true: checking recent AWG/AIVPN success")  // 0.7.10 debug
            val nowMs = System.currentTimeMillis()
            val awgLast = ProtocolMemory.lastSuccessMs(ctx, "AmneziaWG")
            val aiLast = ProtocolMemory.lastSuccessMs(ctx, "AIVPN")
            // 0.8.6: защита от lastSuccess=0 (56 лет ago) — проверяем что протокол реально подключался
            val awgOk = if (awgLast > 0L) nowMs - awgLast else Long.MAX_VALUE
            val aiOk  = if (aiLast  > 0L) nowMs - aiLast  else Long.MAX_VALUE
            Log.i(TAG, "whiteList check: nowMs=$nowMs, awgLast=$awgLast, aiLast=$aiLast, awgOk=${if(awgOk==Long.MAX_VALUE) "never" else "${awgOk/1000}s"}, aiOk=${if(aiOk==Long.MAX_VALUE) "never" else "${aiOk/1000}s"}")
            if (awgOk < 300_000L || aiOk < 300_000L) {
                Log.w(TAG, "whiteList overridden: recent AWG/AIVPN success (awg=${awgOk/1000}s ago, aivpn=${aiOk/1000}s ago) — network not restrictive")
                whiteList = false
            } else {
                Log.i(TAG, "whiteList NOT overridden: no recent AWG/AIVPN success")  // 0.7.10 debug
            }
        } else {
            Log.i(TAG, "whiteList=false: skipping override check")  // 0.7.10 debug
        }
        val hashCount = dev.hydra.hydra_client.SettingsStore.getHashList(ctx).size
        val preferred = dev.hydra.hydra_client.SettingsStore.getPreferredProtocol(ctx)

        val scored = providers.map { p ->
            var sc = p.score(probe)
            // Penalty: если протокол наказан — score=0
            if (ProtocolMemory.isPenalized(ctx, p.name)) {
                Log.w(TAG, "${p.name} is penalized, score forced to 0")
                sc = 0
            }
            if (whiteList) {
                sc = when (p.name) {
                    "WDTT" -> sc + 50          // обход белых списков через VK TURN
                    else -> 0                  // AIVPN/AWG в белых списках не живут
                }
            }
            p to sc
        }
        .sortedWith(compareByDescending<Pair<TunnelProvider, Int>> { it.second }
            .thenBy { when (it.first.name) {
                "AmneziaWG" -> 0
                "AIVPN" -> 1
                "WDTT" -> 2
                else -> 3
            } })

        // Белые списки + выбран WDTT, но хешей нет — просим пользователя добавить
        needVkHashes = whiteList && hashCount == 0 &&
            scored.firstOrNull()?.first?.name == "WDTT"
        if (needVkHashes) {
            Log.e(TAG, "White-list network but no VK hashes configured — ask user")
            return null
        }
        
        DevLog.i("SmartConnectManager", "Memory state: ${ProtocolMemory.dumpState(ctx)}")
        DevLog.i("SmartConnectManager", "Probe results: UDP=${probe.udpOk}, TCP=${probe.tcpOk}, VK=${probe.vkApiOk}, DPI=${probe.dpiDetected}, latency=${probe.latencyMs}ms")
        val scoredDetails = scored.map { (p, sc) ->
            val penalty = if (ProtocolMemory.isPenalized(ctx, p.name)) "penalized" else "ok"
            "${p.name}=$sc ($penalty)"
        }.joinToString(", ")
        DevLog.i("SmartConnectManager", "Scored details: [$scoredDetails], preferred=$preferred, whiteList=$whiteList")
        Log.i(TAG, "Providers scored: ${scored.map { "${it.first.name}=${it.second}" }}, preferred=$preferred")
        
        // Evidence-timeout loop: пробуем каждого кандидата последовательно
        val candidates = if (preferred != "auto") {
            val pref = scored.find { it.first.name.equals(preferred, ignoreCase = true) && it.second > 0 }
            if (pref != null) {
                Log.i(TAG, "Preferred protocol: ${pref.first.name}, trying first")
                listOf(pref) + scored.filter { it.first.name != pref.first.name && it.second > 0 }
            } else {
                scored.filter { it.second > 0 }
            }
        } else {
            scored.filter { it.second > 0 }
        }

        // Tiebreaker: при равных баллах приоритет AWG > WDTT > AIVPN
        val tiebreakerOrder = mapOf("AmneziaWG" to 0, "AIVPN" to 1, "WDTT" to 2)
        Log.i(TAG, "Scoring: candidates=${candidates.map { "${it.first.name}=${it.second}" }.joinToString(", ")}")  // 0.7.10 debug
        val sorted = candidates.sortedWith(compareByDescending<Pair<dev.hydra.hydra_client.tunnel.TunnelProvider, Int>> { it.second }
            .thenBy { tiebreakerOrder[it.first.name] ?: 99 })
        Log.i(TAG, "Scoring: sorted=${sorted.map { "${it.first.name}=${it.second}" }.joinToString(", ")}")  // 0.7.10 debug
        // 0.7.4: preferred-first НЕ уничтожается пересортировкой по score
        val candidatesFinal = if (preferred != "auto") {
            val prefEntry = scored.find { it.first.name.equals(preferred, ignoreCase = true) && it.second > 0 }
            if (prefEntry != null) {
                listOf(prefEntry) + sorted.filter { it.first.name != prefEntry.first.name }
            } else sorted
        } else sorted
        
        if (candidates.isEmpty()) {
            if (retryAfterUnlock && ProtocolMemory.getPrefs(ctx).all.isNotEmpty()) {
                Log.w(TAG, "No viable candidates — emergency unlock penalties and rescore once")
                // 0.7.8: whiteList-перехват РАНЬше hb-проверки (hb накопительный и врёт «жив»)
            try {
                val kfW = keyfilekt.Keyfile.parse(java.io.File(ctx.filesDir, "keys/${sanitizeKeyId(kid)}.key").absolutePath)
                val epW = kfW.peers.firstOrNull()?.endpoint ?: ""
                if (epW.isNotEmpty()) EnvironmentProbe.probe(ctx, epW)
            } catch (_: Exception) {}
            val wlNow = EnvironmentProbe.whiteListDetected
            val curProto = dev.hydra.hydra_client.HydraVpnService.activeProviderName
            if (wlNow && curProto in listOf("AIVPN", "AmneziaWG") && dev.hydra.hydra_client.HydraVpnService.isRunning) {
                Log.w(TAG, "═══════════════════════════════════════════════════════════")
                Log.w(TAG, "[FAILOVER] текущий протокол $curProto мёртв")
                Log.w(TAG, "[FAILOVER] причина: whiteList detected, $curProto не работает на этой сети")
                Log.w(TAG, "[FAILOVER] переходим на WDTT (единственный протокол для whiteList)")
                Log.w(TAG, "═══════════════════════════════════════════════════════════")
                Log.w(TAG, "Network changed to whiteList with $curProto active — forcing failover to WDTT")
                activeProvider?.disconnect()
                activeProvider = null
                HealthMonitor.stop()
                kotlinx.coroutines.delay(1500)
                return autoConnect(ctx, kid)
            }
            ProtocolMemory.clearPenalties(ctx)
                return autoConnectInner(ctx, kid, false)
            }
            Log.e(TAG, "No viable candidates (all scored 0 or penalized)")
            return null
        }
        
        for ((provider, score) in candidatesFinal) {
            Log.i(TAG, "Trying ${provider.name} (score=$score)...")
            uiMessage("Подключение через ${provider.name}...")
            
            val success = tryConnectWithEvidence(provider, kid)
            if (success) {
                Log.i(TAG, "✅ ${provider.name} connected successfully")
                ProtocolMemory.recordSuccess(ctx, provider.name)
                
                // 0.7.11: health monitor для WDTT (как в WDTT Plus)
                if (provider.name == "WDTT") {
                    healthMonitorJob?.cancel()
                    healthMonitorJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        var lastRx = try { HydraVpnService.readTunStats(HydraVpnService.tunName).first } catch (_: Exception) { 0L }
                        var lastActivityMs = System.currentTimeMillis()
                        while (isActive) {
                            delay(10_000L)  // проверка каждые 10 секунд
                            val now = System.currentTimeMillis()
                            val idleMs = now - lastActivityMs
                            
                            // Если нет активности 75 секунд → перезапуск
                            if (idleMs > 75_000L) {
                                Log.w(TAG, "[HEALTH] WDTT idle for ${idleMs/1000}s — triggering failover")
                                ReconnectManager.onWdttDead("Health monitor: idle 75s")
                                break
                            }
                            
                            // Обновляем lastActivityMs если есть трафик
                            if (!HydraVpnService.isRunning) { Log.i(TAG, "[HEALTH] tunnel not running — monitor exit"); break }
                            val curRx = try { HydraVpnService.readTunStats(HydraVpnService.tunName).first } catch (_: Exception) { lastRx }
                            if (curRx > lastRx) { lastActivityMs = now; lastRx = curRx }  // 0.7.11: дельта, не накопление
                        }
                    }
                }
                activeProvider = provider
                HealthMonitor.statusListener = { st -> ReconnectManager.onHealth(st) }
                HealthMonitor.start()
                return provider
            } else {
                Log.w(TAG, "❌ ${provider.name} failed, trying next...")
                ProtocolMemory.recordFailure(ctx, provider.name)
                provider.disconnect()
                uiMessage("${provider.name} недоступен, переключаюсь...")
                delay(1000) // даём teardown завершиться
            }
        }
        
        Log.e(TAG, "All candidates failed")
        return null
    }
    
    /**
     * Evidence-timeout: подключаем провайдер и ждём реального признака жизни.
     * Таймауты: AWG 15с, AIVPN 10с, WDTT 12с.
     */
    private suspend fun tryConnectWithEvidence(provider: TunnelProvider, kid: String): Boolean {
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "[EVIDENCE] начинаем проверку ${provider.name}")
        Log.i(TAG, "[EVIDENCE] критерии: handshake + traffic + health")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        // 0.5.7: AWG timeout 15с (сервер иногда отвечает за 10-13с)
        val timeoutMs = when (provider.name) {
            "AmneziaWG" -> 25_000L  // 0.8.6: увеличено с 15с (сервер иногда отвечает за 10-20с)
            "AIVPN" -> 10_000L
            "WDTT" -> 20_000L
            else -> 15_000L
        }
        
        val startTime = android.os.SystemClock.elapsedRealtime()
        provider.connect(kid)
        
        // 0.5.5: фиксированная задержка перед evidence-циклом
        // WDTT стартует за 3-4 сек (VK auth throttling + TURN credentials + handshake)
        // AIVPN стартует за 2-3 сек
        // AWG стартует мгновенно (но evidence требует handshake)
        // 0.5.6: AWG требует 3-7с на handshake (сервер отвечает медленно)
        val preEvidenceDelay = when (provider.name) {
            "WDTT" -> 5000L
            "AIVPN" -> 3000L
            "AmneziaWG" -> 5000L
            else -> 3000L
        }
        Log.d(TAG, "0.5.5: delay(${preEvidenceDelay}ms) перед evidence-циклом для ${provider.name}")
        delay(preEvidenceDelay)
        
        // 0.5.6: убираем isRunning из условия — timeoutMs достаточно
        // (isRunning=false пока establish() не завершится, но это не значит что туннель мёртв)
        while (android.os.SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            delay(2000)
            
            // 0.5.7: alive-проверка через hb_stats.json для всех multiprotocol протоколов
            // (activeProviderName выставляется после establish(), а evidence-цикл идёт раньше —
            //  поэтому для AIVPN/WDTT используем hb_stats + handshake, а не только activeName)
            val activeName = dev.hydra.hydra_client.HydraVpnService.activeProviderName
            val handshakeSet = dev.hydra.hydra_client.HydraVpnService.isHandshakeComplete
            val alive = when (provider.name) {
                // 0.8.7: evidence-gate для bridge-протоколов
                // hbHasRx() = только rx > 0 (реальный входящий трафик от сервера)
                // tx от bridge (SYN-попытки) не считается evidence
                "WDTT" -> wdttSocksAlive() || hbHasRx()
                "AIVPN" -> (handshakeSet && hbHasRx()) || hbHasRx()
                else -> handshakeSet && activeName == provider.name
            }
            val handshakeOk = alive
            val hasTraffic = alive
            val healthOk = alive
            
            if (handshakeOk && (hasTraffic || healthOk)) {
                Log.i(TAG, "Evidence: ${provider.name} alive (handshake=$handshakeOk, traffic=$hasTraffic, health=$healthOk)")
                return true
            }
            
            val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
            Log.d(TAG, "Waiting for ${provider.name} evidence: ${elapsed}ms / ${timeoutMs}ms")
        }
        
        // 0.5.3: TODO — distinguish timeout vs start failure
            Log.w(TAG, "Evidence timeout for ${provider.name}")
        return false
    }
    
    // 0.8.7: только rx > 0 (реальный трафик от сервера, не SYN от bridge)
    private fun hbHasRx(): Boolean {
        return try {
            val statsFile = java.io.File(context?.filesDir ?: return false, "hb_stats.json")
            if (!statsFile.exists()) return false
            val json = org.json.JSONObject(statsFile.readText())
            val rx = json.optLong("rx_bytes", 0L)
            rx > 0L
        } catch (e: Exception) {
            false
        }
    }

    private fun hbHasBytes(): Boolean {
        return try {
            val inst = dev.hydra.hydra_client.HydraVpnService.instance ?: return false
            val f = java.io.File(inst.filesDir, "hb_stats.json")
            if (!f.exists()) return false
            val j = org.json.JSONObject(f.readText())
            (j.optLong("rx_bytes") + j.optLong("tx_bytes")) > 0L
        } catch (_: Exception) { false }
    }

    private fun readHbStats(): Pair<Long, Long>? {
        return try {
            val inst = dev.hydra.hydra_client.HydraVpnService.instance ?: return null
            val f = java.io.File(inst.filesDir, "hb_stats.json")
            if (!f.exists()) return null
            val j = org.json.JSONObject(f.readText())
            Pair(j.optLong("rx_bytes"), j.optLong("tx_bytes"))
        } catch (_: Exception) { null }
    }

    private fun wdttSocksAlive(): Boolean = try {
        java.net.Socket().connect(java.net.InetSocketAddress("127.0.0.1", 1082), 300)
        true
    } catch (_: Exception) { false }

    /**
     * Fast failover: переключение при деградации (UNSTABLE ×2 или stall).
     * Вызывается из ReconnectManager.
     */
    private val ncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var networkChangeJob: kotlinx.coroutines.Job? = null

    /** Смена сети (WiFi<->mobile): штрафы сети-предшественницы недействительны,
     *  активный туннель переподнимаем со свежим probe. */
    fun onNetworkChanged() {
        networkChangeJob?.cancel()
        networkChangeJob = ncScope.launch {
            kotlinx.coroutines.delay(3000) // дебаунс: даём сети устаканиться
            val ctx = context ?: return@launch
            val kid = keyId ?: return@launch
            // 0.7.4: explicit-режим — переподнимаем тот же протокол, без подмены
            val expl = if (dev.hydra.hydra_client.HydraVpnService.sessionExplicit) dev.hydra.hydra_client.MainActivity.explicitProtocol else null  // 0.7.6
            if (expl != null) {
                kotlinx.coroutines.delay(3000)
                if (!HydraVpnService.isRunning) {
                    Log.i(TAG, "Network changed in explicit mode (idle) — reconnecting $expl")
                    connectExplicit(ctx, kid, expl)
                    return@launch
                }
                // 0.8.8: живость = пер-протокольное payload-доказательство, НЕ tx-счётчики.
                // Раньше tx рос от контрольных повторов самого мёртвого протокола
                // (AWG handshake-ретраи, aivpn TLS-ретраи, bridge SYN) — туннель-зомби
                // жил 20+ минут. Теперь: AWG = возраст UAPI-handshake; bridge = hb rx дельта.
                val hb0 = readHbStats()
                kotlinx.coroutines.delay(6000)
                val hb1 = readHbStats()
                val alive = when (expl) {
                    "AmneziaWG" -> HydraVpnService.isHandshakeComplete &&
                            HydraVpnService.handshakeAgeMs() in 0L..180_000L
                    else -> (hb0 != null && hb1 != null && hb1.first > hb0.first)  // только payload rx от сервера
                }
                if (!alive) {
                    activeProvider?.disconnect()
                    activeProvider = null
                    HealthMonitor.stop()
                    kotlinx.coroutines.delay(1500)
                    // 0.7.8: на whiteList-сети тот же протокол жить не может — честный переход
                    var wl2 = false
                    try {
                        val kf3 = keyfilekt.Keyfile.parse(java.io.File(ctx.filesDir, "keys/${sanitizeKeyId(kid)}.key").absolutePath)
                        val ep3 = kf3.peers.firstOrNull()?.endpoint ?: ""
                        if (ep3.isNotEmpty()) EnvironmentProbe.probe(ctx, ep3)
                        wl2 = EnvironmentProbe.whiteListDetected
                    } catch (_: Exception) {}
                    // 0.8.3: чистая сепарация explicit — никогда не переключаемся на другой протокол
                    Log.w(TAG, "═══════════════════════════════════════════════════════════")
                    Log.w(TAG, "[ROAM] смена сети в explicit mode")
                    Log.w(TAG, "[ROAM] текущий протокол: $expl, туннель мёртв (нет трафика)")
                    Log.w(TAG, "[ROAM] решение: переподключаем ТОЛЬКО $expl (no failover by contract)")
                    Log.w(TAG, "═══════════════════════════════════════════════════════════")
                    Log.w(TAG, "Network changed in explicit mode — tunnel stale, reconnecting $expl")
                    connectExplicit(ctx, kid, expl)
                } else {
                    Log.i(TAG, "═══════════════════════════════════════════════════════════")
                    Log.i(TAG, "[ROAM] смена сети в explicit mode")
                    Log.i(TAG, "[ROAM] текущий протокол: $expl, туннель жив (есть трафик)")
                    Log.i(TAG, "[ROAM] решение: оставляем как есть (no failover by contract)")
                    Log.i(TAG, "═══════════════════════════════════════════════════════════")
                    Log.i(TAG, "Network changed in explicit mode — tunnel alive, kept")
                }
                return@launch
            }
            ProtocolMemory.clearPenalties(ctx)
            if (!HydraVpnService.isRunning) {
                Log.i(TAG, "Network changed (idle): penalties cleared")
                return@launch
            }
            // 0.8.8: roam-aware живость по пер-протокольным источникам payload (НЕ tx)
            // Раньше tx рос от handshake-ретраев AWG / TLS-ретраев aivpn / SYN bridge —
            // мёртвый туннель объявлялся "жив" на 20+ минут. Теперь:
            //   AWG         = UAPI handshake age < 180с
            //   bridge      = дельта hb rx (реальный трафик от сервера)
            val hb0 = readHbStats()
            kotlinx.coroutines.delay(6000)
            val hb1 = readHbStats()
            val currentProtoAlive = dev.hydra.hydra_client.HydraVpnService.activeProviderName
            val aliveByProto = when (currentProtoAlive) {
                "AmneziaWG" -> HydraVpnService.isHandshakeComplete &&
                        HydraVpnService.handshakeAgeMs() in 0L..180_000L
                else -> (hb0 != null && hb1 != null && hb1.first > hb0.first)
            }
            if (!aliveByProto) {
                Log.w(TAG, "═══════════════════════════════════════════════════════════")
                Log.w(TAG, "[ROAM] смена сети в AUTO, протокол $currentProtoAlive, payload=0 — туннель мёртв")
                Log.w(TAG, "[ROAM] решение: triggerReconnect(roam-stale)")
                Log.w(TAG, "═══════════════════════════════════════════════════════════")
                dev.hydra.hydra_client.tunnel.ReconnectManager.triggerReconnect("roam-stale")
                return@launch
            }
            Log.i(TAG, "Roam survived: $currentProtoAlive alive (handshake/payload confirmed) — tunnel kept")
            
            // 0.7.2: whiteList-aware failover
            // Если новая сеть = whiteList и текущий протокол = AIVPN/AWG -> немедленный failover на WDTT
            // 0.7.4: свежая проба — кэш whiteListDetected остался от СТАРОЙ сети
            try {
                val kf2 = keyfilekt.Keyfile.parse(java.io.File(ctx.filesDir, "keys/${sanitizeKeyId(kid)}.key").absolutePath)
                val ep2 = kf2.peers.firstOrNull()?.endpoint ?: ""
                if (ep2.isNotEmpty()) EnvironmentProbe.probe(ctx, ep2)
            } catch (_: Exception) {}
            val newWhiteList = EnvironmentProbe.whiteListDetected
            val currentProto = dev.hydra.hydra_client.HydraVpnService.activeProviderName
            if (newWhiteList && currentProto in listOf("AIVPN", "AmneziaWG")) {
                Log.w(TAG, "═══════════════════════════════════════════════════════════")
                Log.w(TAG, "[FAILOVER] текущий протокол $currentProto мёртв")
                Log.w(TAG, "[FAILOVER] причина: whiteList detected в explicit mode")
                Log.w(TAG, "[FAILOVER] переходим на WDTT (единственный протокол для whiteList)")
                Log.w(TAG, "═══════════════════════════════════════════════════════════")
                Log.w(TAG, "Network changed to whiteList — forcing failover from $currentProto to WDTT")
                activeProvider?.disconnect()
                activeProvider = null
                HealthMonitor.stop()
                kotlinx.coroutines.delay(1500)
                autoConnect(ctx, kid)
                return@launch
            }
            

            activeProvider?.disconnect()
            activeProvider = null
            HealthMonitor.stop()
            kotlinx.coroutines.delay(1500)
            autoConnect(ctx, kid)
        }
    }

    // 0.7.1: explicit-подключение: один провайдер, peer-данные из keyfile, БЕЗ failover
    suspend fun connectExplicit(ctx: Context, kid: String, protocol: String): Boolean =
        connectMutex.withLock {
            context = ctx
            keyId = kid
            Log.i(TAG, "═══════════════════════════════════════════════════════════")
            Log.i(TAG, "[MODE] EXPLICIT — пользователь выбрал протокол $protocol")
            Log.i(TAG, "[CONTRACT] Только $protocol, без failover на другие протоколы")
            Log.i(TAG, "═══════════════════════════════════════════════════════════")
            supervisedRetryJob?.cancel()
            dev.hydra.hydra_client.HydraVpnService.sessionExplicit = true  // 0.7.6  // 0.7.4: фоновые ретраи не мешают явному тапу
            val keysDir = java.io.File(ctx.filesDir, "keys")
            val keyFile = java.io.File(keysDir, "${sanitizeKeyId(kid)}.key")
            if (!keyFile.exists()) {
                Log.e(TAG, "explicit: key file not found: ${keyFile.absolutePath}")
                return@withLock false
            }
            val kf = keyfilekt.Keyfile.parse(keyFile.absolutePath)
            val peer = kf.peers.firstOrNull { it.protocol == protocol }
            if (peer == null) {
                Log.e(TAG, "explicit: no peer for protocol $protocol in key")
                return@withLock false
            }
            val provider = when (peer.protocol) {
                "awg" -> AWGTunnelProvider(ctx, peer.serverId, peer.label, peer)
                "aivpn" -> AIVPNTunnelProvider(ctx, peer.serverId, peer.label, peer)
                "wdtt" -> WDTTTunnelProvider(ctx, peer.serverId, peer.label, peer)
                else -> return@withLock false
            }
            Log.i(TAG, "[EVIDENCE] проверяем ${provider.name}: handshake + traffic + health")
            val ok = tryConnectWithEvidence(provider, kid)
            if (ok) {
                Log.i(TAG, "✅ explicit ${provider.name} connected")
                ProtocolMemory.recordSuccess(ctx, provider.name)
                activeProvider = provider
                HealthMonitor.statusListener = { st -> ReconnectManager.onHealth(st) }
                HealthMonitor.start()
            } else {
                Log.w(TAG, "❌ explicit ${provider.name} failed — no failover by contract")
                ProtocolMemory.recordFailure(ctx, provider.name)
                provider.disconnect()
            }
            ok
        }

    suspend fun failover(currentProvider: TunnelProvider?): Boolean {

        // 0.6.3: explicit-режим — НЕ переключаемся на другой протокол за спиной пользователя
        if (dev.hydra.hydra_client.HydraVpnService.sessionExplicit) {
            Log.w(TAG, "failover blocked: explicit session (${dev.hydra.hydra_client.MainActivity.explicitProtocol})")
            return false
        }
        val ctx = context ?: return false
        val kid = keyId ?: return false
        val actualCurrent = currentProvider ?: activeProvider // 0.4.5: защита от null
        
        Log.i(TAG, "Failover triggered, current=${actualCurrent?.name}")
        DevLog.e("SmartConnectManager", "Failover: current=${actualCurrent?.name}, memory=${ProtocolMemory.dumpState(ctx)}")
        actualCurrent?.let { ProtocolMemory.recordFailure(ctx, it.name) }
        actualCurrent?.disconnect()
        
        uiMessage("Соединение потеряно, переподключение...")
        delay(2000) // даём teardown завершиться
        
        // Перезапускаем autoConnect (он сам выберет следующего кандидата)
        val newProvider = autoConnect(ctx, kid)
        return newProvider != null
    }
    
    private fun uiMessage(m: String) {
        // 0.7.1: в explicit-режиме не перетираем статус-сообщение UI авто-текстами
        if (dev.hydra.hydra_client.HydraVpnService.sessionExplicit) return  // 0.7.6
        lastStatusMessage = m
        statusMessageCallback?.invoke(m)
    }
    
    private fun sanitizeKeyId(keyId: String): String {
        return keyId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    }
    
    fun disconnect() {
        Log.i(TAG, "Disconnecting active provider")
        activeProvider?.disconnect()
        activeProvider = null
        HealthMonitor.stop()
    }
}
