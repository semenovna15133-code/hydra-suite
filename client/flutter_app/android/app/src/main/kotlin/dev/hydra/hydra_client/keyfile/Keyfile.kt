package keyfile

import java.io.File

// Kotlin-замена Go keyfile парсера.
// Интерфейс максимально близок к Go-версии для минимизации изменений.

object Keyfile {
    fun initRuntime() {
        // Заглушка для совместимости с Go-кодом
    }
    
    fun parse(filename: String): KeyFile {
        val file = File(filename)
        val lines = file.readLines()
        val kf = KeyFile()
        var currentPeer: Peer? = null
        
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            
            // Section header: [Section] или [Peer.<proto>.<server_id>]
            if (line.startsWith("[") && line.endsWith("]")) {
                val section = line.substring(1, line.length - 1)
                val parts = section.split(".", limit = 3)
                
                when {
                    parts.size == 1 && parts[0].equals("Hydra", ignoreCase = true) -> {
                        currentPeer = null
                    }
                    parts.size == 3 && parts[0].equals("Peer", ignoreCase = true) -> {
                        currentPeer = Peer()
                        currentPeer.protocol = parts[1]
                        currentPeer.serverId = parts[2]
                        kf.peers.add(currentPeer)
                    }
                }
                continue
            }
            
            // Key = Value
            val eqIdx = line.indexOf('=')
            if (eqIdx < 0) continue
            
            val key = line.substring(0, eqIdx).trim()
            var value = line.substring(eqIdx + 1).trim()
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length - 1)
            }
            
            if (currentPeer == null) {
                when (key) {
                    "Version" -> kf.version = value.toIntOrNull() ?: 0
                    "KeyId" -> kf.keyId = value
                    "ExpiresAt" -> kf.expiresAt = value
                    "MaxDevices" -> kf.maxDevices = value.toIntOrNull() ?: 0
                    "ClientName" -> kf.clientName = value
                }
            } else {
                when (key) {
                    "Endpoint" -> currentPeer.endpoint = value
                    "Label" -> currentPeer.label = value
                    "PublicKey" -> currentPeer.publicKey = value
                    "PrivateKey" -> currentPeer.privateKey = value
                    "Address" -> currentPeer.address = value
                    "DNS" -> currentPeer.dns = value
                    "Jc" -> currentPeer.jc = value
                    "Jmin" -> currentPeer.jmin = value
                    "Jmax" -> currentPeer.jmax = value
                    "S1" -> currentPeer.s1 = value
                    "S2" -> currentPeer.s2 = value
                    "S3" -> currentPeer.s3 = value
                    "S4" -> currentPeer.s4 = value
                    "HeaderProtectionKey" -> currentPeer.headerProtectionKey = value
                    "Key" -> currentPeer.key = value
                    "Password" -> currentPeer.password = value
                }
            }
        }
        
        require(kf.version > 0) { "Missing or invalid [Hydra] section" }
        require(kf.peers.isNotEmpty()) { "No [Peer.*] sections found" }
        
        return kf
    }
    
    fun peerAt(kf: KeyFile, index: Long): Peer? {
        return kf.peers.getOrNull(index.toInt())
    }
    
    fun peerCount(kf: KeyFile): Long {
        return kf.peers.size.toLong()
    }
    
    fun expiresAtString(kf: KeyFile): String {
        return kf.expiresAt
    }
}

data class KeyFile(
    var version: Int = 0,
    var keyId: String = "",
    var expiresAt: String = "",
    var maxDevices: Int = 0,
    var clientName: String = "",
    val peers: MutableList<Peer> = mutableListOf()
)

data class Peer(
    var protocol: String = "",
    var serverId: String = "",
    var endpoint: String = "",
    var label: String = "",
    var publicKey: String = "",
    var privateKey: String = "",
    var address: String = "",
    var dns: String = "",
    var jc: String = "",
    var jmin: String = "",
    var jmax: String = "",
    var s1: String = "",
    var s2: String = "",
    var s3: String = "",
    var s4: String = "",
    var headerProtectionKey: String = "",
    var key: String = "",
    var password: String = ""
)
