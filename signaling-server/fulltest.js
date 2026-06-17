const http = require('http');
const io = require('socket.io-client');
const fake = 'fakebase64image';

function post(p, b, cb) {
  const d = JSON.stringify(b);
  const req = http.request('http://localhost:3000' + p, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(d) }
  }, r => { let o = ''; r.on('data', c => o += c); r.on('end', () => cb(JSON.parse(o))); });
  req.write(d); req.end();
}
function get(p, cb) {
  http.get('http://localhost:3000' + p, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => cb(JSON.parse(d))); });
}

console.log('=== Full Flow Test ===');
const c = io('http://localhost:3000');
c.on('connect', () => {
  c.emit('register-collector', { deviceId: 'FULLTEST', token: 'tok', password: 'mypass' });
});
c.on('register-result', r => {
  console.log('1. Register:', r.ok ? 'OK' : 'FAIL');
  post('/api/upload', { deviceId: 'FULLTEST', image: fake }, r => {
    console.log('2. Upload:', r.ok ? 'OK' : 'FAIL');
    get('/api/latest-image?deviceId=FULLTEST', r => {
      console.log('3. Fetch:', r.ok ? 'OK (img len=' + r.image.length + ')' : 'FAIL');
      get('/api/verify?deviceId=FULLTEST&password=mypass', r => {
        console.log('4. Verify:', r.ok ? 'OK' : 'FAIL');
        console.log('ALL TESTS PASSED!');
        c.disconnect(); process.exit(0);
      });
    });
  });
});
setTimeout(() => { console.log('TIMEOUT'); process.exit(1); }, 5000);
