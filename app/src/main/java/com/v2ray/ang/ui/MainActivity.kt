package com.v2ray.ang.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.ValueEventListener
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityTelegramVpnBinding
import com.v2ray.ang.handler.AccessManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.TelegramVpnConfig
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTelegramVpnBinding
    private var isRunning = false
    private var statusListener: ValueEventListener? = null
    private var currentAccessStatus = AccessManager.AccessStatus.UNKNOWN
    private var updateRequired = false
    private var updateDownloadUrl: String? = null

    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private val requestVpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startV2Ray()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — продолжаем в любом случае */ }

    private val vpnServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val key = intent?.getIntExtra("key", 0) ?: 0
            when (key) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    isRunning = true
                    updateVpnUi()
                }
                AppConfig.MSG_STATE_NOT_RUNNING,
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning = false
                    updateVpnUi()
                }
                else -> updateVpnUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramVpnBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            requestNotificationPermissionIfNeeded()
            TelegramVpnConfig.initializeConfig(this)
            setupClickListeners()
            registerBroadcastReceiver()
            checkNicknameAndAccess()
            checkForceUpdate()
        } catch (e: Exception) {
            Log.e("TelegramVPN", "Init error: ${e.message}", e)
            showErrorState("Ошибка инициализации")
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ── Force update check ────────────────────────────────────────────────────

    private fun checkForceUpdate() {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance().reference
        db.child("app_config").get()
            .addOnSuccessListener { snapshot ->
                val minVersion = snapshot.child("min_version_code").getValue(Long::class.java)
                Log.d("TelegramVPN", "checkForceUpdate: minVersion=$minVersion")
                if (minVersion == null) return@addOnSuccessListener
                val downloadUrl = snapshot.child("download_url").getValue(String::class.java)
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
                Log.d("TelegramVPN", "checkForceUpdate: currentVersion=$currentVersion minVersion=$minVersion")
                if (currentVersion < minVersion) {
                    updateRequired = true
                    updateDownloadUrl = downloadUrl
                    runOnUiThread {
                        binding.btnConnect.isEnabled = false
                        if (isRunning) stopV2Ray()
                        showUpdateDialog(downloadUrl)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("TelegramVPN", "Force update check failed: ${e.message}")
            }
    }

    private fun showUpdateDialog(downloadUrl: String?) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Требуется обновление")
            .setMessage("Эта версия устарела. Обновите приложение чтобы продолжить использование.")
            .setCancelable(false)
        if (!downloadUrl.isNullOrBlank()) {
            builder.setPositiveButton("Скачать обновление") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
        } else {
            builder.setPositiveButton("Понятно", null)
        }
        builder.show()
    }

    override fun onResume() {
        super.onResume()
        if (updateRequired) {
            binding.btnConnect.isEnabled = false
            showUpdateDialog(updateDownloadUrl)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (updateRequired) return
        super.onBackPressed()
    }

    // ── Access / nickname ─────────────────────────────────────────────────────

    private fun checkNicknameAndAccess() {
        val savedNickname = AccessManager.getNickname(this)
        if (savedNickname.isNullOrBlank()) {
            showNicknameDialog()
        } else {
            binding.tvNickname.text = savedNickname
            startListeningForAccess()
        }
    }

    private fun showNicknameDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_nickname, null)
        val etNickname = dialogView.findViewById<TextInputEditText>(R.id.etNickname)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Отправить запрос") { _, _ ->
                val nickname = etNickname.text?.toString()?.trim()
                if (!nickname.isNullOrBlank()) {
                    AccessManager.saveNickname(nickname)
                    binding.tvNickname.text = nickname
                    binding.tvAccessStatus.text = "Отправка запроса..."
                    AccessManager.requestAccess(deviceId, nickname) { success ->
                        runOnUiThread {
                            if (success) {
                                binding.tvAccessStatus.text = "Ожидание одобрения"
                                startListeningForAccess()
                            } else {
                                binding.tvAccessStatus.text = "Ошибка отправки"
                            }
                        }
                    }
                } else {
                    showNicknameDialog()
                }
            }
            .show()
    }

    private fun startListeningForAccess() {
        statusListener = AccessManager.listenForStatus(deviceId) { status ->
            runOnUiThread {
                currentAccessStatus = status
                updateAccessUi(status)
            }
        }
    }

    private fun updateAccessUi(status: AccessManager.AccessStatus) {
        when (status) {
            AccessManager.AccessStatus.APPROVED -> {
                binding.tvAccessStatus.text = "Доступ разрешён"
                binding.tvAccessStatus.setTextColor(ContextCompat.getColor(this, R.color.colorPing))
                binding.btnConnect.isEnabled = !updateRequired
                hideErrorState()
            }
            AccessManager.AccessStatus.REJECTED -> {
                binding.tvAccessStatus.text = "Доступ отклонён"
                binding.tvAccessStatus.setTextColor(ContextCompat.getColor(this, R.color.md_theme_error))
                binding.btnConnect.isEnabled = false
                if (isRunning) stopV2Ray()
                showErrorState("Доступ отклонён\nОбратитесь к администратору")
            }
            AccessManager.AccessStatus.PENDING -> {
                binding.tvAccessStatus.text = "Ожидание одобрения"
                binding.tvAccessStatus.setTextColor(ContextCompat.getColor(this, R.color.md_theme_secondary))
                binding.btnConnect.isEnabled = false
                hideErrorState()
            }
            AccessManager.AccessStatus.UNKNOWN -> {
                binding.tvAccessStatus.text = "Нет связи с сервером"
                binding.tvAccessStatus.setTextColor(ContextCompat.getColor(this, R.color.color_fab_inactive))
                binding.btnConnect.isEnabled = false
                if (!hasNetwork()) {
                    showErrorState("Нет сети\nПроверь подключение к интернету")
                } else {
                    showErrorState("Сервер недоступен\nПопробуй позже")
                }
            }
        }
    }

    // ── Error / empty states ──────────────────────────────────────────────────

    private fun showErrorState(message: String) {
        binding.cardErrorState.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
        val emoji = when {
            message.contains("сети", ignoreCase = true) -> "📡"
            message.contains("отклонён", ignoreCase = true) -> "🚫"
            else -> "⚠️"
        }
        binding.tvErrorEmoji.text = emoji
    }

    private fun hideErrorState() {
        binding.cardErrorState.visibility = View.GONE
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnConnect.isEnabled = false
        binding.btnConnect.setOnClickListener {
            if (currentAccessStatus != AccessManager.AccessStatus.APPROVED) return@setOnClickListener
            if (isRunning) stopV2Ray() else startV2RayWithPermission()
        }
        binding.tvStatusLabel.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        }
        binding.btnRetry.setOnClickListener {
            hideErrorState()
            checkNicknameAndAccess()
        }
        binding.btnDiagnostics.setOnClickListener {
            runDiagnostics()
        }
    }

    // ── VPN control ───────────────────────────────────────────────────────────

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        filter.addAction(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(this, vpnServiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun startV2RayWithPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) requestVpnPermission.launch(intent) else startV2Ray()
    }

    private fun startV2Ray() {
        try {
            V2RayServiceManager.startVService(this, TelegramVpnConfig.SERVER_GUID)
        } catch (e: Exception) {
            Log.e("TelegramVPN", "Start error: ${e.message}", e)
        }
    }

    private fun stopV2Ray() {
        V2RayServiceManager.stopVService(this)
    }

    // ── VPN UI state ──────────────────────────────────────────────────────────

    private fun updateVpnUi() {
        if (isRunning) {
            binding.btnConnect.text = "ON"
            binding.btnConnect.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.color_fab_active)
            binding.tvStatus.text = "ВКЛЮЧЕНО"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.colorPing))
            binding.tvStatusLabel.text = "Telegram защищён • Нажми для настроек VPN"
            binding.viewStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.colorPing)
            animateGlow(true)
        } else {
            binding.btnConnect.text = "OFF"
            binding.btnConnect.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.color_fab_inactive)
            binding.tvStatus.text = "ОТКЛЮЧЕНО"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.tvStatusLabel.text = "Нет соединения • Нажми для настроек VPN"
            binding.viewStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.color_fab_inactive)
            animateGlow(false)
        }
    }

    private fun animateGlow(show: Boolean) {
        ObjectAnimator.ofFloat(binding.viewGlow, "alpha", if (show) 1f else 0f).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private fun runDiagnostics() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Диагностика")
            .setMessage("Проверяю…")
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { buildDiagnosticsReport() }
            dialog.setMessage(result)
            dialog.setCancelable(true)
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK") { d, _ -> d.dismiss() }
        }
    }

    private fun buildDiagnosticsReport(): String {
        val sb = StringBuilder()

        // Network
        val net = hasNetwork()
        sb.appendLine("🌐 Сеть: ${if (net) "есть" else "нет"}")

        // Ping to server
        val pingMs = pingHost(TelegramVpnConfig.SERVER_ADDRESS, TelegramVpnConfig.SERVER_PORT)
        sb.appendLine("📡 Сервер (${TelegramVpnConfig.SERVER_ADDRESS}:${TelegramVpnConfig.SERVER_PORT}): ${if (pingMs >= 0) "${pingMs}ms" else "недоступен"}")

        // VPN status
        sb.appendLine("🔒 VPN: ${if (isRunning) "включён" else "выключен"}")

        // Xray version
        val xrayVer = runCatching { V2RayNativeManager.getLibVersion() }.getOrNull() ?: "неизвестно"
        sb.appendLine("⚙️ Xray: $xrayVer")

        // Firebase reachable — проверяем реальным запросом
        val fbOk = try {
            com.google.firebase.database.FirebaseDatabase.getInstance().reference
                .child("app_config").get()
            true
        } catch (e: Exception) { false }
        sb.appendLine("🔥 Firebase: ${if (fbOk) "доступен" else "недоступен"}")

        // MTU
        sb.appendLine("📦 MTU: ${TelegramVpnConfig.VPN_TUN_MTU}")

        return sb.toString().trimEnd()
    }

    /** Returns latency in ms, or -1 if unreachable */
    private fun pingHost(host: String, port: Int, timeoutMs: Int = 3000): Long {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Запрашиваем текущий статус у сервиса
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        updateVpnUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(vpnServiceReceiver) } catch (e: Exception) { }
        statusListener?.let { AccessManager.removeListener(deviceId, it) }
    }

    fun restartV2Ray() {
        if (isRunning) {
            stopV2Ray()
            startV2Ray()
        }
    }

    fun importConfigViaSub() {
        // Not used in simplified version
    }
}
