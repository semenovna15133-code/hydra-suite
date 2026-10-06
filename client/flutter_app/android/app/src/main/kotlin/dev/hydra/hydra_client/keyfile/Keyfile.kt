package keyfile

import java.io.File

// Kotlin-замена Go keyfile парсера.
// Интерфейс максимально близок к Go-версии для минимизации изменений.


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
