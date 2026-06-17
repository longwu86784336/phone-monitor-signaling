const express = require('express');
const multer = require('multer');
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 10*1024*1024 } });

const app = express();
app.use(express.json({ limit: '8mb' }));

app.post('/api/upload', upload.fields([{ name: 'image', maxCount: 1 }, { name: 'audio', maxCount: 1 }]), (req, res) => {
  let deviceId, imageBase64 = null;
  
  if (req.files && (req.files['image'] || req.files['audio'])) {
    deviceId = req.body.deviceId;
    console.log('[MULTIPART] deviceId=', deviceId, 'hasImage=', !!req.files['image']);
    if (req.files['image']) {
      imageBase64 = req.files['image'][0].buffer.toString('base64');
    }
  } else {
    deviceId = req.body.deviceId;
    imageBase64 = req.body.image || null;
    console.log('[JSON] deviceId=', deviceId, 'hasImage=', !!imageBase64);
  }
  
  if (!deviceId || (!imageBase64 && !req.body.audio)) {
    return res.json({ ok: false, error: '缺少参数', debug: { deviceId, hasImg: !!imageBase64, hasAudio: !!req.body.audio, hasFiles: !!req.files }});
  }
  res.json({ ok: true });
});

app.listen(19996, () => console.log('Test server on :19996'));
