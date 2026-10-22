package dev.hydra.hydra_client.tunnel

import android.util.Log

object DevLog {
    const val ENABLED = true  // К релизу: false
    const val TAG = "HydraDev"
    
    fun d(tag: String, msg: String) {
        if (ENABLED) Log.d(TAG, "[$tag] $msg")
    }
    fun i(tag: String, msg: String) {
        if (ENABLED) Log.i(TAG, "[$tag] $msg")
    }
    fun w(tag: String, msg: String) {
        if (ENABLED) Log.w(TAG, "[$tag] $msg")
    }
    fun e(tag: String, msg: String) {
        if (ENABLED) Log.e(TAG, "[$tag] $msg")
    }
}
