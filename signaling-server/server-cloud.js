// PhoneMonitor 公网中继服务器
// 部署到任何支持Node.js的平台（Render/Railway/Vercel等）
// 支持多对设备同时监控

const http = require('http');
const fs = require('fs');

const PORT = process.env.PORT || 3000;

// 存储所有连接的设备对
const sessions = new Map();

// 静态文件服务
function serveStatic(res, filePath, contentType) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not Found');
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType, 'Access-Control-Allow-Origin': '*' });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const path = url.pathname;

  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    res.end();
    return;
  }

  // 首页
  if (path === '/' || path === '/index.html') {
    serveStatic(res, __dirname + '/viewer-web/index.html', 'text/html');
    return;
  }

  // 查看端页面
  if (path === '/viewer') {
    serveStatic(res, __dirname + '/viewer-web/index.html', 'text/html');
    return;
  }

  // API: 注册采集端
  if (path === '/api/register' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const data = JSON.parse(body);
        const deviceId = data.deviceId;
        const password = data.password;
        
        if (!deviceId || !password) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Missing deviceId or password' }));
          return;
        }

        sessions.set(deviceId, {
          password: password,
          lastSeen: Date.now(),
          viewers: new Set()
        });

        console.log(`[REGISTER] Device registered: ${deviceId}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, deviceId }));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // API: 上传截图
  if (path === '/api/upload' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const data = JSON.parse(body);
        const deviceId = data.deviceId;
        const password = data.password;
        const image = data.image;

        const session = sessions.get(deviceId);
        if (!session || session.password !== password) {
          res.writeHead(403, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Unauthorized' }));
          return;
        }

        session.lastFrame = image;
        session.lastFrameTime = Date.now();
        session.lastSeen = Date.now();

        // 通知所有观看端
        session.viewers.forEach(viewerId => {
          const viewer = sessions.get(viewerId);
          if (viewer) {
            viewer.pendingFrame = image;
          }
        });

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // API: 拉取最新画面
  if (path === '/api/latest-image') {
    const deviceId = url.searchParams.get('deviceId');
    const password = url.searchParams.get('password');
    const viewerId = url.searchParams.get('viewerId');

    const session = sessions.get(deviceId);
    if (!session || session.password !== password) {
      res.writeHead(403, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Unauthorized' }));
      return;
    }

    // 注册观看端
    if (viewerId) {
      session.viewers.add(viewerId);
      const viewer = sessions.get(viewerId);
      if (viewer) {
        viewer.viewing = deviceId;
      }
    }

    if (session.lastFrame) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ 
        success: true, 
        image: session.lastFrame,
        timestamp: session.lastFrameTime 
      }));
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, message: 'No frame yet' }));
    }
    return;
  }

  // API: 验证密码
  if (path === '/api/verify') {
    const deviceId = url.searchParams.get('deviceId');
    const password = url.searchParams.get('password');

    const session = sessions.get(deviceId);
    if (session && session.password === password) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true }));
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false }));
    }
    return;
  }

  // API: 心跳
  if (path === '/api/heartbeat' && req.method === 'POST') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const data = JSON.parse(body);
        const deviceId = data.deviceId;
        const session = sessions.get(deviceId);
        if (session) {
          session.lastSeen = Date.now();
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: e.message }));
      }
    });
    return;
  }

  // API: 状态
  if (path === '/api/status') {
    const status = {
      devices: sessions.size,
      deviceList: Array.from(sessions.entries()).map(([id, s]) => ({
        id,
        viewers: s.viewers.size,
        lastSeen: s.lastSeen
      }))
    };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(status));
    return;
  }

  // 404
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Not Found' }));
});

// 清理过期会话（5分钟无心跳）
setInterval(() => {
  const now = Date.now();
  for (const [id, session] of sessions.entries()) {
    if (now - session.lastSeen > 5 * 60 * 1000) {
      sessions.delete(id);
      console.log(`[CLEANUP] Removed expired device: ${id}`);
    }
  }
}, 60 * 1000);

server.listen(PORT, '0.0.0.0', () => {
  console.log(`PhoneMonitor Server running on port ${PORT}`);
  console.log(`Viewer URL: http://localhost:${PORT}/viewer`);
});
