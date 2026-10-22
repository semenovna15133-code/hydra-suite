package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Управляет жизненным циклом клиентских бинарников (aivpn/wdtt)
 * и userspace-моста hydra-bridge (gVisor netstack).
 *
 * Цепочка data-plane:
 *   App → TUN fd → hydra-bridge (gVisor) → SOCKS5 127.0.0.1:1081
 *         → aivpn-client → UDP-туннель → сервер → internet
 */
class MultiProtocolManager(private val context: Context) {
    companion object {
        private const val TAG = "MultiProtocolManager"
        private const val AIVPN_SOCKS_PORT = 1081
        private const val WDTT_SOCKS_PORT = 1082
        private const val BRIDGE_SOCKET = "hydra_tun_fd"
    }

    private var aivpnProcess: Process? = null
    private var wdttProcess: Process? = null
    private var bridgeProcess: Process? = null

    @Volatile var started: Boolean = false
    @Volatile var startedAt: Long = 0L

    private fun debounce(tag: String): Boolean {
        val age = System.currentTimeMillis() - startedAt
        return if (started && processesAlive() && age < 20_000) {
            Log.w(TAG, "$tag debounced: tunnel alive, age=${age}ms")
            true
        } else false
    }
    @Volatile var wdttWorkers: Int = 0
    @Volatile var wdttHashes: Int = 0
    @Volatile var wdttActive: Int = 0

    fun wdttStatsJson(): String =
        """{"workers":$wdttWorkers,"hashes":$wdttHashes,"active":$wdttActive}"""

    private var aivpnHandshakeFailCount = 0
    private var wdttZeroSince: Long = 0L
    @Volatile private var wdttDeadNotified: Boolean = false
    
    private fun parseWdttLine(tag: String, line: String) {
        if (tag == "wdtt-client") {
            try {
                Regex("Воркеров: (\\d+)").find(line)?.let { wdttWorkers = it.groupValues[1].toInt() }
                Regex("Хешей: (\\d+)").find(line)?.let { wdttHashes = it.groupValues[1].toInt() }
                Regex("Активных: (\\d+)").find(line)?.let { wdttActive = it.groupValues[1].toInt() }
            } catch (_: Exception) { }
            // WDTT liveness: смерть потоков = сигнал к failover, а не вечное "connected"
            if (line.contains("Распределение потоков")) { wdttZeroSince = 0L; wdttDeadNotified = false }
            if (line.contains("Активных:")) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (wdttActive == 0) {
                    if (wdttZeroSince == 0L) { wdttZeroSince = now; Log.w(TAG, "WDTT active=0 started") }
                    if (now - wdttZeroSince >= 45_000L && !wdttDeadNotified) {
                        wdttDeadNotified = true
                        Log.e(TAG, "WDTT dead: active=0 for >=45s")
                        ReconnectManager.onWdttDead("active=0 for >=45s")
                    }
                } else { wdttZeroSince = 0L; wdttDeadNotified = false }
            }
            if ((line.contains("PAUSED") || line.contains("Лимит попыток исчерпан")) && !wdttDeadNotified) {
                wdttDeadNotified = true
                Log.e(TAG, "WDTT dead: client PAUSED (retry limit exhausted)")
                ReconnectManager.onWdttDead("PAUSED: retry limit exhausted")
            }
        }
        // Zombie-детект AIVPN: >=3 "handshake never connected" подряд -> немедленный штраф
        if (tag == "aivpn-client" && line.contains("handshakes never connected")) {
            val match = Regex("(\\d+) consecutive handshakes never connected").find(line)
            if (match != null) {
                val count = match.groupValues[1].toInt()
                if (count >= 3) {
                    Log.e(TAG, "AIVPN zombie detected: $count consecutive handshake failures — penalizing AIVPN")
                    ProtocolMemory.recordFailure(context, "AIVPN")
                }
            }
        }
    }
    fun processesAlive(): Boolean =
        (wdttProcess?.isAlive == true) || (aivpnProcess?.isAlive == true) || (bridgeProcess?.isAlive == true)

    @Volatile private var rxBytes: Long = 0
    @Volatile private var txBytes: Long = 0
    fun getTrafficStats(): Pair<Long, Long> = Pair(rxBytes, txBytes)


    /** Убивает все висячие процессы своих бинарников (same uid) через /proc. */
    private fun killOrphans(substr: String) {
        try {
            val myPid = android.os.Process.myPid()
            java.io.File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == myPid) return@forEach
                try {
                    val cmd = java.io.File(d, "cmdline").readText()
                    if (cmd.contains(substr)) {
                        android.os.Process.killProcess(pid)
                        Log.w(TAG, "killed orphan: $substr pid=$pid")
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    suspend fun startAivpn(connKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (debounce("startAivpn")) return@withContext true
            aivpnProcess?.destroy()
            wdttProcess?.destroy()
            killOrphans("libaivpn_client.so")
            killOrphans("libhydra_bridge.so")
            started = true
            startedAt = System.currentTimeMillis()
            val binary = extractBinary("aivpn-client")
            // AIVPN пишет временные файлы (bootstrap descriptor, admin token) в TMPDIR.
            // По умолчанию это /tmp, который закрыт SELinux для untrusted_app на Android.
            // Передаём cacheDir приложения как TMPDIR, чтобы AIVPN писал в доступное место.
            val aivpnTmpDir = java.io.File(context.cacheDir, "aivpn-tmp").apply { mkdirs() }
            val pb = ProcessBuilder(listOf(
                binary.absolutePath,
                "--connection-key", connKey,
                "--proxy-listen", "127.0.0.1:$AIVPN_SOCKS_PORT"
            )).redirectErrorStream(false)
            pb.environment()["TMPDIR"] = aivpnTmpDir.absolutePath
            Log.i(TAG, "AIVPN TMPDIR set to: ${aivpnTmpDir.absolutePath}")
            // Создаём symlink /tmp/aivpn-* → cacheDir через su (AIVPN может игнорировать TMPDIR)
            try {
                val suPb = ProcessBuilder("su", "-c", "ln -sf ${aivpnTmpDir.absolutePath} /tmp/aivpn-*")
                suPb.start().waitFor()
                Log.i(TAG, "Symlink /tmp/aivpn-* -> ${aivpnTmpDir.absolutePath} created")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink: ${e.message}")
            }
            aivpnProcess = pb.start()
            streamToLogcat(aivpnProcess!!, "aivpn-client")
            Log.i(TAG, "AIVPN started, port=$AIVPN_SOCKS_PORT")
        // Race fix: даём AIVPN 3 сек поднять SOCKS5 до старта hydra-bridge
        Thread.sleep(3000)
        Log.i(TAG, "Delay 3s after AIVPN start completed")

            startBridgeBinary(AIVPN_SOCKS_PORT)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AIVPN: ${e.message}")
            false
        }
    }

        suspend fun startWdtt(serverAddr: String, vkHashes: String, connPassword: String, workers: Int = 24): Boolean = withContext(Dispatchers.IO) {
        try {
            if (vkHashes.isBlank() || vkHashes.split(",").all { it.isBlank() }) {
                Log.e(TAG, "startWdtt: empty vkHashes — refusing to spawn")
                return@withContext false
            }
            if (debounce("startWdtt")) return@withContext true
            wdttProcess?.destroy()
            aivpnProcess?.destroy()
            killOrphans("libwdtt_client.so")
            killOrphans("libhydra_bridge.so")
            started = true
            startedAt = System.currentTimeMillis()
            val binary = extractBinary("wdtt-client")
            Log.i(TAG, "WDTT binary: ${binary.absolutePath}")
            Log.i(TAG, "WDTT args: -mode socks5 -socks-listen 127.0.0.1:$WDTT_SOCKS_PORT -vk $vkHashes -peer $serverAddr -password <redacted> -n $workers")
            
            // Правильные флаги: одинарные дефисы, имена как в help
            val args = listOf(
                binary.absolutePath,
                "-mode", "socks5",
                "-socks-listen", "127.0.0.1:$WDTT_SOCKS_PORT",
                "-socks-udp",
                "-vk", vkHashes,
                "-peer", serverAddr,
                "-password", connPassword,
                "-n", workers.toString(),
                "-captcha-mode", "auto",
                "-vkcalls-preflight",
                "-turn-stream-first",
                "-device-id", android.os.Build.MODEL.take(30)
            )
            Log.i(TAG, "WDTT full cmd: ${args.joinToString(" ")}")
            
            val pb = ProcessBuilder(args).redirectErrorStream(false)
            pb.environment()["GODEBUG"] = "netdns=go,netcgo=0"
            wdttProcess = pb.start()
            streamToLogcat(wdttProcess!!, "wdtt-client")
            
            // Проверяем что процесс не умер сразу (wrong flags → мгновенная смерть с -h)
            val exited = wdttProcess!!.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (exited) {
                Log.e(TAG, "wdtt-client died immediately, exit=${wdttProcess!!.exitValue()}")
                return@withContext false
            }
            
            Log.i(TAG, "WDTT started, listen=127.0.0.1:$WDTT_SOCKS_PORT, workers=$workers, hashes=${vkHashes.split(",").size}")
            
            // Ждём поднятия SOCKS-порта
            Thread.sleep(2500)
            
            startBridgeBinary(WDTT_SOCKS_PORT)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WDTT: ${e.message}", e)
            false
        }
    }

    private fun startBridgeBinary(socksPort: Int) {
        bridgeProcess?.destroy()
        bridgeProcess = null
        killOrphans("libhydra_bridge.so")
        val bridge = extractBinary("hydra-bridge")
        bridgeProcess = ProcessBuilder(listOf(
            bridge.absolutePath, BRIDGE_SOCKET, "127.0.0.1:$socksPort"
        )).redirectErrorStream(false).start()
        streamToLogcat(bridgeProcess!!, "hydra-bridge")
        Log.i(TAG, "hydra-bridge started, waiting for TUN fd via @$BRIDGE_SOCKET")
    }

    /** Вызывается из HydraVpnService после establish() TUN */
    fun startBridge(tunFd: ParcelFileDescriptor, protocol: String) {
        sendTunFd(tunFd, BRIDGE_SOCKET)
    }

    private fun sendTunFd(tunFd: ParcelFileDescriptor, socketName: String) {
        Thread {
            try {
                Thread.sleep(800) // ждём пока hydra-bridge начнёт слушать @socket
                Log.i(TAG, "Sending TUN fd via @$socketName")
                val client = android.net.LocalSocket()
                client.connect(android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT))
                // Штатный API Android для SCM_RIGHTS: fd уйдёт вместе со следующей записью
                client.setFileDescriptorsForSend(arrayOf(tunFd.fileDescriptor))
                client.outputStream.write(1)
                client.outputStream.flush()
                Thread.sleep(300)
                client.close()
                Log.i(TAG, "TUN fd sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "sendTunFd failed: ${e.message}")
            }
        }.start()
    }

    fun stopAll() {
        aivpnProcess?.destroy()
        aivpnProcess = null
        wdttProcess?.destroy()
        wdttProcess = null
        bridgeProcess?.destroy()
        bridgeProcess = null
        started = false
        // Удаляем файл статистики чтобы новый bridge стартовал с нуля
        try {
            val statsFile = java.io.File(context.filesDir, "hb_stats.json")
            if (statsFile.exists()) statsFile.delete()
            Log.i(TAG, "Stats file deleted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete stats file: ${e.message}")
        }
        Log.i(TAG, "All stopped (including hydra-bridge)")
    }

    private fun streamToLogcat(process: Process, tag: String) {
        Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { parseWdttLine(tag, it); Log.i("[$tag]", it) }
            } catch (e: Exception) {
                Log.d(TAG, "$tag stdout closed")
            }
        }.start()
        Thread {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { parseWdttLine(tag, it); Log.e("[$tag]", it) }
            } catch (e: Exception) {
                Log.d(TAG, "$tag stderr closed")
            }
        }.start()
    }

    private fun extractBinary(name: String): File {
        // SELinux запрещает execve из app_data_file (Android 10+),
        // поэтому бинарники упакованы как native-библиотеки lib*.so
        val libName = "lib" + name.replace("-", "_") + ".so"
        val native = File(context.applicationInfo.nativeLibraryDir, libName)
        if (!native.exists()) {
            throw IllegalStateException("Native binary not found: ${native.absolutePath}")
        }
        Log.i(TAG, "Native binary: ${native.absolutePath} (${native.length()} bytes)")
        return native
    }
}
