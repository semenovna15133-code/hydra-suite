package dev.hydra.hydra_client

import android.app.Activity
import android.content.Intent
import android.net.Uri
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import keyfile.Keyfile
import keyfile.KeyFile
import keyfile.Peer
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterActivity() {
    private val CHANNEL = "dev.hydra/keyfile"
    private val PICK_FILE_REQUEST = 1001
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Keyfile.touch() // инициализация Go runtime до первого вызова
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "pickAndParseKeyFile" -> {
                    pendingResult = result
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(intent, PICK_FILE_REQUEST)
                }
                
                "version" -> {
                    result.success("hydra-keyfile/1.0.0")
                }
                
                else -> result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == PICK_FILE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                try {
                    // Копируем файл во временную директорию чтобы gomobile мог его прочитать
                    val tempFile = File.createTempFile("hydra_key", ".key", cacheDir)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    val kf: KeyFile = Keyfile.parse(tempFile.absolutePath)
                    
                    val peerCount = Keyfile.peerCount(kf).toInt()
                    val peersList = mutableListOf<Map<String, Any?>>()
                    
                    for (i in 0 until peerCount) {
                        val p: Peer? = Keyfile.peerAt(kf, i.toLong())
                        if (p != null) {
                            peersList.add(mapOf(
                                "protocol" to p.protocol,
                                "serverId" to p.serverId,
                                "endpoint" to p.endpoint,
                                "label" to p.label
                            ))
                        }
                    }
                    
                    val data2 = mapOf(
                        "version" to kf.version.toInt(),
                        "keyId" to kf.keyId,
                        "expiresAt" to Keyfile.expiresAtString(kf),
                        "maxDevices" to kf.maxDevices.toInt(),
                        "clientName" to kf.clientName,
                        "peers" to peersList
                    )
                    
                    pendingResult?.success(data2)
                    pendingResult = null
                    
                    // Чистим временный файл
                    tempFile.delete()
                    
                } catch (e: Exception) {
                    pendingResult?.error("PARSE_ERROR", e.message ?: "Unknown error", e.toString())
                    pendingResult = null
                }
            } else {
                pendingResult?.error("CANCELLED", "User cancelled", null)
                pendingResult = null
            }
        }
    }
}
