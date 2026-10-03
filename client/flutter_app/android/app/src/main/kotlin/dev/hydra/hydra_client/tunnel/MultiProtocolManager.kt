package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Управляет запуском клиентских бинарников aivpn-client и wdtt-client
 * из assets, и запуском SOCKS5 bridge
 */
class MultiProtocolManager(private val context: Context) {
    // Счётчики трафика (байты)
    @Volatile private var rxBytes: Long = 0
    @Volatile private var txBytes: Long = 0
    
    fun getTrafficStats(): Pair<Long, Long> = Pair(rxBytes, txBytes)
    
    fun addRx(bytes: Long) { rxBytes += bytes }
    fun addTx(bytes: Long) { txBytes += bytes }
    companion object {
        private const val TAG = "MultiProtocolManager"
        private const val AIVPN_SOCKS_PORT = 1081
        private const val WDTT_SOCKS_PORT = 1082
    }

    private var aivpnProcess: Process? = null
    private var wdttProcess: Process? = null
    private var bridge: SocksBridge? = null

    /**
     * Запускает AIVPN бинарник в SOCKS5 режиме
     */
    suspend fun startAivpn(connKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val binaryFile = extractBinary("aivpn-client")
            val cmd = listOf(
                binaryFile.absolutePath,
                "--connection-key", connKey,
                "--proxy-listen", "127.0.0.1:$AIVPN_SOCKS_PORT"
            )
            aivpnProcess = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()
            
            // Читаем stdout в фоне
            Thread {
                try {
                    aivpnProcess!!.inputStream.bufferedReader().forEachLine { line ->
                        android.util.Log.i("[aivpn-client]", line)
                        if (line.contains("AIVPN-STATUS")) dev.hydra.hydra_client.HydraVpnService.onMultiProtoStatus(line)
                    }
                } catch (e: Exception) {
                    android.util.Log.d(TAG, "stdout closed")
                }
            }.start()
            
            // Читаем stderr в фоне
            Thread {
                try {
                    aivpnProcess!!.errorStream.bufferedReader().forEachLine { line ->
                        android.util.Log.e("[aivpn-client]", line)
                    }
                } catch (e: Exception) {
                    android.util.Log.d(TAG, "stderr closed")
                }
            }.start()
            Log.i(TAG, "AIVPN started, port=$AIVPN_SOCKS_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AIVPN: ${e.message}")
            false
        }
    }

    /**
     * Запускает WDTT бинарник в SOCKS5 режиме
     */
    suspend fun startWdtt(vkHashes: String, connPassword: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val binaryFile = extractBinary("wdtt-client")
            val cmd = listOf(
                binaryFile.absolutePath,
                "--mode", "socks5",
                "--socks-listen", "127.0.0.1:$WDTT_SOCKS_PORT",
                "--vk-hashes", vkHashes,
                "--conn-password", connPassword
            )
            wdttProcess = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()
            
            // Читаем stdout в фоне
            Thread {
                try {
                    wdttProcess!!.inputStream.bufferedReader().forEachLine { line ->
                        android.util.Log.i("[wdtt-client]", line)
                        if (line.contains("WDTT-STATUS")) dev.hydra.hydra_client.HydraVpnService.onMultiProtoStatus(line)
                    }
                } catch (e: Exception) {
                    android.util.Log.d(TAG, "stdout closed")
                }
            }.start()
            
            // Читаем stderr в фоне
            Thread {
                try {
                    wdttProcess!!.errorStream.bufferedReader().forEachLine { line ->
                        android.util.Log.e("[wdtt-client]", line)
                    }
                } catch (e: Exception) {
                    android.util.Log.d(TAG, "stderr closed")
                }
            }.start()
            Log.i(TAG, "WDTT started, port=$WDTT_SOCKS_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WDTT: ${e.message}")
            false
        }
    }

    /**
     * Запускает SOCKS5 bridge с указанным upstream
     */
    fun startBridge(tunFd: android.os.ParcelFileDescriptor, protocol: String) {
        val port = when (protocol) {
            "aivpn" -> AIVPN_SOCKS_PORT
            "wdtt" -> WDTT_SOCKS_PORT
            else -> throw IllegalArgumentException("Unknown protocol: $protocol")
        }
        bridge = SocksBridge(tunFd, "127.0.0.1", port)
        bridge?.start()
    }

    fun stopAll() {
        bridge?.stop()
        bridge = null
        aivpnProcess?.destroy()
        aivpnProcess = null
        wdttProcess?.destroy()
        wdttProcess = null
        Log.i(TAG, "All stopped")
    }

    private fun extractBinary(name: String): File {
        // SELinux запрещает execve из app_data_file (Android 10+),
        // поэтому исполняемые файлы упакованы как native-библиотеки:
        // aivpn-client -> libaivpn_client.so, wdtt-client -> libwdtt_client.so
        val libName = "lib" + name.replace("-", "_") + ".so"
        val native = File(context.applicationInfo.nativeLibraryDir, libName)
        if (!native.exists()) {
            throw IllegalStateException("Native binary not found: ${native.absolutePath}")
        }
        Log.i(TAG, "Native binary: ${native.absolutePath} (${native.length()} bytes)")
        return native
    }
}
