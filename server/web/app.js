'use strict';

const $ = (id) => document.getElementById(id);

const state = {
  ws: null,
  password: '',
  passwordRequired: false,
  iceServers: [],
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
};

const REASONS = {
  bad_password: '密码错误',
  rate_limited: '尝试次数过多，请稍后再试',
  busy: '已有另一个 Web 端正在通话中',
  bad_role: '协议错误',
};

// ---------- signaling ----------

function connect() {
  clearTimeout(state.reconnectTimer);
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${proto}//${location.host}/ws`);
  state.ws = ws;

  ws.onopen = () => {
    ws.send(JSON.stringify({ type: 'auth', role: 'web', name: '服务端', password: state.password }));
  };
  ws.onmessage = (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    onSignal(m);
  };
  ws.onclose = () => {
    if (state.ws !== ws) return;
    setPill('sigStatus', false, '信令未连接');
    setPill('peerStatus', false, 'Android 离线');
    if (state.inCall || state.ringing) endCall('与服务端的连接已断开');
    if (!state.stopReconnect) state.reconnectTimer = setTimeout(connect, 3000);
  };
}

function send(obj) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(obj));
}

async function onSignal(m) {
  switch (m.type) {
    case 'auth_ok':
      state.iceServers = m.iceServers || [];
      setPill('sigStatus', true, '信令已连接');
      setPeer(m.peerOnline, m.peerName);
      $('login').classList.add('hidden');
      $('stage').classList.remove('hidden');
      $('fingerprint').textContent = m.fingerprint;
      break;
    case 'auth_fail':
      state.stopReconnect = m.reason === 'bad_password';
      showLogin(REASONS[m.reason] || m.reason);
      if (m.reason === 'rate_limited' || m.reason === 'busy') {
        state.stopReconnect = true;
      }
      break;
    case 'kicked':
      state.stopReconnect = true;
      endCall();
      setMsg('已在其他页面登录，本页面已断开。刷新页面可重新连接。');
      break;
    case 'presence':
      setPeer(m.online, m.name);
      break;
    case 'incoming':
      onIncoming(m.name);
      break;
    case 'offer':
      await onOffer(m.sdp);
      break;
    case 'candidate':
      await onRemoteCandidate(m.candidate);
      break;
    case 'hangup':
      endCall(m.reason === 'disconnected' ? '对方连接已断开' : '对方已挂断');
      break;
  }
}

// ---------- call flow ----------

function onIncoming(name) {
  state.ringing = true;
  $('incomingName').textContent = name || 'Android';
  $('incoming').classList.remove('hidden');
  startRing();
  notify(`${name || 'Android'} 正在呼叫你`);
}

async function accept() {
  stopRing();
  $('incoming').classList.add('hidden');
  state.ringing = false;
  try {
    await startLocalMedia();
  } catch (e) {
    setMsg('无法打开摄像头/麦克风: ' + e.message);
    send({ type: 'reject' });
    return;
  }
  state.inCall = true;
  createPeerConnection();
  send({ type: 'accept' });
  $('idleOverlay').classList.add('hidden');
  $('controls').classList.remove('hidden');
  $('callInfo').classList.remove('hidden');
  $('callPeer').textContent = $('incomingName').textContent;
  setMsg('正在建立连接…');
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
    // Everything goes through the embedded TURN relay on this server.
    iceTransportPolicy: 'relay',
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
  };
  pc.onconnectionstatechange = () => {
    switch (pc.connectionState) {
      case 'connected':
        setMsg('');
        if (!state.callStart) {
          state.callStart = Date.now();
          state.timers.push(setInterval(updateTimer, 1000));
          state.timers.push(setInterval(updateStats, 2000));
        }
        break;
      case 'disconnected':
        setMsg('网络中断，正在尝试恢复…');
        break;
      case 'failed':
        send({ type: 'hangup' });
        endCall('媒体连接失败（请检查端口转发与防火墙）');
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
  if (state.pc) {
    state.pc.ontrack = state.pc.onicecandidate = state.pc.onconnectionstatechange = null;
    state.pc.close();
    state.pc = null;
  }
  if (state.localStream) {
    state.localStream.getTracks().forEach((t) => t.stop());
    state.localStream = null;
  }
  $('remoteVideo').srcObject = null;
  const lv = $('localVideo');
  lv.srcObject = null;
  lv.removeAttribute('data-active');
  $('idleOverlay').classList.remove('hidden');
  $('controls').classList.add('hidden');
  $('callInfo').classList.add('hidden');
  $('netInfo').textContent = '';
  $('btnMute').textContent = '静音';
  $('btnCam').textContent = '关闭摄像头';
  if (msg) setMsg(msg);
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
  const lv = $('localVideo');
  lv.srcObject = state.localStream;
  lv.setAttribute('data-active', '');
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
  sel.innerHTML = '';
  list.forEach((d, i) => {
    const o = document.createElement('option');
    o.value = d.deviceId;
    o.textContent = d.label || `${label} ${i + 1}`;
    if (d.deviceId === current) o.selected = true;
    sel.appendChild(o);
  });
}

async function switchDevice(kind) {
  if (!state.localStream) return;
  const id = kind === 'video' ? $('camSelect').value : $('micSelect').value;
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
    setMsg('切换设备失败: ' + e.message);
  }
}

function toggleTrack(kind, btn, onText, offText) {
  if (!state.localStream) return;
  const tracks = kind === 'audio' ? state.localStream.getAudioTracks() : state.localStream.getVideoTracks();
  const enabled = !tracks.every((t) => !t.enabled);
  tracks.forEach((t) => { t.enabled = !enabled; });
  btn.textContent = enabled ? offText : onText;
}

// ---------- UI helpers ----------

function setPill(id, on, text) {
  const el = $(id);
  el.textContent = text;
  el.classList.toggle('on', on);
  el.classList.toggle('off', !on);
}

function setPeer(online, name) {
  setPill('peerStatus', !!online, online ? `Android 在线${name ? '：' + name : ''}` : 'Android 离线');
}

function setMsg(t) { $('stageMsg').textContent = t || ''; }

function showLogin(err) {
  $('stage').classList.add('hidden');
  $('login').classList.remove('hidden');
  $('loginError').textContent = err || '';
  $('password').focus();
}

function updateTimer() {
  const s = Math.floor((Date.now() - state.callStart) / 1000);
  $('callTimer').textContent =
    `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

let lastBytes = 0, lastTs = 0;
async function updateStats() {
  if (!state.pc) return;
  const stats = await state.pc.getStats();
  let pair, local, inboundBytes = 0;
  stats.forEach((r) => {
    if (r.type === 'transport' && r.selectedCandidatePairId) pair = stats.get(r.selectedCandidatePairId);
    if (r.type === 'inbound-rtp' && r.kind === 'video') inboundBytes = r.bytesReceived;
  });
  if (pair) local = stats.get(pair.localCandidateId);
  const parts = [];
  if (local) parts.push(`中继 ${(local.relayProtocol || local.protocol || '').toUpperCase()}`);
  if (pair && pair.currentRoundTripTime !== undefined) parts.push(`RTT ${Math.round(pair.currentRoundTripTime * 1000)}ms`);
  const now = Date.now();
  if (lastTs && inboundBytes >= lastBytes) {
    parts.push(`${Math.round(((inboundBytes - lastBytes) * 8) / (now - lastTs))} kbps`);
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
  } catch { /* autoplay policy may block sound; the modal is still shown */ }
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

// ---------- init ----------

async function init() {
  $('loginForm').addEventListener('submit', (e) => {
    e.preventDefault();
    state.password = $('password').value;
    state.stopReconnect = false;
    $('loginError').textContent = '';
    if (state.ws) { state.ws.onclose = null; state.ws.close(); }
    connect();
  });
  $('btnAccept').onclick = accept;
  $('btnReject').onclick = reject;
  $('btnHangup').onclick = hangup;
  $('btnMute').onclick = () => toggleTrack('audio', $('btnMute'), '静音', '取消静音');
  $('btnCam').onclick = () => toggleTrack('video', $('btnCam'), '关闭摄像头', '打开摄像头');
  $('camSelect').onchange = () => switchDevice('video');
  $('micSelect').onchange = () => switchDevice('audio');

  if ('Notification' in window && Notification.permission === 'default') {
    document.addEventListener('click', () => Notification.requestPermission(), { once: true });
  }

  if (!window.isSecureContext || !navigator.mediaDevices) {
    setMsg('当前页面不是安全上下文，无法使用摄像头。请通过 http://localhost 或 https:// 访问。');
  }

  const info = await fetch('api/info').then((r) => r.json());
  $('version').textContent = info.version;
  $('fingerprint').textContent = info.fingerprint;
  state.passwordRequired = info.passwordRequired;
  if (info.passwordRequired) {
    showLogin();
  } else {
    $('stage').classList.remove('hidden');
    connect();
  }
  refreshDevices().catch(() => {});
}

window.addEventListener('beforeunload', () => {
  if (state.inCall || state.ringing) send({ type: 'hangup' });
});

init();
