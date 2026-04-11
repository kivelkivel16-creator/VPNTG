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
}
