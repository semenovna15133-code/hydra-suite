package dev.hydra.hydra_client.tunnel

import android.content.BroadcastReceiver
import dev.hydra.hydra_client.HydraVpnService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log

class NetworkChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (HydraVpnService.userInitiatedStop) {
            Log.i(TAG, "User-initiated stop — ignoring network change")
            return
        }
        if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            Log.i(TAG, "Network changed, triggering reconnect")
        }
    }
}
