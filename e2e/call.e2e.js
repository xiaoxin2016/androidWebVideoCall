'use strict';
// End-to-end call test.
//
// Starts the real server binary, opens the web console in one Chromium (the
// "server side" party, on loopback exactly like a user would) and emulates the
// Android client in a second Chromium that connects over TLS to the call port
// via a non-loopback interface address. Both use fake camera/microphone.
// Passes when both sides receive decoded video through the TURN relay.
//
// env:
//   SERVER_BIN          path to the videocall-server binary (required)
//   ANDROID_TRANSPORT   udp | tcp | any   (TURN transport for the emulated phone, default any)
//   VIDEOCALL_WEB_TURN_HOST  passed through to the server (diagnostics only)

const { chromium } = require('playwright');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CONSOLE = 18080;
const CALL = 18443;
const PW = 'e2e-password-123';
const TRANSPORT = process.env.ANDROID_TRANSPORT || 'any';
const FAKE_MEDIA = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'];

let serverLog = '';

function startServer() {
  const bin = process.env.SERVER_BIN;
  if (!bin) throw new Error('SERVER_BIN not set');
  const data = fs.mkdtempSync(path.join(os.tmpdir(), 'vc-e2e-'));
  const p = spawn(bin, ['-data', data, '-listen', `:${CALL}`, '-console', `127.0.0.1:${CONSOLE}`, '-open=false'], {
    env: process.env,
  });
  p.stdout.on('data', (d) => { serverLog += d; });
  p.stderr.on('data', (d) => { serverLog += d; });
  return p;
}

async function waitFor(fn, ms, what) {
  const end = Date.now() + ms;
  for (;;) {
    try { const v = await fn(); if (v) return v; } catch { /* retry */ }
    if (Date.now() > end) throw new Error(`timeout waiting for ${what}`);
    await new Promise((r) => setTimeout(r, 250));
  }
}

async function main() {
  const server = startServer();
  let b1, b2;
  try {
    const info = await waitFor(async () => (await fetch(`http://127.0.0.1:${CONSOLE}/api/info`)).json(), 15000, 'server');
    const ip = (info.localIPs || [])[0];
    if (!ip) throw new Error('no non-loopback IPv4 address on this machine');
    console.log(`server up; phone side will dial ${ip}:${CALL} (${TRANSPORT})`);

    b1 = await chromium.launch({ args: FAKE_MEDIA });
    b2 = await chromium.launch({
      args: [...FAKE_MEDIA, '--ignore-certificate-errors', '--proxy-server=direct://', '--proxy-bypass-list=*'],
    });
    const web = await (await b1.newContext()).newPage();
    const phone = await (await b2.newContext({ ignoreHTTPSErrors: true })).newPage();
    web.on('console', (m) => { if (['warning', 'error'].includes(m.type())) console.log('[web]', m.text()); });
    phone.on('console', (m) => console.log('[phone]', m.text()));

    // console: set the access password like a user would
    await web.goto(`http://localhost:${CONSOLE}/`);
    await web.waitForSelector('#pwForm:not(.hidden)');
    await web.fill('#pwNew', PW);
    await web.fill('#pwConfirm', PW);
    await web.click('#btnPwSave');
    await web.waitForSelector('#pwView:not(.hidden)');
    const webIce = await web.evaluate(() => JSON.stringify(state.iceServers[0].urls));
    console.log('web TURN urls:', webIce);

    // phone: same protocol as the Android app
    await phone.goto(`https://${ip}:${CALL}/`);
    const phoneResult = phone.evaluate(async ({ PW, CALL, ip, TRANSPORT }) => {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480 }, audio: true });
      const ws = new WebSocket(`wss://${ip}:${CALL}/ws`);
      let pc;
      let ice;
      return await new Promise((resolve) => {
        ws.onopen = () => ws.send(JSON.stringify({ type: 'auth', role: 'android', name: 'E2E Phone', password: PW }));
        ws.onmessage = async (ev) => {
          const m = JSON.parse(ev.data);
          if (m.type === 'auth_ok') {
            ice = m.iceServers;
            if (TRANSPORT !== 'any') ice = ice.map((s) => ({ ...s, urls: s.urls.filter((u) => u.endsWith(TRANSPORT)) }));
            console.log('phone TURN urls: ' + JSON.stringify(ice[0].urls));
            ws.send(JSON.stringify({ type: 'call' }));
          } else if (m.type === 'accepted') {
            pc = new RTCPeerConnection({ iceServers: ice, iceTransportPolicy: 'relay' });
            window.pc = pc;
            stream.getTracks().forEach((t) => pc.addTrack(t, stream));
            pc.onicecandidate = (e) => e.candidate && ws.send(JSON.stringify({ type: 'candidate', candidate: e.candidate.toJSON() }));
            pc.onicecandidateerror = (e) => console.log(`ice error ${e.url} ${e.errorCode} ${e.errorText}`);
            pc.onconnectionstatechange = () => {
              if (pc.connectionState === 'connected' || pc.connectionState === 'failed') resolve(pc.connectionState);
            };
            const o = await pc.createOffer();
            await pc.setLocalDescription(o);
            ws.send(JSON.stringify({ type: 'offer', sdp: o.sdp }));
          } else if (m.type === 'answer') {
            await pc.setRemoteDescription({ type: 'answer', sdp: m.sdp });
          } else if (m.type === 'candidate') {
            await pc.addIceCandidate(m.candidate);
          } else if (['rejected', 'call_failed', 'hangup', 'auth_fail'].includes(m.type)) {
            resolve(`${m.type}:${m.reason || ''}`);
          }
        };
        setTimeout(() => resolve('timeout'), 40000);
      });
    }, { PW, CALL, ip, TRANSPORT });

    await web.waitForSelector('#incoming:not(.hidden)', { timeout: 15000 });
    await web.click('#btnAccept');
    const phoneState = await phoneResult;
    console.log('phone connection state:', phoneState);
    if (phoneState !== 'connected') throw new Error(`phone side did not connect: ${phoneState}`);

    const webVideo = await waitFor(() => web.evaluate(() => {
      const v = document.getElementById('remoteVideo');
      return v.videoWidth > 0 && v.currentTime > 0.5 ? [v.videoWidth, v.videoHeight] : null;
    }), 15000, 'video on web side');
    const phoneFrames = await waitFor(() => phone.evaluate(async () => {
      const s = await window.pc.getStats();
      let n = 0;
      s.forEach((r) => { if (r.type === 'inbound-rtp' && r.kind === 'video') n = r.framesDecoded || 0; });
      return n > 10 ? n : null;
    }), 15000, 'video on phone side');
    const relay = await phone.evaluate(async () => {
      const s = await window.pc.getStats();
      let proto = '';
      s.forEach((r) => {
        if (r.type === 'transport' && r.selectedCandidatePairId) {
          const p = s.get(r.selectedCandidatePairId);
          proto = s.get(p.localCandidateId).relayProtocol;
        }
      });
      return proto;
    });
    console.log(`PASS: web video ${webVideo.join('x')}, phone decoded ${phoneFrames} frames, phone relay ${relay}`);
  } finally {
    if (b1) await b1.close();
    if (b2) await b2.close();
    server.kill();
  }
}

main().catch((e) => {
  console.error('FAIL:', e.message);
  console.error('---- server log ----\n' + serverLog);
  process.exit(1);
});
