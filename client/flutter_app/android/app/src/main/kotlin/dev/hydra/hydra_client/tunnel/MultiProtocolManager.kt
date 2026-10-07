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

    @Volatile private var rxBytes: Long = 0
    @Volatile private var txBytes: Long = 0
    fun getTrafficStats(): Pair<Long, Long> = Pair(rxBytes, txBytes)

    suspend fun startAivpn(connKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val binary = extractBinary("aivpn-client")
            aivpnProcess = ProcessBuilder(listOf(
                binary.absolutePath,
                "--connection-key", connKey,
                "--proxy-listen", "127.0.0.1:$AIVPN_SOCKS_PORT"
            )).redirectErrorStream(false).start()
            streamToLogcat(aivpnProcess!!, "aivpn-client")
            Log.i(TAG, "AIVPN started, port=$AIVPN_SOCKS_PORT")

            startBridgeBinary(AIVPN_SOCKS_PORT)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AIVPN: ${e.message}")
            false
        }
    }

        suspend fun startWdtt(serverAddr: String, vkHashes: String, connPassword: String, workers: Int = 24): Boolean = withContext(Dispatchers.IO) {
        try {
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
            
            wdttProcess = ProcessBuilder(args).redirectErrorStream(false).start()
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
        Log.i(TAG, "All stopped (including hydra-bridge)")
    }

    private fun streamToLogcat(process: Process, tag: String) {
        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { Log.i("[$tag]", it) }
            } catch (e: Exception) {
                Log.d(TAG, "$tag stdout closed")
            }
        }.start()
        Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { Log.e("[$tag]", it) }
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
