package dev.hydra.hydra_client.tunnel

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * SOCKS5-мост: читает IP-пакеты из TUN fd, парсит TCP/UDP,
 * форвардит через SOCKS5 proxy на 127.0.0.1:port
 */
class SocksBridge(
    private val tunFd: ParcelFileDescriptor,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int
) {
    companion object {
        private const val TAG = "SocksBridge"
        private const val MTU = 1500
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeConnections = ConcurrentHashMap<String, Socket>()
    private var running = false

    private val input = FileInputStream(tunFd.fileDescriptor)
    private val output = FileOutputStream(tunFd.fileDescriptor)

    fun start() {
        running = true
        Log.i(TAG, "Starting SOCKS5 bridge, upstream=$socksHost:$socksPort")
        scope.launch { readFromTun() }
    }

    fun stop() {
        running = false
        activeConnections.values.forEach { 
            try { it.close() } catch (_: Exception) {} 
        }
        activeConnections.clear()
        scope.cancel()
        Log.i(TAG, "SOCKS5 bridge stopped")
    }

    private suspend fun readFromTun() {
        val buf = ByteArray(MTU)
        while (running) {
            try {
                val n = input.read(buf)
                if (n <= 0) continue
                if (n < 20) continue  // минимальный IP заголовок

                // Парсим IPv4
                val version = (buf[0].toInt() shr 4) and 0x0F
                if (version != 4) continue

                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (n < ihl) continue

                val protocol = buf[9].toInt() and 0xFF
                val srcIP = byteArrayOf(buf[12], buf[13], buf[14], buf[15])
                val dstIP = byteArrayOf(buf[16], buf[17], buf[18], buf[19])

                when (protocol) {
                    6 -> handleTCP(buf, ihl, n, srcIP, dstIP)   // TCP
                    17 -> handleUDP(buf, ihl, n, srcIP, dstIP)  // UDP
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "TUN read error: ${e.message}")
                break
            }
        }
    }

    private fun handleTCP(buf: ByteArray, ihl: Int, n: Int, srcIP: ByteArray, dstIP: ByteArray) {
        val tcpData = buf.copyOfRange(ihl, n)
        if (tcpData.size < 20) return

        val srcPort = ((tcpData[0].toInt() and 0xFF) shl 8) or (tcpData[1].toInt() and 0xFF)
        val dstPort = ((tcpData[2].toInt() and 0xFF) shl 8) or (tcpData[3].toInt() and 0xFF)
        val flags = tcpData[13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0 && (flags and 0x10) == 0

        if (!isSyn) return  // обрабатываем только новые соединения

        val connKey = "${ipToString(srcIP)}:$srcPort-${ipToString(dstIP)}:$dstPort"
        Log.i(TAG, "TCP SYN: $connKey")

        scope.launch {
            try {
                val sock = connectViaSocks5(ipToString(dstIP), dstPort)
                activeConnections[connKey] = sock

                // TODO: отправить SYN-ACK обратно в TUN и форвардить данные
                // Сейчас только логируем успешное подключение
                Log.i(TAG, "Connected to ${ipToString(dstIP)}:$dstPort via SOCKS5")

                // Считаем трафик
                val sent = sock.getOutputStream().let { 0L } // TODO: реальный подсчёт
                val recv = sock.getInputStream().let { 0L }
                
                // Закрываем (нужно реализовать полноценный forwarder)
                sock.close()
                activeConnections.remove(connKey)
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 connect failed: ${e.message}")
            }
        }
    }

    private fun handleUDP(buf: ByteArray, ihl: Int, n: Int, srcIP: ByteArray, dstIP: ByteArray) {
        val udpData = buf.copyOfRange(ihl, n)
        if (udpData.size < 8) return

        val srcPort = ((udpData[0].toInt() and 0xFF) shl 8) or (udpData[1].toInt() and 0xFF)
        val dstPort = ((udpData[2].toInt() and 0xFF) shl 8) or (udpData[3].toInt() and 0xFF)
        val payload = udpData.copyOfRange(8, udpData.size)

        // Особая обработка DNS (порт 53)
        if (dstPort == 53) {
            Log.i(TAG, "DNS query to ${ipToString(dstIP)}, ${payload.size} bytes")
            // TODO: реализовать DNS через SOCKS5 UDP ASSOCIATE
        } else {
            Log.d(TAG, "UDP ${ipToString(srcIP)}:$srcPort -> ${ipToString(dstIP)}:$dstPort")
        }
    }

    private fun connectViaSocks5(host: String, port: Int): Socket {
        val sock = Socket()
        sock.connect(InetSocketAddress(socksHost, socksPort), 5000)
        sock.soTimeout = 30000

        val out = sock.getOutputStream()
        val inp = sock.getInputStream()

        // SOCKS5 handshake: no auth
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()

        val resp = ByteArray(2)
        inp.read(resp)
        if (resp[0] != 0x05.toByte() || resp[1] != 0x00.toByte()) {
            sock.close()
            throw IOException("SOCKS5 auth failed")
        }

        // CONNECT request
        val hostBytes = host.toByteArray()
        val req = ByteBuffer.allocate(7 + hostBytes.size)
        req.put(0x05)           // VER
        req.put(0x01)           // CMD = CONNECT
        req.put(0x00)           // RSV
        req.put(0x03)           // ATYP = DOMAINNAME
        req.put(hostBytes.size.toByte())
        req.put(hostBytes)
        req.putShort(port.toShort())

        out.write(req.array())
        out.flush()

        val connResp = ByteArray(10)
        val read = inp.read(connResp)
        if (read < 2 || connResp[1] != 0x00.toByte()) {
            sock.close()
            throw IOException("SOCKS5 connect failed: ${connResp[1]}")
        }

        return sock
    }

    private fun ipToString(ip: ByteArray): String {
        return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }
}
