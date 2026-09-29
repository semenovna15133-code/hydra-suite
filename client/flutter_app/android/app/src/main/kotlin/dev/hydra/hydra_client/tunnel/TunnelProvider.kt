package dev.hydra.hydra_client.tunnel

import tunnel.NetworkProbeResult

interface TunnelProvider {
    val name: String
    val serverId: String
    val label: String
    
    fun score(probe: NetworkProbeResult?): Int
    fun connect(keyId: String): Boolean
    fun disconnect()
    fun isRunning(): Boolean
}
