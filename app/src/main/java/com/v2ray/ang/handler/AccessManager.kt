package com.v2ray.ang.handler

import android.content.Context
import android.os.Build
import com.v2ray.ang.BuildConfig
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.v2ray.ang.AppConfig
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages user access control via Firebase Realtime Database.
 *
 * Firebase structure:
 * /users/{deviceId}/nickname  - string
 * /users/{deviceId}/status    - "pending" | "approved" | "rejected"
 * /users/{deviceId}/requestedAt - timestamp
 */
object AccessManager {

    enum class AccessStatus { PENDING, APPROVED, REJECTED, UNKNOWN }

    private val db by lazy { FirebaseDatabase.getInstance().reference }
    private const val PREF_NICKNAME = "pref_user_nickname"
    private const val TRAFFIC_UPDATE_INTERVAL_MS = 30_000L // каждые 30 секунд

    private var trafficJob: Job? = null

    fun getNickname(context: Context? = null): String? {
        return MmkvManager.decodeSettingsString(PREF_NICKNAME)
    }

    fun saveNickname(nickname: String) {
        MmkvManager.encodeSettings(PREF_NICKNAME, nickname)
    }

    /**
     * Sends an access request to Firebase.
     * Admin sees it in Firebase console or via Telegram bot webhook.
     */
    fun requestAccess(deviceId: String, nickname: String, onComplete: (Boolean) -> Unit) {
        val userRef = db.child("vpn_users").child(deviceId)
        val data = mapOf(
            "nickname" to nickname,
            "status" to "pending",
            "requestedAt" to System.currentTimeMillis(),
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE,
            "appVersion" to BuildConfig.VERSION_NAME
        )
        userRef.setValue(data)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener {
                Log.e(AppConfig.TAG, "AccessManager: Failed to request access", it)
                onComplete(false)
            }
    }

    /**
     * Listens for status changes in real-time.
     */
    fun listenForStatus(deviceId: String, onStatus: (AccessStatus) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                onStatus(when (status) {
                    "approved" -> AccessStatus.APPROVED
                    "rejected" -> AccessStatus.REJECTED
                    "pending"  -> AccessStatus.PENDING
                    else       -> AccessStatus.UNKNOWN
                })
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(AppConfig.TAG, "AccessManager: DB error", error.toException())
                onStatus(AccessStatus.UNKNOWN)
            }
        }
        db.child("vpn_users").child(deviceId).addValueEventListener(listener)
        return listener
    }

    fun removeListener(deviceId: String, listener: ValueEventListener) {
        db.child("vpn_users").child(deviceId).removeEventListener(listener)
    }

    /**
     * Обновляет lastSeen и online статус в Firebase.
     * Вызывается при включении/выключении VPN.
     */
    fun updateOnlineStatus(deviceId: String, online: Boolean) {
        val updates = mapOf(
            "online" to online,
            "lastSeen" to System.currentTimeMillis()
        )
        db.child("vpn_users").child(deviceId).updateChildren(updates)
            .addOnFailureListener {
                Log.e(AppConfig.TAG, "AccessManager: Failed to update online status", it)
            }

        if (online) {
            startTrafficReporting(deviceId)
        } else {
            stopTrafficReporting()
        }
    }

    /**
     * Запускает периодическую отправку трафика в Firebase каждые 30 секунд.
     */
    private fun startTrafficReporting(deviceId: String) {
        stopTrafficReporting()
        trafficJob = CoroutineScope(Dispatchers.IO).launch {
            val sessionStart = System.currentTimeMillis()
            var sessionUpBytes = 0L
            var sessionDownBytes = 0L
            while (true) {
                delay(TRAFFIC_UPDATE_INTERVAL_MS)
                try {
                    val up = V2RayServiceManager.queryStats("proxy", "uplink")
                    val down = V2RayServiceManager.queryStats("proxy", "downlink")
                    sessionUpBytes = up
                    sessionDownBytes = down
                    val updates = mapOf(
                        "online" to true,
                        "lastSeen" to System.currentTimeMillis(),
                        "sessionStart" to sessionStart,
                        "sessionUpMB" to String.format("%.2f", up / 1024.0 / 1024.0).toDouble(),
                        "sessionDownMB" to String.format("%.2f", down / 1024.0 / 1024.0).toDouble()
                    )
                    db.child("vpn_users").child(deviceId).updateChildren(updates)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "AccessManager: Failed to report traffic", e)
                }
            }
        }
    }

    private fun stopTrafficReporting() {
        trafficJob?.cancel()
        trafficJob = null
    }
}
