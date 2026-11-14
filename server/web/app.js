'use strict';

const $ = (id) => document.getElementById(id);

const state = {
  ws: null,
  info: null,
  iceServers: [],
  passwordSet: false,
  editingPassword: false,
  pc: null,
  localStream: null,
  pendingCandidates: [],
  inCall: false,
  ringing: false,
  callStart: 0,
  timers: [],
  reconnectTimer: null,
  stopReconnect: false,
  ring: null,
  peerRelay: '',
  peerName: '',
};

// ---------- signaling ----------

function connect() {
  clearTimeout(state.reconnectTimer);
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${proto}//${location.host}/ws`);
  state.ws = ws;
  ws.onopen = () => ws.send(JSON.stringify({ type: 'auth', role: 'web', name: '服务端' }));
  ws.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    onSignal(m);
  };
  ws.onclose = () => {
    if (state.ws !== ws) return;
    setService(false, '服务未连接');
    setPeer(false);
    if (state.inCall || state.ringing) endCall('与服务端的连接已断开');
    if (!state.stopReconnect) state.reconnectTimer = setTimeout(connect, 2000);
  };
}

function send(obj) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(obj));
}

async function onSignal(m) {
  switch (m.type) {
    case 'auth_ok':
      state.iceServers = m.iceServers || [];
      setService(true, '服务运行中');
      setPeer(m.peerOnline, m.peerName);
      setPasswordState(!!m.passwordSet);
      break;
    case 'auth_fail':
      state.stopReconnect = true;
      setService(false, m.reason === 'busy' ? '另一个控制台正在通话' : '连接被拒绝');
      break;
    case 'kicked':
      state.stopReconnect = true;
      endCall();
      setService(false, '已在其他页面打开');
      toast('控制台已在其他页面打开，本页已断开。刷新可重新连接。', 6000);
      break;
    case 'presence':
      setPeer(m.online, m.name);
      break;
    case 'password_status':
      if (m.error) {
        showPwError(m.error);
      } else {
        setPasswordState(!!m.set);
        if (m.changed) toast(m.set ? '接入密码已更新' : '接入密码已清除');
      }
      $('btnPwSave').disabled = false;
      break;
    case 'incoming':
      onIncoming(m);
      break;
    case 'offer':
      await onOffer(m.sdp);
      break;
    case 'candidate':
      await onRemoteCandidate(m.candidate);
      break;
    case 'netinfo':
      state.peerRelay = m.relay || '';
      break;
    case 'hangup':
      endCall(m.reason === 'disconnected' ? '对方连接已断开' : '对方已挂断');
      break;
  }
}

// ---------- password card ----------

function setPasswordState(set) {
  state.passwordSet = set;
  state.editingPassword = false;
  const badge = $('pwBadge');
  badge.textContent = set ? '已设置' : '未设置';
  badge.className = 'badge ' + (set ? 'ok' : 'warn');
  $('pwCard').classList.toggle('warn', !set);
  $('pwAlert').classList.toggle('hidden', set);
  $('pwView').classList.toggle('hidden', !set);
  $('pwForm').classList.toggle('hidden', set);
  $('btnPwCancel').classList.toggle('hidden', true);
  $('pwForm').reset();
  resetPwHint();
  $('idleHint').textContent = set
    ? '保持此页面打开。来电时会响铃并弹出接听提示，接听后才会开启摄像头和麦克风。'
    : '建议先在右侧设置接入密码，再把密码告诉需要呼叫你的人。';
}

function resetPwHint() {
  const h = $('pwHint');
  h.textContent = '至少 8 位。服务端只保存密码的加盐哈希（argon2id）。';
  h.classList.remove('error');
}

function showPwError(msg) {
  const h = $('pwHint');
  h.textContent = msg;
  h.classList.add('error');
  $('btnPwSave').disabled = false;
}

function savePassword(e) {
  e.preventDefault();
  const pw = $('pwNew').value;
  if ([...pw].length < 8) return showPwError('密码至少 8 位');
  if (pw !== $('pwConfirm').value) return showPwError('两次输入的密码不一致');
  $('btnPwSave').disabled = true;
  send({ type: 'set_password', password: pw });
}

function editPassword() {
  state.editingPassword = true;
  $('pwView').classList.add('hidden');
  $('pwForm').classList.remove('hidden');
  $('btnPwCancel').classList.remove('hidden');
  resetPwHint();
  $('pwNew').focus();
}

function clearPassword() {
  if (!confirm('清除后任何能访问通话端口的人都可以向你发起呼叫，确定清除？')) return;
  send({ type: 'clear_password' });
}

// ---------- call flow ----------

function onIncoming(m) {
  state.ringing = true;
  state.peerName = m.name || 'Android';
  $('inName').textContent = state.peerName;
  $('inAvatar').textContent = initials(state.peerName);
  $('inMeta').textContent = `来自 ${m.ip || '未知地址'} · ${m.verified ? '已通过密码验证' : '未设置接入密码'}`;
  $('incoming').classList.remove('hidden');
  $('btnAccept').focus();
  startRing();
  notify(`${state.peerName} 正在呼叫你`);
}

async function accept() {
  stopRing();
  $('incoming').classList.add('hidden');
  state.ringing = false;
  try {
    await startLocalMedia();
  } catch (e) {
    toast('无法打开摄像头/麦克风：' + e.message, 6000);
    send({ type: 'reject' });
    return;
  }
  state.inCall = true;
  state.peerRelay = '';
  createPeerConnection();
  send({ type: 'accept' });
  $('callPeer').textContent = state.peerName;
  $('callAvatar').textContent = initials(state.peerName);
  $('callTimer').textContent = '00:00';
  $('netInfo').textContent = '正在建立连接…';
  $('devPopover').appendChild($('devBody'));
  document.body.classList.add('incall');
}

function reject() {
  stopRing();
  $('incoming').classList.add('hidden');
  state.ringing = false;
  send({ type: 'reject' });
}

function createPeerConnection() {
  const pc = new RTCPeerConnection({
    iceServers: state.iceServers,
    iceTransportPolicy: 'relay', // all media through the embedded TURN relay
    bundlePolicy: 'max-bundle',
  });
  state.pc = pc;
  state.pendingCandidates = [];

  pc.onicecandidate = (e) => {
    if (e.candidate) send({ type: 'candidate', candidate: e.candidate.toJSON() });
  };
  pc.ontrack = (e) => {
    const v = $('remoteVideo');
    if (!v.srcObject) v.srcObject = e.streams[0] || new MediaStream();
    if (!e.streams[0]) v.srcObject.addTrack(e.track);
    v.classList.remove('hidden');
  };
  pc.onconnectionstatechange = () => {
    switch (pc.connectionState) {
      case 'connected':
        if (!state.callStart) {
          state.callStart = Date.now();
          state.timers.push(setInterval(updateTimer, 1000));
          state.timers.push(setInterval(updateStats, 2000));
          updateStats();
        }
        break;
      case 'disconnected':
        $('netInfo').textContent = '网络中断，正在恢复…';
        break;
      case 'failed':
        send({ type: 'hangup' });
        endCall('媒体连接失败，请检查通话端口的转发与防火墙');
        break;
    }
  };
}

async function onOffer(sdp) {
  const pc = state.pc;
  if (!pc) return;
  await pc.setRemoteDescription({ type: 'offer', sdp });
  for (const track of state.localStream.getTracks()) pc.addTrack(track, state.localStream);
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  send({ type: 'answer', sdp: pc.localDescription.sdp });
  for (const c of state.pendingCandidates) await pc.addIceCandidate(c).catch(() => {});
  state.pendingCandidates = [];
}

async function onRemoteCandidate(c) {
  if (!state.pc || !c) return;
  if (!state.pc.remoteDescription) {
    state.pendingCandidates.push(c);
    return;
  }
  await state.pc.addIceCandidate(c).catch((e) => console.warn('addIceCandidate', e));
}

function hangup() {
  send({ type: 'hangup' });
  endCall('通话已结束');
}

function endCall(msg) {
  stopRing();
  $('incoming').classList.add('hidden');
  state.ringing = false;
  state.inCall = false;
  state.timers.forEach(clearInterval);
  state.timers = [];
  state.callStart = 0;
  lastBytes = 0; lastTs = 0;
  if (state.pc) {
    state.pc.ontrack = state.pc.onicecandidate = state.pc.onconnectionstatechange = null;
    state.pc.close();
    state.pc = null;
  }
  if (state.localStream) {
    state.localStream.getTracks().forEach((t) => t.stop());
    state.localStream = null;
  }
  const rv = $('remoteVideo');
  rv.srcObject = null;
  rv.classList.add('hidden');
  $('localVideo').srcObject = null;
  document.body.classList.remove('incall');
  $('devCard').appendChild($('devBody'));
  $('devPopover').classList.add('hidden');
  $('btnDevices').setAttribute('aria-expanded', 'false');
  setPressed($('btnMute'), false, '静音', '取消静音');
  setPressed($('btnCam'), false, '关闭摄像头', '打开摄像头');
  if (msg) toast(msg);
}

// ---------- media ----------

async function startLocalMedia() {
  const camId = $('camSelect').value;
  const micId = $('micSelect').value;
  state.localStream = await navigator.mediaDevices.getUserMedia({
    video: {
      deviceId: camId ? { exact: camId } : undefined,
      width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 },
    },
    audio: {
      deviceId: micId ? { exact: micId } : undefined,
      echoCancellation: true, noiseSuppression: true, autoGainControl: true,
    },
  });
  $('localVideo').srcObject = state.localStream;
  await refreshDevices();
}

async function refreshDevices() {
  if (!navigator.mediaDevices?.enumerateDevices) return;
  const devices = await navigator.mediaDevices.enumerateDevices();
  fillSelect($('camSelect'), devices.filter((d) => d.kind === 'videoinput'), '摄像头',
    state.localStream?.getVideoTracks()[0]?.getSettings().deviceId);
  fillSelect($('micSelect'), devices.filter((d) => d.kind === 'audioinput'), '麦克风',
    state.localStream?.getAudioTracks()[0]?.getSettings().deviceId);
}

function fillSelect(sel, list, label, current) {
  const prev = current || sel.value;
  sel.innerHTML = '';
  if (!list.length) {
    const o = document.createElement('option');
    o.textContent = `未检测到${label}`;
    o.value = '';
    sel.appendChild(o);
    return;
  }
  list.forEach((d, i) => {
    const o = document.createElement('option');
    o.value = d.deviceId;
    o.textContent = d.label || `${label} ${i + 1}`;
    if (d.deviceId === prev) o.selected = true;
    sel.appendChild(o);
  });
}

async function switchDevice(kind) {
  if (!state.localStream) return;
  const id = kind === 'video' ? $('camSelect').value : $('micSelect').value;
  if (!id) return;
  const constraints = kind === 'video'
    ? { video: { deviceId: { exact: id }, width: { ideal: 1280 }, height: { ideal: 720 } } }
    : { audio: { deviceId: { exact: id }, echoCancellation: true, noiseSuppression: true } };
  try {
    const s = await navigator.mediaDevices.getUserMedia(constraints);
    const newTrack = kind === 'video' ? s.getVideoTracks()[0] : s.getAudioTracks()[0];
    const old = kind === 'video' ? state.localStream.getVideoTracks()[0] : state.localStream.getAudioTracks()[0];
    newTrack.enabled = old ? old.enabled : true;
    const sender = state.pc?.getSenders().find((x) => x.track && x.track.kind === kind);
    if (sender) await sender.replaceTrack(newTrack);
    if (old) { state.localStream.removeTrack(old); old.stop(); }
    state.localStream.addTrack(newTrack);
  } catch (e) {
    toast('切换设备失败：' + e.message);
  }
}

function toggleTrack(kind, btn, onLabel, offLabel) {
  if (!state.localStream) return;
  const tracks = kind === 'audio' ? state.localStream.getAudioTracks() : state.localStream.getVideoTracks();
  const disabledNow = tracks.every((t) => !t.enabled);
  tracks.forEach((t) => { t.enabled = disabledNow; });
  setPressed(btn, !disabledNow, onLabel, offLabel);
}

function setPressed(btn, pressed, onLabel, offLabel) {
  btn.setAttribute('aria-pressed', String(pressed));
  const label = pressed ? offLabel : onLabel;
  btn.setAttribute('aria-label', label);
  btn.title = label;
}

// ---------- UI helpers ----------

function initials(name) {
  const s = (name || '').trim();
  if (!s) return 'A';
  const words = s.split(/\s+/);
  if (/^[\x00-\x7F]/.test(s)) {
    return words.length > 1 ? (words[0][0] + words[1][0]).toUpperCase() : s.slice(0, 2).toUpperCase();
  }
  return [...s][0];
}

function setService(on, text) {
  $('svcText').textContent = text;
  $('svcChip').querySelector('.dot').classList.toggle('off', !on);
}

function setPeer(online, name) {
  $('peerPill').classList.toggle('on', !!online);
  $('peerText').textContent = online ? `Android 在线${name ? ' · ' + name : ''}` : 'Android 未连接';
}

let toastTimer;
function toast(text, ms = 3500) {
  const t = $('toast');
  t.textContent = text;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), ms);
}

function updateTimer() {
  const s = Math.floor((Date.now() - state.callStart) / 1000);
  const h = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
  const ss = String(s % 60).padStart(2, '0');
  $('callTimer').textContent = h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

let lastBytes = 0, lastTs = 0;
async function updateStats() {
  if (!state.pc) return;
  const stats = await state.pc.getStats();
  let pair, inboundBytes = 0;
  stats.forEach((r) => {
    if (r.type === 'transport' && r.selectedCandidatePairId) pair = stats.get(r.selectedCandidatePairId);
    if (r.type === 'inbound-rtp' && r.kind === 'video') inboundBytes = r.bytesReceived;
  });
  const parts = [];
  parts.push(state.peerRelay ? `${state.peerRelay.toUpperCase()} 中继` : '中继');
  if (pair && pair.currentRoundTripTime !== undefined) parts.push(`${Math.round(pair.currentRoundTripTime * 1000)} ms`);
  const now = Date.now();
  if (lastTs && inboundBytes >= lastBytes) {
    const kbps = ((inboundBytes - lastBytes) * 8) / (now - lastTs);
    parts.push(kbps >= 1000 ? `${(kbps / 1000).toFixed(1)} Mbps` : `${Math.round(kbps)} kbps`);
  }
  lastBytes = inboundBytes; lastTs = now;
  $('netInfo').textContent = parts.join(' · ');
}

function startRing() {
  stopRing();
  try {
    const ctx = new AudioContext();
    const beep = () => {
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.frequency.value = 440;
      g.gain.setValueAtTime(0.0001, ctx.currentTime);
      g.gain.exponentialRampToValueAtTime(0.2, ctx.currentTime + 0.05);
      g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.8);
      o.connect(g).connect(ctx.destination);
      o.start();
      o.stop(ctx.currentTime + 0.85);
    };
    beep();
    state.ring = { ctx, timer: setInterval(beep, 2000) };
  } catch { /* autoplay policy may block sound; the dialog is still shown */ }
}

function stopRing() {
  if (!state.ring) return;
  clearInterval(state.ring.timer);
  state.ring.ctx.close().catch(() => {});
  state.ring = null;
}

function notify(text) {
  if (!('Notification' in window) || document.hasFocus()) return;
  if (Notification.permission === 'granted') new Notification('视频通话', { body: text });
}

function formatFingerprint(fp) {
  const parts = (fp || '').split(':');
  const lines = [];
  for (let i = 0; i < parts.length; i += 8) lines.push(parts.slice(i, i + 8).join(':'));
  return lines.join('\n');
}

// ---------- init ----------

async function init() {
  $('btnAccept').onclick = accept;
  $('btnReject').onclick = reject;
  $('btnHangup').onclick = hangup;
  $('btnMute').onclick = () => toggleTrack('audio', $('btnMute'), '静音', '取消静音');
  $('btnCam').onclick = () => toggleTrack('video', $('btnCam'), '关闭摄像头', '打开摄像头');
  $('btnDevices').onclick = () => {
    const pop = $('devPopover');
    const open = pop.classList.toggle('hidden') === false;
    $('btnDevices').setAttribute('aria-expanded', String(open));
  };
  $('camSelect').onchange = () => switchDevice('video');
  $('micSelect').onchange = () => switchDevice('audio');
  $('pwForm').addEventListener('submit', savePassword);
  $('btnPwEdit').onclick = editPassword;
  $('btnPwCancel').onclick = () => setPasswordState(state.passwordSet);
  $('btnPwClear').onclick = clearPassword;
  $('btnCopyFp').onclick = async () => {
    try {
      await navigator.clipboard.writeText(state.info.fingerprint);
      $('btnCopyFp').querySelector('span').textContent = '已复制';
      setTimeout(() => { $('btnCopyFp').querySelector('span').textContent = '复制'; }, 1500);
    } catch {
      toast('复制失败，请手动选择指纹');
    }
  };

  if ('Notification' in window && Notification.permission === 'default') {
    document.addEventListener('click', () => Notification.requestPermission(), { once: true });
  }
  if (!window.isSecureContext || !navigator.mediaDevices) {
    toast('当前页面不是安全上下文，无法使用摄像头。请用 http://localhost 打开控制台。', 10000);
  }

  $('consoleAddr').textContent = location.host;
  const info = await fetch('api/info').then((r) => r.json());
  state.info = info;
  $('version').textContent = info.version;
  $('fingerprint').textContent = formatFingerprint(info.fingerprint);
  $('infoCall').textContent = `${info.udp ? 'TCP + UDP' : '仅 TCP'} ${info.callPort}`;
  $('alertPort').textContent = String(info.callPort);
  $('infoConsole').textContent = String(info.consolePort);
  $('infoIPs').textContent = (info.localIPs || []).join('\n') || '—';

  connect();
  refreshDevices().catch(() => {});
}

window.addEventListener('beforeunload', () => {
  if (state.inCall || state.ringing) send({ type: 'hangup' });
});

init();
