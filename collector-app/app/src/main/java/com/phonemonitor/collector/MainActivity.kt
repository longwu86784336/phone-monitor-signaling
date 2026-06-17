package com.phonemonitor.collector

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.MessageDigest
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhoneMonitor"
        private const val PREFS_NAME = "monitor_prefs"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_PASSWORD_PLAIN = "password_plain"   // ★ 明文密码（记住用）
        private const val KEY_AUTO_START = "auto_start"           // ★ 是否自动启动
        private const val KEY_DEVICE_ID = "device_id"
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_OVERLAY = 200
        const val DEFAULT_SERVER = "phone-monitor-server.onrender.com"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var etPassword: EditText
    private lateinit var etServer: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var ivQrCode: ImageView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var cbRemember: CheckBox   // ★ 记住密码勾选框
    private lateinit var llSpecialModes: LinearLayout
    private lateinit var btnInfrared: Button
    private lateinit var btnNightVision: Button

    private var deviceId: String = ""
    private var passwordHash: String = ""
    private var serviceRunning = false
    private var infraredMode = false   // ★ 红外模式
    private var nightVisionMode = false // ★ 夜视模式

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        etPassword = findViewById(R.id.etPassword)
        etServer   = findViewById(R.id.etServer)
        btnStart   = findViewById(R.id.btnStart)
        btnStop    = findViewById(R.id.btnStop)
        ivQrCode   = findViewById(R.id.ivQrCode)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvStatus   = findViewById(R.id.tvStatus)
        tvVersion  = findViewById(R.id.tvVersion)
        cbRemember = findViewById(R.id.cbRemember)
        llSpecialModes = findViewById(R.id.llSpecialModes)
        btnInfrared = findViewById(R.id.btnInfrared)
        btnNightVision = findViewById(R.id.btnNightVision)

        // 显示版本号
        try {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${pkgInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "v2.5.0"
        }

        // 加载服务器地址
        val savedServer = prefs.getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
        etServer.setText(savedServer)

        // 获取或生成设备ID
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString().take(8)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
        tvDeviceId.text = "设备ID: $deviceId"

        // ★ 检查是否已设置密码
        val savedHash = prefs.getString(KEY_PASSWORD_HASH, null)
        val savedPassword = prefs.getString(KEY_PASSWORD_PLAIN, null)
        val autoStart = prefs.getBoolean(KEY_AUTO_START, false)

        cbRemember.isChecked = autoStart

        if (savedHash != null) {
            passwordHash = savedHash
            etPassword.hint = "输入密码启动"
            // ★ 如果记住了密码，回填显示
            if (savedPassword != null) {
                etPassword.setText(savedPassword)
            }
            btnStart.text = "启动监控"
            tvStatus.text = if (autoStart) "已记住密码，点击启动" else "已设置密码，输入后启动"
            showPreviewQr(savedServer)
        } else {
            etPassword.hint = "设置密码（6-16位）"
            btnStart.text = "设置密码并启动"
            tvStatus.text = "首次使用，请设置密码"
            showPreviewQr(savedServer)
        }

        // ★ 如果开了自动启动且有记住密码，自动启动服务
        // 注意：即使没有明文密码（savedPassword==null），只要autoStart=true且有savedHash就启动
        // 因为 doStartService 只需要 hash，不需要明文
        if (autoStart && savedHash != null) {
            val server = etServer.text.toString().trim()
            tvStatus.text = "⏳ 自动启动监控中..."
            doStartService(server, savedHash)
        }

        btnStart.setOnClickListener {
            val password = etPassword.text.toString().trim()
            val server   = etServer.text.toString().trim()

            if (password.length < 6) {
                Toast.makeText(this, "密码至少6位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString("server_url", server).apply()

            val hash = sha256(password)
            val currentSavedHash = prefs.getString(KEY_PASSWORD_HASH, null)

            when {
                currentSavedHash == null -> {
                    // 首次设置密码
                    prefs.edit().putString(KEY_PASSWORD_HASH, hash).apply()
                    passwordHash = hash
                    // ★ 保存明文密码和自启设置
                    savePasswordSettings(password, hash)
                    doStartService(server, hash)
                }
                hash == currentSavedHash -> {
                    passwordHash = hash
                    // ★ 保存明文密码和自启设置
                    savePasswordSettings(password, hash)
                    doStartService(server, hash)
                }
                else -> Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            stopMonitor()
        }

        // ★ 红外/夜视按钮
        btnInfrared.setOnClickListener {
            if (!serviceRunning) return@setOnClickListener
            infraredMode = !infraredMode
            nightVisionMode = false  // 互斥
            updateModeButtons()
            restartServiceWithMode()
        }
        btnNightVision.setOnClickListener {
            if (!serviceRunning) return@setOnClickListener
            nightVisionMode = !nightVisionMode
            infraredMode = false  // 互斥
            updateModeButtons()
            restartServiceWithMode()
        }

        checkPermissions()
        // ★ 主动请求悬浮窗权限
        requestOverlayPermission()
        // ★ 检查OTA更新
        checkAppUpdate()
    }

    /** ★ 保存密码设置（记住密码 + 自启选项） */
    private fun savePasswordSettings(password: String, hash: String) {
        val autoStart = cbRemember.isChecked
        prefs.edit().apply {
            putString(KEY_PASSWORD_HASH, hash)       // ★ 始终保存hash
            putBoolean(KEY_AUTO_START, autoStart)     // ★ 始终保存自启标记
            if (autoStart) {
                putString(KEY_PASSWORD_PLAIN, password)  // 只有勾选时才存明文
            } else {
                remove(KEY_PASSWORD_PLAIN)
            }
            apply()
        }
    }

    /** ★ 主动请求悬浮窗权限（非必须，前台服务已足够保活） */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // 悬浮窗是可选优化，不阻塞启动。用户可稍后在系统设置中开启。
                // 前台服务 + WakeLock 已提供足够的保活能力。
                Log.d(TAG, "悬浮窗权限未开启（可选优化），前台服务保活已就绪")
            }
        }
    }

    private fun showPreviewQr(server: String) {
        val qrData = "phonemonitor://device?deviceId=$deviceId&server=$server"
        val bmp = generateQrCodeSafe(qrData)
        if (bmp != null) {
            ivQrCode.setImageBitmap(bmp)
        } else {
            val fallback = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            val canvas = Canvas(fallback)
            canvas.drawColor(0xFFFFFFFF.toInt())
            val paint = Paint().apply {
                color = 0xFF1a1a2e.toInt()
                textSize = 36f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("设备ID: $deviceId", 256f, 240f, paint)
            canvas.drawText("服务器: $server", 256f, 290f, paint)
            ivQrCode.setImageBitmap(fallback)
        }
    }

    private fun doStartService(server: String, hash: String) {
        if (serviceRunning) {
            tvStatus.text = "监控已在运行中 ✅"
            return
        }

        // 生成带 token 的二维码
        val token = hash.take(16)
        val qrData = "phonemonitor://connect?deviceId=$deviceId&server=$server&token=$token"
        val bmp = generateQrCodeSafe(qrData)
        if (bmp != null) {
            ivQrCode.setImageBitmap(bmp)
        }

        // 启动采集服务
        val intent = Intent(this, CameraService::class.java).apply {
            putExtra("deviceId", deviceId)
            putExtra("server", server)
            putExtra("passwordHash", hash)
            putExtra("infraredMode", infraredMode)
            putExtra("nightVisionMode", nightVisionMode)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceRunning = true
            tvStatus.text = "监控已启动 ✅\n用查看端扫描二维码"
            btnStart.isEnabled = false
            btnStop.visibility = android.view.View.VISIBLE
            llSpecialModes.visibility = android.view.View.VISIBLE
            Log.d(TAG, "监控服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitor() {
        stopService(Intent(this, CameraService::class.java))
        serviceRunning = false
        infraredMode = false
        nightVisionMode = false
        tvStatus.text = "监控已停止"
        btnStart.isEnabled = true
        btnStop.visibility = android.view.View.GONE
        llSpecialModes.visibility = android.view.View.GONE
        updateModeButtons()
        Toast.makeText(this, "监控已停止", Toast.LENGTH_SHORT).show()
    }

    /** ★ 更新红外/夜视按钮外观 */
    private fun updateModeButtons() {
        btnInfrared.backgroundTintList = if (infraredMode)
            android.content.res.ColorStateList.valueOf(0xFF8b0000.toInt())
        else
            android.content.res.ColorStateList.valueOf(0xFF444466.toInt())
        btnInfrared.text = if (infraredMode) "🌙 红外 ✓" else "🌙 红外"

        btnNightVision.backgroundTintList = if (nightVisionMode)
            android.content.res.ColorStateList.valueOf(0xFF006400.toInt())
        else
            android.content.res.ColorStateList.valueOf(0xFF444466.toInt())
        btnNightVision.text = if (nightVisionMode) "👁 夜视 ✓" else "👁 夜视"

        val modeLabel = when {
            infraredMode -> "红外模式"
            nightVisionMode -> "夜视模式"
            else -> "标准模式"
        }
        tvStatus.text = "监控已启动 ✅ ($modeLabel)\n用查看端扫描二维码"
    }

    /** ★ 重启服务以应用红外/夜视模式 */
    private fun restartServiceWithMode() {
        val server = etServer.text.toString().trim()
        val hash = passwordHash
        stopService(Intent(this, CameraService::class.java))
        val intent = Intent(this, CameraService::class.java).apply {
            putExtra("deviceId", deviceId)
            putExtra("server", server)
            putExtra("passwordHash", hash)
            putExtra("infraredMode", infraredMode)
            putExtra("nightVisionMode", nightVisionMode)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "切换模式失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQrCodeSafe(data: String): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 512, 512, hints)
            val w = bitMatrix.width; val h = bitMatrix.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            for (x in 0 until w) {
                for (y in 0 until h) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) 0xFF1a1a2e.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bmp
        } catch (e: WriterException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
        // ★ 录音权限（语音对讲）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.filterIndexed { i, _ -> grantResults[i] != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty())
                Toast.makeText(this, "需要摄像头权限才能使用监控功能", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已开启 ✅ 后台保活将更稳定", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未开启悬浮窗权限，后台运行可能不稳定", Toast.LENGTH_LONG).show()
                }
            }
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
                    .setMessage("PhoneMonitor 采集端 $versionName 已发布，是否立即更新？$changeText\n\n更新后无需重新输入密码，所有设置保持不变。")
                    .setPositiveButton("立即更新") { _, _ ->
                        Toast.makeText(this, "正在后台下载更新...", Toast.LENGTH_SHORT).show()
                        updateManager.downloadAndInstall(apkUrl, versionName)
                    }
                    .setNegativeButton("稍后再说", null)
                    .setCancelable(false)
                    .show()
            },
            onNoUpdate = {
                Log.d(TAG, "已是最新版本")
            },
            onError = { error ->
                Log.w(TAG, "检查更新失败: $error")
            }
        )
    }
}
