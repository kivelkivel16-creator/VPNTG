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
import kotlinx.coroutines.cancel
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
    private var trafficScope: CoroutineScope? = null

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
                val status = snapshot.getValue(String::class.java)
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
        db.child("vpn_users").child(deviceId).child("status").addValueEventListener(listener)
        return listener
    }

    fun removeListener(deviceId: String, listener: ValueEventListener) {
        db.child("vpn_users").child(deviceId).child("status").removeEventListener(listener)
    }

    /**
     * Обновляет lastSeen и online статус в Firebase.
     * Вызывается при включении/выключении VPN.
     */
    fun updateOnlineStatus(deviceId: String, online: Boolean) {
        val updates = if (online) {
            mapOf(
                "online" to true,
                "lastSeen" to System.currentTimeMillis()
            )
        } else {
            // When going offline, explicitly set online=false and clear session data
            mapOf(
                "online" to false,
                "lastSeen" to System.currentTimeMillis(),
                "sessionStart" to null,
                "sessionUpMB" to null,
                "sessionDownMB" to null
            )
        }
        try {
            db.child("vpn_users").child(deviceId).updateChildren(updates)
                .addOnFailureListener {
                    Log.e(AppConfig.TAG, "AccessManager: Failed to update online status", it)
                }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "AccessManager: Firebase not available in this process", e)
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
        val scope = CoroutineScope(Dispatchers.IO)
        trafficScope = scope
        trafficJob = scope.launch {
            val sessionStart = System.currentTimeMillis()
            // Накапливаем суммарный трафик за сессию
            var totalUpBytes = 0L
            var totalDownBytes = 0L
            while (true) {
                delay(TRAFFIC_UPDATE_INTERVAL_MS)
                try {
                    // queryStats возвращает байты с момента последнего вызова (delta)
                    val deltaUp = V2RayServiceManager.queryStats("proxy", "uplink")
                    val deltaDown = V2RayServiceManager.queryStats("proxy", "downlink")
                    totalUpBytes += deltaUp
                    totalDownBytes += deltaDown
                    val updates = mapOf(
                        "online" to true,
                        "lastSeen" to System.currentTimeMillis(),
                        "sessionStart" to sessionStart,
                        "sessionUpMB" to String.format(java.util.Locale.US, "%.2f", totalUpBytes / 1024.0 / 1024.0).toDouble(),
                        "sessionDownMB" to String.format(java.util.Locale.US, "%.2f", totalDownBytes / 1024.0 / 1024.0).toDouble()
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
        trafficScope?.cancel()
        trafficScope = null
    }
}
