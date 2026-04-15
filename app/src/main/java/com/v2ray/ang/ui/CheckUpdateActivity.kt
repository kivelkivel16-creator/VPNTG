package com.v2ray.ang.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.V2RayNativeManager
import kotlinx.coroutines.launch

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates(binding.checkPreRelease.isChecked)
        }

        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }
        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates(binding.checkPreRelease.isChecked)
    }

    private fun checkForUpdates(includePreRelease: Boolean) {
        toast(R.string.update_checking_for_update)
        showLoading()

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease)
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            }
            finally {
                hideLoading()
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, result.latestVersion))
            .setMessage(result.releaseNotes)
            .setPositiveButton(R.string.update_now) { _, _ ->
                result.downloadUrl?.let { url -> startApkDownload(url) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startApkDownload(url: String) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(getString(R.string.update_downloading))
            setDescription(getString(R.string.app_name))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@CheckUpdateActivity, null, "update.apk")
            setMimeType("application/vnd.android.package-archive")
        }
        val downloadId = dm.enqueue(request)
        showDownloadProgressDialog(dm, downloadId)
    }

    private fun showDownloadProgressDialog(dm: DownloadManager, downloadId: Long) {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            setPadding(48, 16, 48, 0)
        }
        val tvPercent = TextView(this).apply {
            text = "Подготовка..."
            gravity = Gravity.CENTER
            setPadding(48, 8, 48, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progressBar)
            addView(tvPercent)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Загрузка обновления")
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()

        val handler = Handler(Looper.getMainLooper())
        val checkProgress = object : Runnable {
            override fun run() {
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (!cursor.moveToFirst()) { cursor.close(); return }

                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                cursor.close()

                when (status) {
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            progressBar.isIndeterminate = false
                            progressBar.progress = percent
                            tvPercent.text = "$percent%  (${downloaded / 1024 / 1024} / ${total / 1024 / 1024} МБ)"
                        }
                        handler.postDelayed(this, 500)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        dialog.dismiss()
                        showInstallInstructionDialog(dm, downloadId)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        dialog.dismiss()
                        AlertDialog.Builder(this@CheckUpdateActivity)
                            .setTitle("Ошибка загрузки")
                            .setMessage("Не удалось скачать обновление. Попробуйте позже.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    else -> handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(checkProgress)
    }

    private fun showInstallInstructionDialog(dm: DownloadManager, downloadId: Long) {
        val uri = dm.getUriForDownloadedFile(downloadId)
        val message = "Чтобы установить обновление:\n\n" +
            "1. Нажмите «Установить» ниже\n\n" +
            "Либо, если кнопки «Установить» снизу нет:\n\n" +
            "2. Откройте шторку уведомлений (свайп сверху вниз)\n" +
            "3. Найдите уведомление о загрузке\n" +
            "4. Нажмите на него\n" +
            "5. В открывшемся окне нажмите «Установить»\n\n" +
            "Если Android спросит разрешение — нажмите «Разрешить из этого источника»."

        val dialog = AlertDialog.Builder(this)
            .setTitle("✅ Загрузка завершена")
            .setMessage(message)
            .setCancelable(false)
            .create()

        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Установить") { _, _ ->
            if (uri != null) {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(intent) }
            }
        }
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Позже") { _, _ -> }
        dialog.show()
    }
}