package tunnel

// Заглушка для совместимости с существующим кодом.
// AWG туннель временно отключен: gomobile не компилируется.
class Tunnel_ {
    fun start(fd: Int, cfg: Config) {
        throw UnsupportedOperationException("AWG tunnel temporarily disabled: gomobile build broken. Use AIVPN or WDTT instead.")
    }
    
    fun stop() {
        throw UnsupportedOperationException("AWG tunnel temporarily disabled")
    }
    
    fun restart(): Boolean {
        throw UnsupportedOperationException("AWG tunnel temporarily disabled")
    }
    
    fun stats(): String = "{}"
    
    fun lastHandshakeMs(): Long = -1
}

object Tunnel {
    fun new_(cfg: Config, protector: SocketProtector): Tunnel_ {
        throw UnsupportedOperationException("AWG tunnel temporarily disabled: gomobile build broken")
    }
    
    fun probeNetwork(endpoint: String, timeoutNs: Long): NetworkProbeResult {
        // Простая заглушка: всегда возвращаем UDP OK
        return NetworkProbeResult(
            udpOk = true,
            tcpOk = true,
            vkApiOk = true,
            dpiDetected = false,
            latencyMs = 50
        )
    }
}

data class Config(
    var privateKey: String = "",
    var peerPublicKey: String = "",
    var endpoint: String = "",
    var address: String = "",
    var dns: String = "",
    var mtu: Int = 1420,
    var jc: Long = 0,
    var jmin: Long = 0,
    var jmax: Long = 0,
    var s1: Long = 0,
    var s2: Long = 0,
    var s3: Long = 0,
    var s4: Long = 0,
    var headerProtectionKey: String = ""
)

interface SocketProtector {
    fun protect(fd: Int): Boolean
}

data class NetworkProbeResult(
    val udpOk: Boolean = false,
    val tcpOk: Boolean = false,
    val vkApiOk: Boolean = false,
    val dpiDetected: Boolean = false,
    val latencyMs: Long = 0
)
