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
import keyfilekt.Keyfile
import keyfilekt.KeyFile
import keyfilekt.Peer
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

    /** QR / шаринг / файл / диплинк: кладём payload в pending_import */
    private fun handleIncomingIntent(intent: Intent?) {
        val i = intent ?: return
        try {
            when {
                i.action == Intent.ACTION_SEND && i.type == "text/plain" -> {
                    val text = i.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) SettingsStore.setPendingImport(this, text)
                }
                i.action == Intent.ACTION_VIEW -> {
                    val uri = i.data ?: return
                    when (uri.scheme) {
                        "content", "file" -> {
                            val dir = java.io.File(cacheDir, "import").apply { mkdirs() }
                            val tmp = java.io.File(dir, "pending.key")
                            contentResolver.openInputStream(uri)?.use { inp ->
                                tmp.outputStream().use { out -> inp.copyTo(out) }
                            } ?: return
                            SettingsStore.setPendingImport(this, tmp.absolutePath)
                        }
                        else -> SettingsStore.setPendingImport(this, uri.toString())
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("HydraImport", "handleIncomingIntent: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Keyfile.initRuntime()
        setupSettingsChannel(flutterEngine)

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
                    val endpoint = prov?.endpoint ?: ""
                    val server = prov?.serverId ?: "fi-polygon"
                    val country = when (server.split("-").first()) {
                        "fi" -> "Finland"; "de" -> "Germany"; "nl" -> "Netherlands"
                        else -> server.split("-").first().uppercase()
                    }
                    val json = org.json.JSONObject().apply {
                        put("protocol", protoName)
                        put("endpoint", endpoint)
                        put("serverId", server)
                        put("country", country)
                        put("rtt", rtt)
                    }
                    result.success(json.toString())
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
                "getWdttStats" -> {
                    result.success(HydraVpnService.wdttStats())
                }
                "getTunnelStatus" -> {
                    Log.d("TunnelStatus", "isRunning=${HydraVpnService.isRunning}, isHandshakeComplete=${HydraVpnService.isHandshakeComplete}")
                    val mp = HydraVpnService.mpState()
                    val status = when {
                        !HydraVpnService.isRunning -> "disconnected"
                        mp != null && mp.first && !mp.second -> "disconnected"
                        HydraVpnService.isHandshakeComplete || (mp != null && mp.first && mp.second) -> "connected"
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

    private fun setupSettingsChannel(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.hydra/settings").setMethodCallHandler { call, result ->
            when (call.method) {
                "getSettings" -> result.success(mapOf(
                    "vkHashes" to SettingsStore.getVkHashes(this),
                    "autoWorkers" to SettingsStore.isAutoWorkers(this),
                    "workers" to SettingsStore.getManualWorkers(this),
                    "resolvedWorkers" to SettingsStore.resolveWorkers(this),
                    "advancedUi" to SettingsStore.isAdvancedUi(this),
                    "mtu" to SettingsStore.getMtu(this),
                    "dnsOverride" to SettingsStore.getDnsOverride(this),
                    "killSwitch" to SettingsStore.isKillSwitch(this),
                    "updateUrl" to SettingsStore.getUpdateUrl(this),
                    "versionName" to (runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""),
                    "onboarded" to SettingsStore.isOnboarded(this),
                    "preferredProtocol" to SettingsStore.getPreferredProtocol(this)
                ))
                "setVkHashes" -> { SettingsStore.setVkHashes(this, call.argument<String>("value") ?: ""); result.success(true) }
                "setAutoWorkers" -> { SettingsStore.setWorkersMode(this, call.argument<Boolean>("auto") ?: true); result.success(true) }
                "setWorkers" -> { SettingsStore.setManualWorkers(this, call.argument<Int>("workers") ?: 24); result.success(true) }
                "setAdvancedUi" -> { SettingsStore.setAdvancedUi(this, call.argument<Boolean>("value") ?: false); result.success(true) }
                "setOnboarded" -> { SettingsStore.setOnboarded(this, call.argument<Boolean>("value") ?: false); result.success(true) }
                "setPreferredProtocol" -> { SettingsStore.setPreferredProtocol(this, call.argument<String>("value") ?: "auto"); result.success(true) }
                "setMtu" -> { SettingsStore.setMtu(this, call.argument<Int>("mtu") ?: 1420); result.success(null) }
                "setDnsOverride" -> { SettingsStore.setDnsOverride(this, call.argument<String>("value") ?: ""); result.success(null) }
                "setKillSwitch" -> { SettingsStore.setKillSwitch(this, call.argument<Boolean>("value") ?: false); result.success(null) }
                "setUpdateUrl" -> { SettingsStore.setUpdateUrl(this, call.argument<String>("value") ?: ""); result.success(null) }
                "getCacheDir" -> result.success(cacheDir.absolutePath)
                "getAbis" -> result.success(android.os.Build.SUPPORTED_ABIS.toList())
                "getSkippedUpdate" -> result.success(SettingsStore.getSkippedUpdate(this))
                "getPendingImport" -> result.success(SettingsStore.getPendingImport(this))
                "setPendingImport" -> { SettingsStore.setPendingImport(this, call.argument<String>("value") ?: ""); result.success(null) }
                "setSkippedUpdate" -> { SettingsStore.setSkippedUpdate(this, call.argument<String>("value") ?: ""); result.success(null) }
                "installApk" -> {
                    val path = call.argument<String>("path") ?: ""
                    val f = java.io.File(path)
                    if (!f.exists()) { result.error("NO_FILE", path, null) } else {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(intent)
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
