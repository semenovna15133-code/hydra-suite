package dev.hydra.hydra_client.tunnel

import android.content.Context
import android.util.Log
import java.io.DataOutputStream

/**
 * KillSwitch: блокирует весь трафик кроме SOCKS5 proxy через iptables.
 * Предотвращает утечки если SOCKS5 proxy упадёт.
 */
object KillSwitch {
    private const val TAG = "KillSwitch"

    /**
     * Включает kill switch: блокирует весь UDP/TCP кроме 127.0.0.1
     */
    fun enable(context: Context, tunFd: Int): Boolean {
        val commands = listOf(
            // Разрешаем loopback
            "iptables -I OUTPUT -o lo -j ACCEPT",
            // Разрешаем трафик через TUN интерфейс
            "iptables -I OUTPUT -o tun0 -j ACCEPT",
            // Блокируем весь остальной исходящий трафик
            "iptables -I OUTPUT -m state --state NEW -j DROP"
        )

        return executeCommands(commands)
    }

    /**
     * Выключает kill switch
     */
    fun disable(): Boolean {
        val commands = listOf(
            "iptables -D OUTPUT -o lo -j ACCEPT",
            "iptables -D OUTPUT -o tun0 -j ACCEPT",
            "iptables -D OUTPUT -m state --state NEW -j DROP"
        )

        return executeCommands(commands)
    }

    private fun executeCommands(commands: List<String>): Boolean {
        try {
            val su = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(su.outputStream)

            for (cmd in commands) {
                os.writeBytes("$cmd\n")
                os.flush()
            }

            os.writeBytes("exit\n")
            os.flush()

            val exitCode = su.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "Kill switch commands executed")
                return true
            } else {
                Log.e(TAG, "Kill switch commands failed: exit code $exitCode")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kill switch error: ${e.message}")
            return false
        }
    }
}
