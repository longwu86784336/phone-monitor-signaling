/**
 * 模拟测试：采集端注册 + 查看端连接请求
 */
const io = require('socket.io-client');

// ===== 模拟采集端注册 =====
const collector = io('http://localhost:3000');
collector.on('connect', () => {
  console.log('[采集端] 已连接到服务器');
  collector.emit('register-collector', {
    deviceId: 'TEST001',
    token: 'abc123',
    password: '123456'
  });
});

collector.on('register-result', (result) => {
  console.log('[采集端] 注册结果:', JSON.stringify(result));

  // 注册成功后，模拟查看端发起连接
  testViewer();
});

// ===== 模拟查看端连接 =====
function testViewer() {
  const viewer = io('http://localhost:3000');
  viewer.on('connect', () => {
    console.log('[查看端] 已连接到服务器');
    viewer.emit('request-view', {
      deviceId: 'TEST001',
      password: '123456'
    });
  });

  viewer.on('view-result', (result) => {
    console.log('[查看端] 连接结果:', JSON.stringify(result));
    if (result.ok) {
      console.log('✅ 密码验证成功！查看端已与采集端建立信令通道');
    } else {
      console.log('❌ 连接失败:', result.error);
    }
    // 退出
    collector.disconnect();
    viewer.disconnect();
    process.exit(0);
  });

  // 测试错误密码
  setTimeout(() => {
    const viewer2 = io('http://localhost:3000');
    viewer2.on('connect', () => {
      console.log('\n[查看端2] 使用错误密码测试...');
      viewer2.emit('request-view', {
        deviceId: 'TEST001',
        password: 'wrongpassword'
      });
    });
    viewer2.on('view-result', (result) => {
      console.log('[查看端2] 结果:', JSON.stringify(result));
      viewer2.disconnect();
    });
  }, 1000);
}

setTimeout(() => {
  console.log('❌ 测试超时');
  process.exit(1);
}, 8000);
