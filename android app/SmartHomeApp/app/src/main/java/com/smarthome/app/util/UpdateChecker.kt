package com.smarthome.app.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.smarthome.app.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/xiao-dgLI/Smart-Home-Project/releases/latest"
    private const val PREF_NAME = "update_check_prefs"
    private const val KEY_SUPPRESS_DATE = "suppress_until_date"
    private const val KEY_LATEST_VERSION = "latest_version"

    var hasUpdate: Boolean = false

    var latestVersionInfo: String = ""

    var onUpdateChecked: ((Boolean) -> Unit)? = null

    fun checkOnStartup(context: Context, currentVersion: String) {
        if (isSuppressedToday(context)) return

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string() ?: return
                    try {
                        val json = JsonParser.parseString(body).asJsonObject
                        val tagName = json.get("tag_name")?.asString ?: return
                        val latestVersion = tagName.removePrefix("v")
                        val releaseName = json.get("name")?.asString ?: tagName
                        val description = json.get("body")?.asString ?: ""
                        val htmlUrl = json.get("html_url")?.asString ?: ""

                        var apkUrl = ""
                        val assets = json.getAsJsonArray("assets")
                        if (assets != null) {
                            for (asset in assets) {
                                val name = asset.asJsonObject.get("name")?.asString ?: ""
                                if (name.endsWith(".apk")) {
                                    apkUrl = asset.asJsonObject.get("browser_download_url")?.asString ?: ""
                                    break
                                }
                            }
                        }

                        if (isVersionNewer(latestVersion, currentVersion)) {
                            hasUpdate = true
                            latestVersionInfo = latestVersion
                            val activity = findActivity(context) ?: return
                            activity.runOnUiThread {
                                onUpdateChecked?.invoke(true)
                                showUpdateDialog(
                                    activity, latestVersion, releaseName,
                                    description, htmlUrl, apkUrl
                                )
                            }
                        } else {
                            hasUpdate = false
                            val activity = findActivity(context) ?: return
                            activity.runOnUiThread {
                                onUpdateChecked?.invoke(false)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    private fun showUpdateDialog(
        context: Context,
        version: String,
        title: String,
        content: String,
        htmlUrl: String,
        apkUrl: String
    ) {
        val dialogView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_update_check, null)

        val tvVersion = dialogView.findViewById<TextView>(R.id.tvUpdateVersion)
        val tvContent = dialogView.findViewById<TextView>(R.id.tvUpdateContent)
        val cbNotRemind = dialogView.findViewById<CheckBox>(R.id.cbNotRemindToday)
        val progressDownload = dialogView.findViewById<ProgressBar>(R.id.progressDownload)
        val tvDownloadStatus = dialogView.findViewById<TextView>(R.id.tvDownloadStatus)

        tvVersion.text = "v${version}"
        tvContent.text = if (content.isNotBlank()) content else "暂无更新说明"

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "更新") { _, _ ->
            if (apkUrl.isNotBlank()) {
                downloadAndInstall(context, apkUrl, version, progressDownload, tvDownloadStatus, dialog, htmlUrl)
            } else {
                openUrl(context, htmlUrl)
                dialog.dismiss()
            }
        }

        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "我知道了") { _, _ ->
            if (cbNotRemind.isChecked) {
                suppressToday(context)
            }
            dialog.dismiss()
        }

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (apkUrl.isNotBlank()) {
                downloadAndInstall(context, apkUrl, version, progressDownload, tvDownloadStatus, dialog, htmlUrl)
            } else {
                openUrl(context, htmlUrl)
                dialog.dismiss()
            }
        }
    }

    private fun downloadAndInstall(
        context: Context,
        url: String,
        version: String,
        progressBar: ProgressBar,
        tvStatus: TextView,
        dialog: AlertDialog,
        htmlUrl: String
    ) {
        progressBar.visibility = android.view.View.VISIBLE
        tvStatus.visibility = android.view.View.VISIBLE
        tvStatus.text = "正在下载..."

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

        val fileName = "SmartHome_v${version}.apk"
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val activity = findActivity(context) ?: return@onFailure
                activity.runOnUiThread {
                    tvStatus.text = "下载失败: ${e.message}"
                    progressBar.visibility = android.view.View.GONE
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val activity = findActivity(context) ?: return@onResponse
                    activity.runOnUiThread {
                        tvStatus.text = "下载失败 (${response.code})"
                        progressBar.visibility = android.view.View.GONE
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                    return
                }

                response.use { resp ->
                    val body = resp.body ?: return
                    val contentLength = body.contentLength()

                    file.outputStream().use { output ->
                        val input = body.byteStream()
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                val progress = (totalRead * 100 / contentLength).toInt()
                                val activity = findActivity(context)
                                activity?.runOnUiThread {
                                    progressBar.progress = progress
                                    tvStatus.text = "下载中... $progress%"
                                }
                            }
                        }
                    }

                    val activity = findActivity(context) ?: return@use
                    activity.runOnUiThread {
                        progressBar.progress = 100
                        tvStatus.text = "下载完成"
                        dialog.dismiss()
                        showInstallPrompt(context, file)
                    }
                }
            }
        })
    }

    private fun showInstallPrompt(context: Context, apkFile: File) {
        AlertDialog.Builder(context)
            .setTitle("下载完成")
            .setMessage("安装包已保存到:\n${apkFile.absolutePath}\n\n是否立即安装?")
            .setPositiveButton("立即安装") { _, _ ->
                installApk(context, apkFile)
            }
            .setNegativeButton("稍后") { _, _ -> }
            .setCancelable(true)
            .show()
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openUrl(context: Context, url: String) {
        if (url.isNotBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun isVersionNewer(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val curParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(newParts.size, curParts.size)
        for (i in 0 until maxLen) {
            val n = newParts.getOrElse(i) { 0 }
            val c = curParts.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }

    private fun isSuppressedToday(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val suppressDate = prefs.getString(KEY_SUPPRESS_DATE, "") ?: return false
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        return suppressDate == today
    }

    private fun suppressToday(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        prefs.edit().putString(KEY_SUPPRESS_DATE, today).apply()
    }

    private fun findActivity(context: Context): android.app.Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
