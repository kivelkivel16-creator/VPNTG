package com.v2ray.ang

import android.content.Context
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.SettingsManager

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        try {
            // Initialize Firebase first — must happen in every process
            // (VPN service runs in :RunSoLibV2RayDaemon process)
            FirebaseApp.initializeApp(this)

            MMKV.initialize(this)

            // Initialize WorkManager with the custom configuration
            WorkManager.initialize(this, workManagerConfiguration)

            // Ensure critical preference defaults are present in MMKV early
            SettingsManager.initApp(this)
            SettingsManager.setNightMode()

            es.dmoral.toasty.Toasty.Config.getInstance()
                .setGravity(android.view.Gravity.BOTTOM, 0, 300)
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("AngApplication", "Initialization error: ${e.message}", e)
            throw e
        }
    }
}
