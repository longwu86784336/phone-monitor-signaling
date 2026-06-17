package com.phonemonitor.collector

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * OTA 自动更新管理器
 * 流程：检查版本 → 发现新版本 → 弹窗确认 → 下载APK → 触发安装
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val SERVER_BASE = "https://phone-monitor-server.onrender.com"
        private const val VERSION_API = "$SERVER_BASE/api/latest-version"
    }

    private var downloadId: Long = -1

    /**
     * 检查更新（静默检查，有新版本时回调）
     * @param onUpdateAvailable 发现新版本的回调 (versionName, changelog, apkUrl)
     * @param onNoUpdate 无更新的回调
     * @param onError 出错时的回调
     */
    fun checkUpdate(
        onUpdateAvailable: (versionName: String, changelog: String, apkUrl: String) -> Unit,
        onNoUpdate: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        thread {
            try {
                // 获取当前版本号
                val currentVersionCode = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionCode
                val currentVersionName = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName

                Log.d(TAG, "当前版本: $currentVersionName ($currentVersionCode)")

                // 请求服务器最新版本
                val url = URL("$VERSION_API?app=collector")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"

                if (conn.responseCode != 200) {
                    val error = "服务器返回 ${conn.responseCode}"
                    Log.w(TAG, error)
                    (context as? android.app.Activity)?.runOnUiThread { onError(error) }
                    return@thread
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                if (!json.getBoolean("ok")) {
                    val error = json.optString("error", "未知错误")
                    Log.w(TAG, "检查更新失败: $error")
                    (context as? android.app.Activity)?.runOnUiThread { onError(error) }
                    return@thread
                }

                val latestVersionCode = json.getInt("versionCode")
                val latestVersionName = json.getString("versionName")
                val apkUrl = json.getString("apkUrl")
                val changelog = json.optString("changelog", "")

                Log.d(TAG, "最新版本: $latestVersionName ($latestVersionCode), URL=$apkUrl")

                if (latestVersionCode > currentVersionCode) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        onUpdateAvailable(latestVersionName, changelog, apkUrl)
                    }
                } else {
                    Log.d(TAG, "已是最新版本")
                    (context as? android.app.Activity)?.runOnUiThread { onNoUpdate() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新异常: ${e.message}", e)
                (context as? android.app.Activity)?.runOnUiThread {
                    onError(e.message ?: "网络错误")
                }
            }
        }
    }

    /**
     * 下载APK并自动安装
     * @param apkUrl 下载URL
     * @param versionName 版本名（用于文件名）
     * @param onComplete 下载完成回调
     */
    fun downloadAndInstall(apkUrl: String, versionName: String, onComplete: () -> Unit = {}) {
        try {
            val fileName = "PhoneMonitor-collector-$versionName.apk"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

            // 如果文件已存在，直接安装
            if (file.exists()) {
                Log.d(TAG, "APK已存在，直接安装: ${file.absolutePath}")
                installApk(file)
                onComplete()
                return
            }

            // 注册下载完成广播
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        Log.d(TAG, "APK下载完成，开始安装")
                        context.unregisterReceiver(this)
                        installApk(file)
                        onComplete()
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("PhoneMonitor 更新")
                .setDescription("正在下载 $versionName...")
                .setDestinationUri(Uri.fromFile(file))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            Log.d(TAG, "开始下载APK: $apkUrl -> ${file.absolutePath} (downloadId=$downloadId)")
        } catch (e: Exception) {
            Log.e(TAG, "下载APK失败: ${e.message}", e)
        }
    }

    /**
     * 触发APK安装
     */
    private fun installApk(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 使用 FileProvider
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "已触发APK安装: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "触发安装失败: ${e.message}", e)
        }
    }
}
