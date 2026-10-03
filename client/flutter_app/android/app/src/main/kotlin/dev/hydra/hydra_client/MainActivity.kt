package dev.hydra.hydra_client

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import dev.hydra.hydra_client.tunnel.SmartConnectManager
import dev.hydra.hydra_client.tunnel.HealthMonitor
import dev.hydra.hydra_client.tunnel.ReconnectManager
import dev.hydra.hydra_client.tunnel.AWGTunnelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.flutter.plugin.common.MethodChannel
import keyfile.Keyfile
import keyfile.KeyFile
import keyfile.Peer
import java.io.File
import java.io.FileOutputStream
import android.util.Log

class MainActivity : FlutterActivity() {
    private val KEYFILE_CHANNEL = "dev.hydra/keyfile"
    private val TUNNEL_CHANNEL = "dev.hydra/tunnel"
    private val PICK_FILE_REQUEST = 1001
    private val VPN_REQUEST_CODE = 2001
    private var pendingResult: MethodChannel.Result? = null
    private var tunnelPendingResult: MethodChannel.Result? = null

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("hydra_prefs", MODE_PRIVATE)
    }
    private val keysDir: File by lazy {
        File(filesDir, "keys").apply { mkdirs() }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Keyfile.initRuntime()

        // === KeyFile channel ===
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, KEYFILE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "pickAndParseKeyFile" -> {
                    pendingResult = result
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(intent, PICK_FILE_REQUEST)
                }

                "listSavedKeys" -> {
                    try {
                        result.success(buildKeysList())
                    } catch (e: Exception) {
                        result.error("LIST_ERROR", e.message ?: "Unknown error", e.toString())
                    }
                }

                "deleteKey" -> {
                    val keyId = call.argument<String>("keyId")
                    if (keyId == null) {
                        result.error("INVALID_ARGUMENT", "keyId is required", null)
                        return@setMethodCallHandler
                    }
                    val ok = File(keysDir, "${sanitizeKeyId(keyId)}.key").delete()
                    if (ok && prefs.getString("active_key_id", null) == keyId) {
                        prefs.edit().remove("active_key_id").apply()
                    }
                    result.success(ok)
                }

                "setActiveKey" -> {
                    val keyId = call.argument<String>("keyId")
                    if (keyId == null) {
                        result.error("INVALID_ARGUMENT", "keyId is required", null)
                        return@setMethodCallHandler
                    }
                    prefs.edit().putString("active_key_id", keyId).apply()
                    result.success(true)
                }

                "getActiveKey" -> {
                    result.success(prefs.getString("active_key_id", null))
                }

                "version" -> result.success("hydra-keyfile/1.2.0")

                else -> result.notImplemented()
            }
        }

        // === Tunnel channel ===
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, TUNNEL_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startTunnel" -> {
                    val keyId = call.argument<String>("keyId")
                    if (keyId == null) {
                        result.error("INVALID_ARGUMENT", "keyId required", null)
                        return@setMethodCallHandler
                    }

                    val vpnIntent = VpnService.prepare(this)
                    if (vpnIntent == null) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val provider = AWGTunnelProvider(this@MainActivity, "fi-polygon", "France · Hetzner")
                            SmartConnectManager.registerProvider(provider)
                            val connected = SmartConnectManager.autoConnect(this@MainActivity, keyId)
                            result.success(connected != null)
                        }
                    } else {
                        tunnelPendingResult = result
                        startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
                    }
                }

                "stopTunnel" -> {
                    val intent = Intent(this, HydraVpnService::class.java)
                    stopService(intent)
                    result.success(true)
                }

                "isTunnelRunning" -> {
                    result.success(false)
                }
                "getServerInfo" -> {
                    // Реальный RTT через туннель (HealthMonitor), не probe-таймаут
                    val rtt = HealthMonitor.lastRttMs
                    val prov = dev.hydra.hydra_client.tunnel.SmartConnectManager.active
                    val protoName = prov?.name ?: "AmneziaWG"
                    val server = prov?.serverId ?: "fi-polygon"
                    val country = when (server.split("-").first()) {
                        "fi" -> "Finland"; "de" -> "Germany"; "nl" -> "Netherlands"
                        else -> server.split("-").first().uppercase()
                    }
                    val info = if (rtt > 0) {
                        "$protoName · $server · $country · ${rtt}ms"
                    } else {
                        "$protoName · $server · $country"
                    }
                    result.success(info)
                }
                                "disconnect" -> {
                    Log.i("Disconnect", "MethodChannel: disconnect called")
                    SmartConnectManager.disconnect()
                    HealthMonitor.stop()
                    ReconnectManager.reset()
                    result.success(null)
                }
                "getStats" -> {
                    result.success(HydraVpnService.statsJson())
                }
                "getTunnelStatus" -> {
                    Log.d("TunnelStatus", "isHandshakeComplete=${HydraVpnService.isHandshakeComplete}, " +
                        "handshakeAgeMs=${HydraVpnService.handshakeAgeMs()}, " +
                        "lastStatus=${HealthMonitor.lastStatus}")
                    val status = when {
                        !HydraVpnService.isRunning -> "disconnected"
                        HealthMonitor.lastStatus == HealthMonitor.HealthStatus.DOWN -> "blocked"
                        HealthMonitor.lastStatus == HealthMonitor.HealthStatus.UNSTABLE -> "unstable"
                        HydraVpnService.handshakeAgeMs() in 0..180_000 -> "connected"
                        else -> "connecting"
                    }
                    result.success(status)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // === KeyFile import ===
        if (requestCode == PICK_FILE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                var tempFile: File? = null
                try {
                    tempFile = File.createTempFile("hydra_key", ".key", cacheDir)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }

                    val kf: KeyFile = Keyfile.parse(tempFile.absolutePath)
                    val dest = File(keysDir, "${sanitizeKeyId(kf.keyId)}.key")
                    tempFile.copyTo(dest, overwrite = true)

                    if (prefs.getString("active_key_id", null) == null) {
                        prefs.edit().putString("active_key_id", kf.keyId).apply()
                    }

                    pendingResult?.success(parseToMap(kf))
                    pendingResult = null
                } catch (e: Exception) {
                    pendingResult?.error("PARSE_ERROR", e.message ?: "Unknown error", e.toString())
                    pendingResult = null
                } finally {
                    tempFile?.delete()
                }
            } else {
                pendingResult?.error("CANCELLED", "User cancelled", null)
                pendingResult = null
            }
        }

        // === VPN permission ===
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                val keyId = prefs.getString("active_key_id", null)
                if (keyId != null) {
                    CoroutineScope(Dispatchers.Main).launch {
                        val provider = AWGTunnelProvider(this@MainActivity, "fi-polygon", "France · Hetzner")
                        SmartConnectManager.registerProvider(provider)
                        val connected = SmartConnectManager.autoConnect(this@MainActivity, keyId)
                        tunnelPendingResult?.success(connected != null)
                    }
                } else {
                    tunnelPendingResult?.error("NO_KEY", "No active key", null)
                }
            } else {
                tunnelPendingResult?.error("CANCELLED", "VPN permission denied", null)
            }
            tunnelPendingResult = null
        }
    }

    // === helpers ===

    private fun sanitizeKeyId(raw: String): String {
        val s = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return if (s.isBlank()) "key_${System.currentTimeMillis()}" else s
    }

    private fun parseToMap(kf: KeyFile): Map<String, Any?> {
        val peerCount = Keyfile.peerCount(kf).toInt()
        val peersList = mutableListOf<Map<String, Any?>>()
        for (i in 0 until peerCount) {
            val p: Peer? = Keyfile.peerAt(kf, i.toLong())
            if (p != null) {
                val peerMap = mutableMapOf<String, Any?>(
                    "protocol" to p.protocol,
                    "serverId" to p.serverId,
                    "endpoint" to p.endpoint,
                    "label" to p.label
                )

                if (p.protocol == "awg") {
                    peerMap["publicKey"] = p.publicKey
                    peerMap["privateKey"] = p.privateKey
                    peerMap["address"] = p.address
                    peerMap["dns"] = p.dns
                    peerMap["jc"] = p.jc
                    peerMap["jmin"] = p.jmin
                    peerMap["jmax"] = p.jmax
                    peerMap["s1"] = p.s1
                    peerMap["s2"] = p.s2
                    peerMap["s3"] = p.s3
                    peerMap["s4"] = p.s4
                    peerMap["headerProtectionKey"] = p.headerProtectionKey
                }

                peersList.add(peerMap)
            }
        }
        return mapOf(
            "version" to kf.version.toInt(),
            "keyId" to kf.keyId,
            "expiresAt" to Keyfile.expiresAtString(kf),
            "maxDevices" to kf.maxDevices.toInt(),
            "clientName" to kf.clientName,
            "peers" to peersList
        )
    }

    private fun buildKeysList(): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        val files = keysDir.listFiles { f -> f.name.endsWith(".key") } ?: return out
        for (f in files.sortedBy { it.name }) {
            try {
                out.add(parseToMap(Keyfile.parse(f.absolutePath)))
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun startVpnService(keyId: String) {
        val intent = Intent(this, HydraVpnService::class.java).apply {
            putExtra(HydraVpnService.EXTRA_KEY_ID, keyId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
