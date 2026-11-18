const express = require('express');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const multer = require('multer');

const app = express();
const server = http.createServer(app);
const PORT = Number(process.env.PORT || 3000);
const ADMIN_PASSWORD = String(process.env.ADMIN_PASSWORD || '').trim();
if (ADMIN_PASSWORD.length < 20) {
  console.error(`ADMIN_PASSWORD is missing or too short. Received length: ${ADMIN_PASSWORD.length} characters; expected at least 20.`);
  process.exit(1);
}

app.disable('x-powered-by');
app.use(express.json({ limit: '5mb' }));
const dataDir = path.join(__dirname, 'data');
const uploadsDir = path.join(dataDir, 'uploads');
const stateFile = path.join(dataDir, 'state.json');
fs.mkdirSync(uploadsDir, { recursive: true });

const devices = new Map();
const commands = new Map();
const transfers = new Map();
const upload = multer({ dest: uploadsDir, limits: { fileSize: 250 * 1024 * 1024 } });
const hash = value => crypto.createHash('sha256').update(String(value)).digest('hex');
function safeEqual(a, b) {
  const x = Buffer.from(hash(a));
  const y = Buffer.from(hash(b));
  return crypto.timingSafeEqual(x, y);
}
function saveState() {
  const state = {
    devices: [...devices.values()],
    commands: [...commands.values()],
    transfers: [...transfers.values()].map(({ savedPath, ...t }) => ({ ...t, savedPath }))
  };
  const tmp = `${stateFile}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state));
  fs.renameSync(tmp, stateFile);
}
function loadState() {
  if (!fs.existsSync(stateFile)) return;
  try {
    const s = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    for (const d of s.devices || []) devices.set(d.deviceId, d);
    for (const c of s.commands || []) commands.set(c.id, c);
    for (const t of s.transfers || []) transfers.set(t.id, t);
  } catch (e) {
    console.error('Could not load persisted state:', e.message);
  }
}
loadState();

function admin(req, res, next) {
  const token = (req.headers.authorization || '').replace(/^Bearer /, '');
  if (!safeEqual(token, ADMIN_PASSWORD)) return res.status(401).json({ error: 'unauthorized' });
  next();
}
function phone(req, res, next) {
  const token = (req.headers.authorization || '').replace(/^Bearer /, '');
  const tokenHash = hash(token);
  const device = [...devices.values()].find(d => d.tokenHash === tokenHash && d.approved && !d.revoked);
  if (!device) return res.status(401).json({ error: 'device_not_approved_or_revoked' });
  req.device = device;
  next();
}
function createCommand(deviceId, type, extra = {}) {
  const id = crypto.randomBytes(12).toString('hex');
  const command = { id, deviceId, type, ...extra, status: 'pending', createdAt: Date.now() };
  commands.set(id, command);
  saveState();
  return command;
}

app.use(express.static(path.join(__dirname, 'public')));
app.get('/health', (_req, res) => res.json({ ok: true }));
app.post('/api/admin/login', (req, res) => {
  if (!safeEqual(req.body?.password || '', ADMIN_PASSWORD)) return res.status(401).json({ error: 'unauthorized' });
  res.json({ ok: true });
});
app.get('/api/admin/devices', admin, (_req, res) => {
  res.json([...devices.values()].map(({ tokenHash, ...device }) => device));
});
app.post('/api/admin/devices/:id/approve', admin, (req, res) => {
  const device = devices.get(req.params.id);
  if (!device || device.revoked) return res.status(404).json({ error: 'not_found' });
  device.approved = true;
  device.approvedAt = Date.now();
  saveState();
  res.json({ ok: true });
});
app.post('/api/admin/devices/:id/revoke', admin, (req, res) => {
  const device = devices.get(req.params.id);
  if (!device) return res.status(404).json({ error: 'not_found' });
  device.revoked = true;
  device.tokenHash = '';
  saveState();
  res.json({ ok: true });
});
app.post('/api/admin/devices/:id/scan', admin, (req, res) => {
  const device = devices.get(req.params.id);
  if (!device || !device.approved || device.revoked) return res.status(404).json({ error: 'device_not_available' });
  const command = createCommand(req.params.id, 'list');
  res.json({ commandId: command.id });
});
app.get('/api/admin/devices/:id/files', admin, (req, res) => {
  const device = devices.get(req.params.id);
  if (!device) return res.status(404).json({ error: 'not_found' });
  res.json({ files: device.files || [], updatedAt: device.filesUpdatedAt || null });
});
app.post('/api/admin/devices/:id/transfers', admin, (req, res) => {
  const device = devices.get(req.params.id);
  if (!device || !device.approved || device.revoked) return res.status(404).json({ error: 'device_not_available' });
  const requestedPath = req.body?.path;
  const knownFile = (device.files || []).find(file => file.path === requestedPath);
  if (!knownFile) return res.status(400).json({ error: 'file_not_in_latest_device_listing' });
  const command = createCommand(req.params.id, 'transfer', { path: knownFile.path });
  transfers.set(command.id, { id: command.id, deviceId: req.params.id, name: path.basename(knownFile.path), status: 'queued', createdAt: command.createdAt });
  saveState();
  res.json({ transferId: command.id, status: 'queued' });
});
app.post('/api/admin/transfers/:id/cancel', admin, (req, res) => {
  const transfer = transfers.get(req.params.id);
  const command = commands.get(req.params.id);
  if (!transfer || !command || !['queued', 'delivered'].includes(transfer.status)) return res.status(409).json({ error: 'transfer_not_cancellable' });
  transfer.status = 'cancelled';
  transfer.cancelledAt = Date.now();
  command.status = 'cancelled';
  saveState();
  res.json({ ok: true });
});
app.get('/api/admin/transfers', admin, (_req, res) => res.json([...transfers.values()]));
app.get('/api/admin/transfers/:id/download', admin, (req, res) => {
  const transfer = transfers.get(req.params.id);
  if (!transfer || transfer.status !== 'complete' || !transfer.savedPath || !fs.existsSync(transfer.savedPath)) return res.status(404).json({ error: 'not_ready' });
  res.download(transfer.savedPath, transfer.name);
});

// Enrollment is open, but a newly enrolled device cannot list or transfer files until an administrator approves it.
app.post('/api/phone/enroll', (req, res) => {
  const { deviceId, firstName, lastName, major } = req.body || {};
  if (![deviceId, firstName, lastName, major].every(v => typeof v === 'string' && v.trim().length > 0 && v.length < 160)) return res.status(400).json({ error: 'invalid_profile' });
  if (devices.has(deviceId)) return res.status(409).json({ error: 'device_already_registered' });
  const token = crypto.randomBytes(32).toString('hex');
  devices.set(deviceId, { deviceId, firstName: firstName.trim(), lastName: lastName.trim(), major: major.trim(), tokenHash: hash(token), approved: false, revoked: false, pairedAt: Date.now(), lastSeen: Date.now(), files: [] });
  saveState();
  res.json({ deviceId, token, approvalRequired: true });
});
app.get('/api/phone/commands', phone, (req, res) => {
  req.device.lastSeen = Date.now();
  const pending = [...commands.values()].filter(c => c.deviceId === req.device.deviceId && c.status === 'pending');
  for (const command of pending) command.status = 'delivered';
  saveState();
  res.json(pending);
});
app.post('/api/phone/commands/:id/result', phone, (req, res) => {
  const command = commands.get(req.params.id);
  if (!command || command.deviceId !== req.device.deviceId || command.type !== 'list' || command.status === 'cancelled') return res.status(404).json({ error: 'not_found' });
  req.device.files = (Array.isArray(req.body.files) ? req.body.files : []).slice(0, 3000);
  req.device.filesUpdatedAt = Date.now();
  command.status = 'complete';
  saveState();
  res.json({ ok: true });
});
app.post('/api/phone/transfers/:id/file', phone, upload.single('file'), (req, res) => {
  const command = commands.get(req.params.id);
  const transfer = transfers.get(req.params.id);
  if (!command || !transfer || command.deviceId !== req.device.deviceId || command.status === 'cancelled' || transfer.status === 'cancelled' || !req.file) {
    if (req.file?.path && fs.existsSync(req.file.path)) fs.unlinkSync(req.file.path);
    return res.status(403).json({ error: 'invalid_or_cancelled_transfer' });
  }
  const safeName = path.basename(req.file.originalname).replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 180) || 'download.bin';
  const destination = path.join(uploadsDir, `${crypto.randomBytes(12).toString('hex')}-${safeName}`);
  fs.renameSync(req.file.path, destination);
  transfer.savedPath = destination;
  transfer.status = 'complete';
  transfer.completedAt = Date.now();
  command.status = 'complete';
  saveState();
  res.json({ ok: true });
});
app.post('/api/phone/revoke', phone, (req, res) => {
  req.device.revoked = true;
  req.device.tokenHash = '';
  saveState();
  res.json({ ok: true });
});

server.listen(PORT, '0.0.0.0', () => console.log(`Alzahra server listening on ${PORT}`));
