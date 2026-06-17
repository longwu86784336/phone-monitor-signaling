package com.phonemonitor.collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class CameraService : Service() {

    companion object {
        const val TAG = "CameraService"
        const val CHANNEL_ID = "camera_monitor"
        const val NOTIFICATION_ID = 1
        const val CAPTURE_INTERVAL_MS = 500L
        // ★ 音频参数
        const val SAMPLE_RATE = 16000
        const val AUDIO_CHUNK_MS = 500L  // 每500ms一个音频块
        const val AUDIO_BUFFER_SIZE = SAMPLE_RATE * 2 / 2  // 16bit PCM, 500ms = 16000 samples = 32000 bytes
        // ★ 录像参数
        const val RECORDING_SEGMENT_MS = 300000L  // 每段5分钟
        const val MAX_RECORDING_FILES = 20         // 最多保留20段（约100分钟）
        const val MIN_FREE_SPACE_MB = 500L         // 预留500MB系统空间
        const val MAX_RECORDING_SPACE_MB = 2048L   // 录像最多占用2GB
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var windowManager: WindowManager? = null
    private var overlayView: ImageView? = null

    private var deviceId: String = ""
    private var serverUrl: String = ""
    private var passwordHash: String = ""
    private var isRunning = false
    private var lastUploadTime = 0L
    private var lastImageUploadTime = 0L
    private var lastAudioUploadTime = 0L
    private var infraredMode = false
    private var nightVisionMode = false
    private var frameUploadCount = 0
    private var frameFailCount = 0
    private var cameraError = ""

    // ★ 本地录像
    private var mediaRecorder: MediaRecorder? = null
    private var recordingSurface: Surface? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTime = 0L
    private var recordingSegmentIndex = 0
    private var recordingDir: File? = null

    // ★ 音频采集
    private var audioRecord: AudioRecord? = null
    private var audioRecordThread: Thread? = null
    private var latestAudioBase64: String? = null

    // ★ 音频播放（接收查看端语音）
    private var audioTrack: AudioTrack? = null
    private var audioPlayThread: Thread? = null
    private var lastAudioPollTime = 0L

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhoneMonitor::WakeLock"
        )
        @Suppress("WakelockTimeout")
        wakeLock.acquire()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        deviceId = intent?.getStringExtra("deviceId") ?: "unknown"
        serverUrl = intent?.getStringExtra("server") ?: "192.168.1.100:3000"
        passwordHash = intent?.getStringExtra("passwordHash") ?: ""
        infraredMode = intent?.getBooleanExtra("infraredMode", false) ?: false
        nightVisionMode = intent?.getBooleanExtra("nightVisionMode", false) ?: false

        Log.d(TAG, "启动服务 deviceId=$deviceId server=$serverUrl infrared=$infraredMode night=$nightVisionMode")

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        startBackgroundThread()

        backgroundHandler?.post {
            registerDevice()
            openCamera()
            startAudioCapture()   // ★ 开始录音
            startAudioPlayback()  // ★ 开始播放对讲音频
        }

        // 1像素悬浮窗保活（可选优化，非必须）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
                addOverlayView()
            } else {
                Log.d(TAG, "悬浮窗权限未开启，前台服务保活已就绪")
            }
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗添加失败（不影响服务）: ${e.message}")
        }

        isRunning = true
        return START_STICKY
    }

    /** 向服务器注册设备 */
    private fun registerDevice() {
        try {
            val json = """{"deviceId":"$deviceId","passwordHash":"$passwordHash"}"""
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://$serverUrl/api/register")
                .post(body)
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                Log.d(TAG, "注册结果: ${resp.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册失败: ${e.message}", e)
        }
    }

    // ★ ========== 音频采集 ==========

    private fun startAudioCapture() {
        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBufSize, AUDIO_BUFFER_SIZE)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "音频采集已启动, bufferSize=$bufferSize")

            audioRecordThread = Thread {
                val buffer = ByteArray(AUDIO_BUFFER_SIZE)
                var lastAudioUpload = 0L
                while (isRunning && audioRecord != null) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (read > 0) {
                            val pcmData = if (read == buffer.size) buffer else buffer.copyOf(read)
                            latestAudioBase64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)

                            // 每500ms上传一次音频
                            val now = System.currentTimeMillis()
                            if (now - lastAudioUpload >= AUDIO_CHUNK_MS) {
                                lastAudioUpload = now
                                uploadAudio(latestAudioBase64!!)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "录音错误: ${e.message}")
                    }
                }
            }.apply {
                name = "AudioCapture"
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "无录音权限: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "启动音频采集失败: ${e.message}", e)
        }
    }

    private fun uploadAudio(audioBase64: String) {
        try {
            val json = """{"deviceId":"$deviceId","passwordHash":"$passwordHash","audio":"$audioBase64","sampleRate":$SAMPLE_RATE}"""
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://$serverUrl/api/upload")
                .post(body)
                .build()
            okHttpClient.newCall(request).execute().use { /* 忽略响应 */ }
        } catch (e: Exception) {
            Log.e(TAG, "音频上传失败: ${e.message}")
        }
    }

    // ★ ========== 音频播放（接收查看端对讲） ==========

    private fun startAudioPlayback() {
        audioPlayThread = Thread {
            // 先等2秒再开始轮询
            try { Thread.sleep(2000) } catch (_: Exception) {}

            while (isRunning) {
                try {
                    pollAndPlayAudio()
                } catch (e: Exception) {
                    Log.e(TAG, "音频播放轮询错误: ${e.message}")
                }
                try { Thread.sleep(500) } catch (_: Exception) {}
            }
        }.apply {
            name = "AudioPlayback"
            start()
        }
    }

    private fun pollAndPlayAudio() {
        try {
            val request = Request.Builder()
                .url("https://$serverUrl/api/latest-audio?deviceId=$deviceId&from=viewer")
                .get()
                .build()
            val resp = okHttpClient.newCall(request).execute()
            if (!resp.isSuccessful) return

            val body = resp.body?.string() ?: return
            val data = org.json.JSONObject(body)
            if (!data.optBoolean("ok", false)) return
            val audioB64 = data.optString("audio", "")
            if (audioB64.isEmpty()) return

            val audioTime = data.optLong("time", 0)
            // 跳过已播放的
            if (audioTime <= lastAudioPollTime) return
            lastAudioPollTime = audioTime

            val pcmBytes = Base64.decode(audioB64, Base64.DEFAULT)
            playPcmAudio(pcmBytes)
        } catch (e: Exception) {
            // 静默
        }
    }

    private fun playPcmAudio(pcmData: ByteArray) {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize, pcmData.size)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)  // ★ MODE_STREAM 更适合动态音频
                .build()

            track.play()
            track.write(pcmData, 0, pcmData.size)

            // 等待播放完成
            val durationMs = (pcmData.size.toLong() * 1000) / (SAMPLE_RATE * 2) + 50
            Thread.sleep(durationMs)
            track.stop()
            track.release()
        } catch (e: Exception) {
            Log.e(TAG, "播放音频失败: ${e.message}")
        }
    }

    // ★ ========== 摄像头 ==========

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手机监控运行中")
            .setContentText("设备ID: $deviceId")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "监控服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "手机监控摄像头服务"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun openCamera() {
        // 先检查存储空间并初始化录像目录
        initRecordingDir()

        tryOpenCamera(retryCount = 0)
    }

    private fun tryOpenCamera(retryCount: Int) {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val ch = cameraManager.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            
            if (cameraId == null) {
                val msg = "未找到摄像头！cameraIdList=${cameraManager.cameraIdList.toList()}"
                Log.e(TAG, msg)
                cameraError = "未找到摄像头"
                updateNotification("❌ 摄像头错误", cameraError)
                return
            }

            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "无摄像头权限！请到系统设置中授予权限")
                cameraError = "无摄像头权限"
                updateNotification("❌ 权限不足", "请到系统设置中授予摄像头权限")
                return
            }

            val attemptMsg = if (retryCount > 0) " (第${retryCount + 1}次尝试)" else ""
            Log.d(TAG, "正在打开摄像头: $cameraId$attemptMsg")
            updateNotification("⏳ 启动中...", "正在打开摄像头 $cameraId$attemptMsg")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "摄像头已打开")
                    cameraDevice = camera
                    updateNotification("📷 监控+录像", "设备ID: $deviceId | 录像: 就绪")
                    createCaptureSession()
                    startRecording()  // ★ 摄像头打开后立即开始录像
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "摄像头断开")
                    cameraError = "摄像头断开"
                    updateNotification("⚠️ 摄像头断开", "尝试重新连接...")
                    camera.close(); cameraDevice = null
                    stopRecording()  // ★ 断开时停止录像
                    // 延迟重试
                    backgroundHandler?.postDelayed({ tryOpenCamera(0) }, 3000)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    val errMsg = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "摄像头设备错误"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "摄像头已被禁用"
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "摄像头被其他应用占用"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "摄像头服务错误"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "摄像头数量已达上限"
                        else -> "未知错误($error)"
                    }
                    Log.e(TAG, "摄像头错误: $errMsg (重试${retryCount}次)")
                    cameraError = errMsg
                    camera.close(); cameraDevice = null
                    stopRecording()  // ★ 错误时停止录像

                    // ★ 对于占用类错误，自动重试（最多3次，间隔递增）
                    val canRetry = error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                            || error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                            || error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE
                    if (canRetry && retryCount < 3) {
                        val delay = 2000L * (retryCount + 1)
                        updateNotification("⏳ 摄像头被占用", "第${retryCount + 1}/3次重试，${delay / 1000}秒后...")
                        backgroundHandler?.postDelayed({ tryOpenCamera(retryCount + 1) }, delay)
                    } else {
                        updateNotification("❌ 摄像头错误", errMsg + if (retryCount >= 3) " (重试3次均失败)" else "")
                    }
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "打开摄像头失败: ${e.message}", e)
            cameraError = "CameraAccessException: ${e.message}"
            updateNotification("❌ 摄像头异常", e.message ?: "未知")
            if (retryCount < 3) {
                backgroundHandler?.postDelayed({ tryOpenCamera(retryCount + 1) }, 3000)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "安全异常: ${e.message}", e)
            cameraError = "SecurityException: ${e.message}"
            updateNotification("❌ 安全限制", "Android 14+ 可能需要额外权限")
        }
    }

    private fun createCaptureSession() {
        val cameraDevice = this.cameraDevice ?: return

        val map = cameraManager.getCameraCharacteristics(cameraDevice.id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val size = map.getOutputSizes(ImageFormat.JPEG)
            ?.filter { it.width <= 1280 }
            ?.maxByOrNull { it.width * it.height }
            ?: map.getOutputSizes(ImageFormat.JPEG)?.firstOrNull()
            ?: return

        Log.d(TAG, "预览分辨率: ${size.width}x${size.height}")

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastImageUploadTime < CAPTURE_INTERVAL_MS) {
                    return@setOnImageAvailableListener
                }
                lastImageUploadTime = now

                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                uploadFrame(bytes)
            } finally {
                image.close()
            }
        }, backgroundHandler)

        val surface = imageReader?.surface ?: return

        // ★ 构建 surface 列表（上传用 + 录像用）
        val surfaces = mutableListOf(surface)
        recordingSurface?.let { surfaces.add(it) }

        try {
            @Suppress("DEPRECATION")
            cameraDevice.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(surface)
                        recordingSurface?.let { req.addTarget(it) }  // ★ 同时输出到录像
                        applySpecialModes(req)
                        session.setRepeatingRequest(req.build(), null, backgroundHandler)
                        Log.d(TAG, "预览已启动")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "设置预览失败: ${e.message}", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "CameraSession 配置失败")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "创建CaptureSession失败: ${e.message}", e)
        }
    }

    private fun uploadFrame(jpegBytes: ByteArray) {
        if (!isRunning) return
        if (!wakeLock.isHeld) {
            @Suppress("WakelockTimeout")
            wakeLock.acquire()
        }

        try {
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val json = """{"deviceId":"$deviceId","passwordHash":"$passwordHash","image":"$base64","timestamp":${System.currentTimeMillis()}}"""
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://$serverUrl/api/upload")
                .post(body)
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (resp.code == 200) {
                    frameUploadCount++
                    // 每50帧更新一次通知
                    if (frameUploadCount % 50 == 0) {
                        updateNotification("📷 监控+录像", "设备ID: $deviceId | 已上传: ${frameUploadCount}帧")
                    }
                } else {
                    frameFailCount++
                    Log.w(TAG, "图片上传HTTP错误: ${resp.code} (成功${frameUploadCount}帧/失败${frameFailCount}帧)")
                }
                Unit
            }
        } catch (e: Exception) {
            frameFailCount++
            Log.e(TAG, "图片上传失败(${frameUploadCount}成功/${frameFailCount}失败): ${e.message}", e)
        }
    }

    // ★ ==================== 本地录像 ====================

    private fun initRecordingDir() {
        try {
            val dir = File(getExternalFilesDir(null), "Recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            recordingDir = dir
            Log.d(TAG, "录像目录: ${dir.absolutePath}")
            // 启动时清理超量文件
            cleanOldRecordings()
        } catch (e: Exception) {
            Log.e(TAG, "初始化录像目录失败: ${e.message}")
        }
    }

    private fun getAvailableSpaceMB(): Long {
        return try {
            val dir = recordingDir ?: return 0L
            val stat = StatFs(dir.absolutePath)
            val availableBytes = stat.availableBytes
            availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    private fun getRecordingDirSizeMB(): Long {
        val dir = recordingDir ?: return 0L
        var totalSize = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile) totalSize += file.length()
        }
        return totalSize / (1024 * 1024)
    }

    private fun startRecording() {
        try {
            val dir = recordingDir ?: return
            val availableMB = getAvailableSpaceMB()

            // 检查可用空间是否足够（预留 MIN_FREE_SPACE_MB 给系统）
            if (availableMB < MIN_FREE_SPACE_MB + 50) {
                Log.w(TAG, "存储空间不足: 可用${availableMB}MB, 需要至少${MIN_FREE_SPACE_MB + 50}MB")
                updateNotification("⚠️ 存储不足", "可用${availableMB}MB，录像未启动")
                return
            }

            // 检查录像目录是否超过限制，超过则清理
            val dirSizeMB = getRecordingDirSizeMB()
            if (dirSizeMB >= MAX_RECORDING_SPACE_MB) {
                cleanOldRecordings()
            }

            // 生成文件名
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = File(dir, "rec_${timestamp}.mp4")
            currentRecordingFile = file
            recordingStartTime = System.currentTimeMillis()
            recordingSegmentIndex++

            val previewSize = imageReader?.let {
                android.util.Size(it.width, it.height)
            }

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file.absolutePath)
                setVideoFrameRate(15)
                if (previewSize != null) {
                    setVideoSize(previewSize.width, previewSize.height)
                } else {
                    setVideoSize(640, 480)
                }
                setVideoEncodingBitRate(1024 * 1024)  // 1Mbps
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(32000)

                prepare()
                recordingSurface = surface  // 保存 surface 引用
                start()
            }

            Log.d(TAG, "录像已启动: ${file.name}")
            updateNotification("📷 监控+录像", "设备ID: $deviceId | 录像中 | 可用${availableMB}MB")

            // 定时检查是否需要切段
            backgroundHandler?.postDelayed({ checkRecordingSegment() }, RECORDING_SEGMENT_MS)

        } catch (e: Exception) {
            Log.e(TAG, "启动录像失败: ${e.message}", e)
            mediaRecorder?.release()
            mediaRecorder = null
            recordingSurface = null
        }
    }

    private fun checkRecordingSegment() {
        if (!isRunning) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        if (elapsed >= RECORDING_SEGMENT_MS) {
            // 切到下一段
            Log.d(TAG, "录像分段切换，已录${elapsed / 1000}秒")
            stopRecording()
            cleanOldRecordings()
            startRecording()
        } else {
            // 继续等待
            backgroundHandler?.postDelayed({ checkRecordingSegment() }, RECORDING_SEGMENT_MS - elapsed)
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                try { reset() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止录像异常: ${e.message}")
        }
        mediaRecorder = null
        recordingSurface = null
    }

    private fun cleanOldRecordings() {
        try {
            val dir = recordingDir ?: return
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            if (files == null || files.isEmpty()) return

            // 按修改时间排序（旧在前）
            files.sortBy { it.lastModified() }

            // 计算总大小，删除最旧的直到满足限制
            var totalSize = files.sumOf { it.length() }
            val maxBytes = MAX_RECORDING_SPACE_MB * 1024 * 1024

            for (file in files) {
                if (totalSize <= maxBytes && files.size - files.indexOf(file) <= MAX_RECORDING_FILES) {
                    break
                }
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                    Log.d(TAG, "清理旧录像: ${file.name} (${size / 1024}KB)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧录像失败: ${e.message}")
        }
    }

    private fun addOverlayView() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView = ImageView(this)
            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                width = 1; height = 1
                gravity = Gravity.START or Gravity.TOP
                x = 0; y = 0
            }
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "服务销毁")
        isRunning = false

        // 停止录像
        stopRecording()

        // 停止音频采集
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
        audioRecordThread?.interrupt()

        // 停止音频播放
        audioPlayThread?.interrupt()

        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun applySpecialModes(req: CaptureRequest.Builder) {
        if (infraredMode) {
            req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            req.set(CaptureRequest.SENSOR_SENSITIVITY, 1600)
        }
        if (nightVisionMode) {
            req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            req.set(CaptureRequest.SENSOR_SENSITIVITY, 3200)
            req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 200000000L)
        }
        if (!infraredMode && !nightVisionMode) {
            req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
