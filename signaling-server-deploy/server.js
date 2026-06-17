/**
 * Phone Monitor - 信令服务器 v8
 * 新增：查看端热更新（WebView禁缓存，push即生效）
 * 运行：node server.js
 */
const express = require('express');
const http = require('http');
const crypto = require('crypto');
const path = require('path');
const multer = require('multer');

const app = express();
const server = http.createServer(app);
const PORT = process.env.PORT || 3000;

// multer：内存存储，不落盘
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 10 * 1024 * 1024 } });

// 内存存储：{ deviceId: { passwordHash, lastImage, lastImageTime, lastSeen, lastAudio, lastAudioTime, viewerAudio, viewerAudioTime } }
const devices = new Map();

// 配对码映射：{ pairCode: { deviceId, expireAt } }
const pairCodes = new Map();

// ★ 消息存储（每个设备最多保留100条）
const messages = new Map(); // { deviceId: [{ from, text, time }] }
const MAX_MESSAGES = 100;

// 工具：SHA-256
function sha256(text) {
  return crypto.createHash('sha256').update(text).digest('hex');
}

// 生成6位纯数字配对码
function generatePairCode(deviceId) {
  // 先清理该设备的旧配对码
  for (const [code, info] of pairCodes.entries()) {
    if (info.deviceId === deviceId) {
      pairCodes.delete(code);
    }
  }
  // 生成新6位码（000000-999999）
  let code;
  do {
    code = String(Math.floor(100000 + Math.random() * 900000));
  } while (pairCodes.has(code));
  // 5分钟有效期
  pairCodes.set(code, { deviceId, expireAt: Date.now() + 5 * 60 * 1000 });
  console.log(`[配对码] 设备 ${deviceId} -> ${code} (5分钟有效)`);
  return code;
}

// 中间件
app.use(express.json({ limit: '8mb' }));
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') { res.sendStatus(200); return; }
  next();
});

// 静态文件：查看端网页（★ 禁用缓存，WebView 每次都能拿到最新版本）
const viewerPath = path.join(__dirname, 'viewer');
app.use('/viewer', (req, res, next) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');
  next();
}, express.static(viewerPath));

// ★ 静态文件：APK 下载目录
const apkPath = path.join(__dirname, 'apk');
app.use('/apk', express.static(apkPath));

// 全局错误处理
app.use((err, req, res, next) => {
  console.error('[全局错误]', err.message, err.stack);
  res.status(500).json({ ok: false, error: '服务器错误: ' + err.message });
});

// ========== 路由 ==========

// 健康检查
app.get('/', (req, res) => {
  res.json({ status: 'ok', devices: devices.size, message: 'Phone Monitor Server v9 (OTA changelog)', commit: 'v2.9.1' });
});

// 查看端网页入口
app.get('/view', (req, res) => {
  res.sendFile(path.join(viewerPath, 'index.html'));
});

/**
 * ★ OTA 版本检查 API
 * GET /api/latest-version?app=collector|viewer
 * 返回最新版本号和下载URL
 */
const LATEST_VERSIONS = {
  collector: {
    versionCode: 14, versionName: '2.10.0', apkUrl: '/apk/collector-v15.apk',
    changelog: '1、摄像头打开失败时自动重试（最多3次，间隔递增）\n2、新增本地循环录像功能（5分钟分段，自动覆盖旧文件）\n3、录像前检查存储空间，预留500MB系统空间\n4、通知栏实时显示摄像头状态和上传帧计数'
  },
  viewer: {
    versionCode: 17, versionName: '2.9.1', apkUrl: '/apk/viewer-v17.apk',
    changelog: '1、OTA更新弹窗显示更新内容，可选择是否更新\n2、查看端启动Loading遮罩，不再显示裸代码页\n3、修复初始化时序，自动登录更可靠\n4、WebView禁用缓存，热更新即时生效'
  }
};

app.get('/api/latest-version', (req, res) => {
  const { app } = req.query;
  if (!app || !LATEST_VERSIONS[app]) {
    return res.json({ ok: false, error: '请指定 app=collector 或 app=viewer' });
  }
  const info = LATEST_VERSIONS[app];
  const host = req.headers.host || 'phone-monitor-server.onrender.com';
  const protocol = req.headers['x-forwarded-proto'] || 'https';
  res.json({
    ok: true,
    app,
    versionCode: info.versionCode,
    versionName: info.versionName,
    changelog: info.changelog || '',
    apkUrl: `${protocol}://${host}${info.apkUrl}`,
    apkSize: info.apkSize || 0
  });
});

/**
 * 采集端：注册设备（启动时调用一次）
 * POST /api/register
 * Body: { deviceId, passwordHash }
 */
app.post('/api/register', (req, res) => {
  const { deviceId, passwordHash } = req.body;
  if (!deviceId || !passwordHash) {
    return res.json({ ok: false, error: '缺少 deviceId 或 passwordHash' });
  }
  devices.set(deviceId, {
    passwordHash,
    lastImage: null,
    lastImageTime: 0,
    lastAudio: null,
    lastAudioTime: 0,
    audioSampleRate: 16000,
    viewerAudio: null,
    viewerAudioTime: 0,
    viewerAudioSampleRate: 16000,
    lastSeen: Date.now()
  });
  console.log(`[注册] 设备: ${deviceId}`);
  res.json({ ok: true, deviceId });
});

/**
 * 采集端：生成/刷新配对码
 * POST /api/pair-code
 * Body: { deviceId, passwordHash, pairCode? }
 * 如果客户端传了 pairCode，直接使用；否则服务器生成
 */
app.post('/api/pair-code', (req, res) => {
  const { deviceId, passwordHash, pairCode: clientCode } = req.body;
  if (!deviceId) {
    return res.json({ ok: false, error: '缺少 deviceId' });
  }
  const device = devices.get(deviceId);
  if (!device) {
    return res.json({ ok: false, error: '设备未注册' });
  }
  // 验证密码Hash
  if (passwordHash && device.passwordHash && passwordHash !== device.passwordHash) {
    return res.json({ ok: false, error: '身份验证失败' });
  }
  
  let code;
  if (clientCode && /^\d{6}$/.test(clientCode)) {
    // 客户端指定配对码
    // 先清理该设备的旧码
    for (const [oldCode, info] of pairCodes.entries()) {
      if (info.deviceId === deviceId) {
        pairCodes.delete(oldCode);
      }
    }
    pairCodes.set(clientCode, { deviceId, expireAt: Date.now() + 5 * 60 * 1000 });
    code = clientCode;
    console.log(`[配对码-客户端] 设备 ${deviceId} -> ${code}`);
  } else {
    code = generatePairCode(deviceId);
  }
  
  res.json({ ok: true, pairCode: code });
});

/**
 * 查看端：用配对码查询设备ID
 * GET /api/pair-lookup?pairCode=123456
 */
app.get('/api/pair-lookup', (req, res) => {
  const { pairCode } = req.query;
  if (!pairCode) return res.json({ ok: false, error: '缺少配对码' });

  const info = pairCodes.get(pairCode);
  if (!info) {
    return res.json({ ok: false, error: '配对码不存在' });
  }
  if (Date.now() > info.expireAt) {
    pairCodes.delete(pairCode);
    return res.json({ ok: false, error: '配对码已过期' });
  }
  // 检查设备是否还在线
  const device = devices.get(info.deviceId);
  if (!device) {
    pairCodes.delete(pairCode);
    return res.json({ ok: false, error: '设备已离线' });
  }
  res.json({ ok: true, deviceId: info.deviceId });
});

/**
 * 采集端：上传截图 + 音频
 * POST /api/upload
 * 支持两种格式：
 *   1. Multipart/form-data（新版采集端 v3）: 字段 deviceId, passwordHash, type("image"|"audio"), 
 *      timestamp, image(文件) 或 audio(文件), sampleRate
 *   2. application/json（旧版兼容）: { deviceId, passwordHash, image, audio, sampleRate }
 */
app.post('/api/upload', (req, res, next) => {
  // 根据 Content-Type 判断格式：multipart 用 multer，JSON 直接处理
  const ct = req.headers['content-type'] || '';
  if (ct.includes('multipart/form-data')) {
    // 使用 multer 解析 multipart
    upload.fields([{ name: 'image', maxCount: 1 }, { name: 'audio', maxCount: 1 }])(req, res, next);
  } else {
    // JSON 格式直接放行
    next();
  }
}, (req, res) => {
  // 兼容两种格式：multipart 用 req.body + req.files；JSON 用 req.body
  try {
  let deviceId, passwordHash, sampleRate;
  let imageBase64 = null;
  let audioBuffer = null;

  if (req.files && (req.files['image'] || req.files['audio'])) {
    // ── Multipart 格式（新版采集端）──
    deviceId = req.body.deviceId;
    passwordHash = req.body.passwordHash;
    sampleRate = parseInt(req.body.sampleRate) || 16000;

    if (req.files['image']) {
      imageBase64 = req.files['image'][0].buffer.toString('base64');
    }
    if (req.files['audio']) {
      audioBuffer = req.files['audio'][0].buffer.toString('base64');
    }
  } else {
    // ── JSON 格式（旧版兼容）──
    deviceId = req.body.deviceId;
    passwordHash = req.body.passwordHash;
    imageBase64 = req.body.image || null;
    audioBuffer = req.body.audio || null;
    sampleRate = req.body.sampleRate || 16000;
  }

  if (!deviceId || (!imageBase64 && !audioBuffer)) {
    return res.json({ ok: false, error: '缺少参数' });
  }

  let device = devices.get(deviceId);

  // 如果设备未注册，且带了 passwordHash，则自动注册（容错）
  if (!device) {
    if (passwordHash) {
      device = { passwordHash, lastImage: null, lastImageTime: 0, lastAudio: null, lastAudioTime: 0, lastSeen: Date.now() };
      devices.set(deviceId, device);
      console.log(`[自动注册] 设备: ${deviceId}`);
    } else {
      return res.json({ ok: false, error: '设备未注册' });
    }
  }

  // 验证身份：支持 passwordHash（采集端）和 password（查看端明文）
  const effectiveHash = passwordHash || (req.body.password ? sha256(req.body.password) : null);
  if (effectiveHash && device.passwordHash && effectiveHash !== device.passwordHash) {
    return res.json({ ok: false, error: '身份验证失败' });
  }

  device.lastSeen = Date.now();

  if (imageBase64) {
    device.lastImage = imageBase64;
    device.lastImageTime = Date.now();
  }

  if (audioBuffer) {
    const from = req.body.from || 'collector'; // ★ 区分来源
    if (from === 'viewer') {
      device.viewerAudio = audioBuffer;
      device.viewerAudioTime = Date.now();
      device.viewerAudioSampleRate = sampleRate;
    } else {
      device.lastAudio = audioBuffer;
      device.lastAudioTime = Date.now();
      device.audioSampleRate = sampleRate;
    }
  }

  res.json({ ok: true });
  } catch (err) {
    console.error('[upload] 错误:', err.message);
    res.status(500).json({ ok: false, error: '服务器内部错误: ' + err.message });
  }
});

/**
 * 查看端：拉取最新截图
 * GET /api/latest-image?deviceId=xxx
 */
app.get('/api/latest-image', (req, res) => {
  const { deviceId } = req.query;
  if (!deviceId) return res.json({ ok: false, error: '缺少 deviceId' });

  const device = devices.get(deviceId);
  if (!device || !device.lastImage) {
    return res.json({ ok: false, error: '暂无画面' });
  }
  res.json({ ok: true, image: device.lastImage, time: device.lastImageTime });
});

/**
 * 查看端/采集端：拉取最新音频
 * GET /api/latest-audio?deviceId=xxx&from=collector|viewer
 * from=viewer: 采集端拉取查看端的语音
 * from=collector(默认): 查看端拉取采集端的语音
 */
app.get('/api/latest-audio', (req, res) => {
  const { deviceId, from } = req.query;
  if (!deviceId) return res.json({ ok: false, error: '缺少 deviceId' });

  const device = devices.get(deviceId);
  if (!device) return res.json({ ok: false, error: '设备未在线' });

  if (from === 'viewer') {
    // 采集端拉取查看端语音
    if (!device.viewerAudio) {
      return res.json({ ok: false, error: '暂无对讲音频' });
    }
    return res.json({ ok: true, audio: device.viewerAudio, time: device.viewerAudioTime, sampleRate: device.viewerAudioSampleRate || 16000 });
  }

  // 默认：查看端拉取采集端语音
  if (!device.lastAudio) {
    return res.json({ ok: false, error: '暂无音频' });
  }
  res.json({ ok: true, audio: device.lastAudio, time: device.lastAudioTime, sampleRate: device.audioSampleRate || 16000 });
});

// ★ ========== 对话消息 API ==========

/**
 * 发送消息（查看端 → 采集端，或采集端 → 查看端）
 * POST /api/chat/send
 * Body: { deviceId, password, from, text }
 */
app.post('/api/chat/send', (req, res) => {
  const { deviceId, password, from, text } = req.body;
  if (!deviceId || !text) return res.json({ ok: false, error: '缺少参数' });

  const device = devices.get(deviceId);
  if (!device) return res.json({ ok: false, error: '设备未在线' });

  // 验证密码
  if (password) {
    const inputHash = sha256(password);
    if (inputHash !== device.passwordHash) {
      return res.json({ ok: false, error: '密码错误' });
    }
  }

  if (!messages.has(deviceId)) messages.set(deviceId, []);
  const list = messages.get(deviceId);
  list.push({ from: from || 'viewer', text, time: Date.now() });
  if (list.length > MAX_MESSAGES) list.splice(0, list.length - MAX_MESSAGES);

  res.json({ ok: true, count: list.length });
});

/**
 * 拉取消息（查看端或采集端轮询）
 * GET /api/chat/messages?deviceId=xxx&since=timestamp
 */
app.get('/api/chat/messages', (req, res) => {
  const { deviceId, since } = req.query;
  if (!deviceId) return res.json({ ok: false, error: '缺少 deviceId' });

  const device = devices.get(deviceId);
  if (!device) return res.json({ ok: false, error: '设备未在线' });

  const list = messages.get(deviceId) || [];
  const sinceTs = parseInt(since) || 0;
  const recent = list.filter(m => m.time > sinceTs);

  res.json({ ok: true, messages: recent, lastTime: Date.now() });
});

/**
 * 查看端：验证密码
 * GET /api/verify?deviceId=xxx&password=xxx
 * 注意：password 是明文，服务器做 sha256 后比较
 */
app.get('/api/verify', (req, res) => {
  const { deviceId, password, passwordHash, token } = req.query;
  if (!deviceId) return res.json({ ok: false, error: '缺少 deviceId' });

  const device = devices.get(deviceId);
  if (!device) return res.json({ ok: false, error: '设备未在线' });

  // 支持三种验证方式：
  // 1. token（扫码生成的前16位哈希快捷验证）
  // 2. passwordHash（完整哈希）
  // 3. password（明文密码，服务器做sha256比较）
  if (token && device.passwordHash) {
    // token 验证：比较 passwordHash 前 16 位
    if (device.passwordHash.startsWith(token)) {
      return res.json({ ok: true, deviceId, token });
    }
    return res.json({ ok: false, error: 'token无效或已过期' });
  }

  let inputHash;
  if (passwordHash) {
    inputHash = passwordHash; // 查看端直接传哈希
  } else if (password) {
    inputHash = sha256(password); // 明文密码做哈希
  } else {
    return res.json({ ok: false, error: '缺少 password' });
  }

  if (inputHash === device.passwordHash) {
    res.json({ ok: true, deviceId });
  } else {
    res.json({ ok: false, error: '密码错误' });
  }
});

// 心跳：同时支持 POST（body: {deviceId}）和 GET（query: ?deviceId=xxx）
app.all('/api/heartbeat', (req, res) => {
  const deviceId = (req.method === 'GET' ? req.query : req.body).deviceId;
  const device = devices.get(deviceId);
  if (device) device.lastSeen = Date.now();
  res.json({ ok: true });
});

/**
 * 查看端：清理已使用的配对码
 * POST/GET /api/pair-clear?pairCode=xxx
 */
app.all('/api/pair-clear', (req, res) => {
  const pairCode = req.query.pairCode || req.body?.pairCode;
  if (pairCode && pairCodes.has(pairCode)) {
    pairCodes.delete(pairCode);
    console.log(`[配对码] 已清理: ${pairCode}`);
  }
  res.json({ ok: true });
});

// 设备列表（调试用）
app.get('/api/status', (req, res) => {
  const list = Array.from(devices.entries()).map(([id, d]) => ({
    id,
    hasImage: !!d.lastImage,
    lastSeen: new Date(d.lastSeen).toISOString()
  }));
  res.json({ devices: list.length, list });
});

// 清理超时设备和过期配对码（每60秒）
setInterval(() => {
  const now = Date.now();

  // 清理超时设备（10分钟无心跳）
  for (const [id, d] of devices.entries()) {
    if (now - d.lastSeen > 10 * 60 * 1000) {
      devices.delete(id);
      console.log(`[清理] 设备下线: ${id}`);
    }
  }

  // 清理过期配对码
  for (const [code, info] of pairCodes.entries()) {
    if (now > info.expireAt) {
      pairCodes.delete(code);
      console.log(`[配对码] 过期: ${code}`);
    }
  }
}, 60 * 1000);

// 启动
server.listen(PORT, '0.0.0.0', () => {
  console.log('========================================');
  console.log('  Phone Monitor 信令服务器 v7 (OTA更新)');
  console.log(`  端口: ${PORT}`);
  console.log('  新增: 应用内自动更新 API');
  console.log('  采集端注册:       POST /api/register');
  console.log('  采集端生成配对码: POST /api/pair-code');
  console.log('  采集端上传(图+音): POST /api/upload');
  console.log('  查看端用配对码查设备: GET  /api/pair-lookup?pairCode=xxx');
  console.log('  查看端拉取图片:       GET  /api/latest-image?deviceId=xxx');
  console.log('  查看端拉取音频:       GET  /api/latest-audio?deviceId=xxx');
  console.log('  密码验证:         GET  /api/verify?deviceId=xxx&password=xxx');
  console.log('  版本检查:         GET  /api/latest-version?app=collector|viewer');
  console.log('  APK下载:          GET  /apk/xxx.apk');
  console.log('========================================');
});
