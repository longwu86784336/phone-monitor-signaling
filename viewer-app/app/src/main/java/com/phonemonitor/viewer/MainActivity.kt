package com.phonemonitor.viewer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.PermissionRequest
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        const val VIEWER_URL = "https://phone-monitor-server.onrender.com/viewer/"
        private const val PREFS_NAME = "phonemonitor_device"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_TOKEN = "token"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SERVER_URL = "serverUrl"
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: FrameLayout
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // ★ 原生扫码结果回调
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = result.data?.getStringExtra(ScanActivity.EXTRA_RESULT) ?: ""
            if (scanResult.isNotEmpty()) {
                // 把扫码结果注入 WebView
                val js = "if(window.__onNativeScan) window.__onNativeScan('${scanResult.replace("'", "\\'")}');"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 根布局：WebView + Loading遮罩
        val rootLayout = FrameLayout(this)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE   // ★ 禁用缓存，热更新即时生效
                setSupportZoom(false)
                builtInZoomControls = false
            }

            // ★ 注入 Android 原生存储接口 + 扫码接口
            addJavascriptInterface(DeviceStorageInterface(), "AndroidStorage")
            addJavascriptInterface(NativeScanInterface(), "NativeScan")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // ★ 注入后再等300ms让JS完成执行，确保 onAndroidDeviceReady 已定义
                    view?.postDelayed({ injectSavedDevice() }, 300)
                }
                override fun onReceivedError(view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        hideLoading()
                        Log.e("ViewerApp", "页面加载失败: ${error?.description}")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        try {
                            request.grant(request.resources)
                        } catch (e: Exception) {
                            request.deny()
                        }
                    }
                }
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 100) {
                        // 页面完全加载完成后隐藏loading
                        view?.postDelayed({ hideLoading() }, 500)
                    }
                }
            }
        }

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ★ Loading遮罩层（深色背景 + 提示文字）
        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0a0a0a"))
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val spinner = ProgressBar(context).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(80, 80).also {
                        it.bottomMargin = 24
                    }
                }
                val label = TextView(context).apply {
                    text = "Phone Monitor"
                    textSize = 18f
                    setTextColor(Color.parseColor("#e0e0e0"))
                    gravity = Gravity.CENTER
                }
                val sub = TextView(context).apply {
                    text = "正在连接服务器..."
                    textSize = 13f
                    setTextColor(Color.parseColor("#888888"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = 8 }
                }
                addView(spinner)
                addView(label)
                addView(sub)
            }
            addView(inner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        rootLayout.addView(loadingOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootLayout)
        webView.loadUrl(VIEWER_URL)

        // ★ 检查OTA更新（延迟3秒，避免和页面加载冲突）
        webView.postDelayed({ checkAppUpdate() }, 3000)
    }

    private fun hideLoading() {
        runOnUiThread {
            if (loadingOverlay.visibility == View.VISIBLE) {
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    /** 把 SharedPreferences 中的设备信息注入到页面 */
    private fun injectSavedDevice() {
        val deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        val token = prefs.getString(KEY_TOKEN, "") ?: ""
        val password = prefs.getString(KEY_PASSWORD, "") ?: ""
        val serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""

        if (deviceId.isNotEmpty()) {
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("token", token)
                put("password", password)
                put("serverUrl", serverUrl)
            }
            // ★ 调用页面的 onAndroidDeviceReady 回调（与 HTML 保持一致）
            val jsonStr = json.toString().replace("\\", "\\\\").replace("'", "\\'")
            val js = """
                window.__savedDevice = $json;
                if (window.onAndroidDeviceReady) {
                    window.onAndroidDeviceReady('$jsonStr');
                }
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** JavaScript 桥接：原生扫码 */
    inner class NativeScanInterface {
        @JavascriptInterface
        fun startScan() {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, ScanActivity::class.java)
                    scanLauncher.launch(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法启动扫码: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** JavaScript 桥接：让网页把数据存到 Android 原生 SharedPreferences */
    inner class DeviceStorageInterface {
        @JavascriptInterface
        fun saveDevice(jsonStr: String) {
            try {
                val json = JSONObject(jsonStr)
                prefs.edit().apply {
                    putString(KEY_DEVICE_ID, json.optString("deviceId", ""))
                    putString(KEY_TOKEN, json.optString("token", ""))
                    putString(KEY_PASSWORD, json.optString("password", ""))
                    putString(KEY_SERVER_URL, json.optString("serverUrl", ""))
                    apply()
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun clearDevice() {
            prefs.edit().clear().apply()
        }

        @JavascriptInterface
        fun getSavedDevice(): String {
            val deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: ""
            val token = prefs.getString(KEY_TOKEN, "") ?: ""
            val password = prefs.getString(KEY_PASSWORD, "") ?: ""
            val serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""
            if (deviceId.isEmpty()) return ""

            return JSONObject().apply {
                put("deviceId", deviceId)
                put("token", token)
                put("password", password)
                put("serverUrl", serverUrl)
            }.toString()
        }
    }

    /** ★ OTA自动更新检查 */
    private fun checkAppUpdate() {
        val updateManager = UpdateManager(this)
        updateManager.checkUpdate(
            onUpdateAvailable = { versionName, changelog, apkUrl ->
                // 将 changelog 的 \n 转为 Dialog 可显示的格式
                val changeText = if (changelog.isNotEmpty()) {
                    "\n更新内容：\n" + changelog.replace("\\n", "\n")
                } else {
                    ""
                }
                AlertDialog.Builder(this)
                    .setTitle("发现新版本 v$versionName")
                    .setMessage("PhoneMonitor 查看端 $versionName 已发布，是否立即更新？$changeText")
                    .setPositiveButton("立即更新") { _, _ ->
                        Toast.makeText(this, "正在后台下载更新...", Toast.LENGTH_SHORT).show()
                        updateManager.downloadAndInstall(apkUrl, versionName)
                    }
                    .setNegativeButton("稍后再说", null)
                    .setCancelable(false)
                    .show()
            },
            onNoUpdate = {
                Log.d("UpdateManager", "查看端已是最新版本")
            },
            onError = { error ->
                Log.w("UpdateManager", "查看端检查更新失败: $error")
            }
        )
    }
}
