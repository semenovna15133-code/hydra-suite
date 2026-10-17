package dev.hydra.hydra_client.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import dev.hydra.hydra_client.HydraVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkChangeReceiver"
        var lastKeyId: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (HydraVpnService.userInitiatedStop) {
            Log.i(TAG, "User-initiated stop — ignoring network change")
            return
        }
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        val keyId = lastKeyId ?: return
        Log.i(TAG, "Network changed, re-evaluating protocols for key=$keyId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Даём сети стабилизироваться
                kotlinx.coroutines.delay(1500)
                SmartConnectManager.disconnect()
                SmartConnectManager.autoConnect(context, keyId)
            } catch (e: Exception) {
                Log.e(TAG, "Re-evaluate failed", e)
            }
        }
    }
}
