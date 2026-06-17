# Phone Monitor 信令服务器 — Render 部署指南

## 第一步：在 GitHub 新建仓库

1. 打开 https://github.com/new
2. 仓库名填：`phone-monitor-signaling`
3. 选 **Public**
4. **不要** 勾选 "Initialize with README"（重要！）
5. 点击 **Create repository**

## 第二步：在本地初始化并推送

打开终端（PowerShell 或 CMD），依次执行：

```bash
# 进入部署目录
cd "D:\软件\安装包\手机\PhoneMonitor\signaling-server-deploy"

# 初始化 Git
git init

# 添加所有文件
git add .

# 首次提交
git commit -m "init: signaling server v5 with multipart support"

# 替换下面的 <你的GitHub用户名> 为实际用户名
git remote add origin https://github.com/<你的GitHub用户名>/phone-monitor-signaling.git

# 推送（会弹出登录框，用浏览器登录 GitHub 即可）
git push -u origin main
```

> 如果提示分支名问题，把 `main` 改成 `master`，或先执行 `git branch -M main`

## 第三步：在 Render 部署

1. 打开 https://render.com → 注册/登录（可以直接用 GitHub 账号登录）
2. 点击 **New + → Web Service**
3. 点击 **Connect account** 连接 GitHub
4. 选择 `phone-monitor-signaling` 仓库
5. 配置页面确认以下信息：
   - **Name**: `phone-monitor-signaling`（任意）
   - **Environment**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
   - **Plan**: `Free`
6. 点击 **Create Web Service**

## 第四步：拿到公网 URL

部署完成后（约2-3分钟），Render 会显示：

```
Your service is live 🎉
https://phone-monitor-signaling.onrender.com
```

复制这个 URL（**不含末尾斜杠**），这就是你的 `SERVER_URL`。

## 第五步：重新编译两个 APK

把上面的 URL 告诉我，我来：
1. 修改采集端默认服务器地址
2. 修改查看端默认服务器地址
3. 重新编译两个 APK

---

## 常见问题

**Q: `git push` 时提示需要登录？**
A: 会弹出浏览器登录 GitHub，登录后自动继续。

**Q: Render 免费版有什么限制？**
A: 15分钟无请求会休眠，下次请求需要等约30秒唤醒。对于监控场景，采集端每14分钟发一次心跳，可以保持唤醒。

**Q: 服务器启动后怎么验证？**
A: 浏览器打开 `https://你的URL/`，看到 `{"status":"ok",...}` 说明正常运行。
