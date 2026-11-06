/* Asphalt detector: a simplified version of the SDK's spike-dip check, fed by the
   phone's motion sensor or by replaying synthetic curves. Shared by / and /developers.
   Put data-plain on #try for plain-language messages with no units (buyer homepage). */
(() => {
  const g = (t, c, w) => Math.exp(-Math.pow((t - c) / w, 2));

  /* road-normal acceleration (m/s²) against time (ms) */
  const pothole = t => {
    let z = 9.81 + .15 * Math.sin(t * .21) + .08 * Math.sin(t * .57);
    z += -3.3 * g(t, 182, 16) + 6.2 * g(t, 232, 15);
    if (t > 255) z += 1.4 * Math.exp(-(t - 255) / 30) * Math.sin((t - 255) / 9);
    return z;
  };
  const bump = t => {
    let z = 9.81 + .15 * Math.sin(t * .21) + .08 * Math.sin(t * .57);
    z += 5.4 * g(t, 175, 16) - 3.1 * g(t, 228, 17);
    if (t > 250) z += 1.1 * Math.exp(-(t - 250) / 30) * Math.sin((t - 250) / 9);
    return z;
  };
  const rough = t => {
    const env = Math.max(0, Math.min(1, (t - 70) / 40, (340 - t) / 40));
    return 9.81 + .12 * Math.sin(t * .21) + env * (1.3 * Math.sin(t * .31) + .9 * Math.sin(t * .83 + 1) + .6 * Math.sin(t * 1.7 + 2));
  };
  window.AsphaltCurves = { g, pothole, bump, rough };

  const tryEl = document.getElementById('try');
  if (!tryEl) return;
  const plain = tryEl.hasAttribute('data-plain');
  const W = 600, H = 240, SPAN = 2000, STEP = 20;
  const ty = z => H - 16 - ((Math.max(4, Math.min(18, z)) - 4) / 14) * (H - 32);
  const $t = id => document.getElementById(id);
  const html = (id, v) => { const el = $t(id); if (el) el.innerHTML = v; };
  const status = $t('tryStatus'), countEl = $t('tryCount');
  const touch = matchMedia('(pointer:coarse)').matches && 'DeviceMotionEvent' in window;
  tryEl.querySelectorAll('[data-touch]').forEach(el => { el.hidden = !touch; });
  tryEl.querySelectorAll('[data-desk]').forEach(el => { el.hidden = touch; });

  let grid = '';
  for (let v = 6; v <= 18; v += 4) grid += `<line class="tg" x1="0" x2="${W}" y1="${ty(v).toFixed(1)}" y2="${ty(v).toFixed(1)}"/>` + (plain ? '' : `<text class="tax" x="6" y="${(ty(v) - 4).toFixed(1)}">${v}</text>`);
  for (let i = 1; i < 4; i++) grid += `<line class="tg" x1="${i * W / 4}" x2="${i * W / 4}" y1="0" y2="${H}"/><text class="tax" x="${i * W / 4 + 4}" y="${H - 4}">−${(SPAN * (4 - i) / 4000).toFixed(1)} s</text>`;
  $t('tryGrid').innerHTML = grid;

  let thr = 4, base = 9.81, samples = [], marks = [], pending = null, lastHit = -1e9, count = 0;
  let device = false, play = null, clock = performance.now(), statusTimer = 0, readAt = 0;
  const unit = v => `${v.toFixed(2)}<small>m/s²</small>`;
  const idle = () => device ? 'Listening · your phone' : 'Listening · flat road';

  const say = (text, hit) => {
    status.textContent = text; status.classList.toggle('hit', !!hit);
    $t('rClass').classList.toggle('hit', !!hit);
    clearTimeout(statusTimer);
    if (hit) statusTimer = setTimeout(() => say(idle()), 2600);
  };
  const plainSay = {
    Pothole: 'Pothole. The road dropped away first, then the wheel hit the far edge.',
    'Speed bump': 'Speed bump. The wheel rose first, then settled back down.',
    Unknown: 'A jolt with no dip first. Logged as unknown, not as a pothole.'
  };
  const classify = () => {
    const win = samples.filter(([t]) => t >= pending.t - 400 && t <= pending.t + 250);
    let peak = win[0], low = win[0];
    for (const p of win) { if (p[1] > peak[1]) peak = p; if (p[1] < low[1]) low = p; }
    const dipped = base - low[1] >= 1.5;
    const kind = !dipped ? 'Unknown' : low[0] < peak[0] ? 'Pothole' : 'Speed bump';
    marks.push([peak[0], peak[1]]);
    lastHit = peak[0]; pending = null; countEl.textContent = ++count;
    html('rPeak', unit(peak[1])); $t('rClass').textContent = kind;
    say(plain ? plainSay[kind]
      : `${kind === 'Unknown' ? 'Jolt without a dip · logged as unknown' : kind} · peak ${peak[1].toFixed(1)} m/s²${dipped ? ` · dip ${low[1].toFixed(1)}` : ''}`, true);
  };
  const push = (t, z) => {
    samples.push([t, z]);
    while (samples.length && samples[0][0] < t - SPAN - 200) samples.shift();
    if (!pending && Math.abs(z - base) < 2) base += (z - base) * 0.02;
    if (!pending && z > base + thr && t - lastHit > 1000) pending = { t, z };
    if (pending && t - pending.t >= 250) classify();
  };

  /* the synthetic road: flat with sensor noise, or a replayed curve */
  const noise = () => (Math.random() - .5) * .25;
  const tick = now => {
    if (!device) {
      for (; clock + STEP <= now; clock += STEP) {
        let z = 9.81 + .12 * Math.sin(clock / 90) + noise();
        if (play) {
          const t = 120 + (clock - play.start);
          if (t > 400) {
            if (lastHit < play.start && !pending) say(plain
              ? 'Too gentle to report. It stayed under the alert level for this vehicle.'
              : `Below the threshold · peak ${play.peak.toFixed(1)} m/s², needs ${(base + thr).toFixed(1)}`, true);
            play = null;
          } else {
            z = 9.81 + (play.fn(t) - 9.81) * play.size + noise();
            play.peak = Math.max(play.peak, z);
          }
        }
        push(clock, z);
      }
    }
    const xs = t => (W - ((now - t) / SPAN) * W).toFixed(1);
    const pts = samples.map(([t, z]) => `${xs(t)},${ty(z).toFixed(1)}`);
    if (pts.length) $t('tryCurve').setAttribute('d', 'M' + pts.join('L'));
    for (const [id, v] of [['tryBase', base], ['tryThr', base + thr]]) {
      const l = $t(id); l.setAttribute('y1', ty(v).toFixed(1)); l.setAttribute('y2', ty(v).toFixed(1));
    }
    $t('tryThrT').setAttribute('y', (ty(base + thr) - 6).toFixed(1));
    $t('tryThrT').textContent = plain ? 'alert level' : `threshold · +${thr.toFixed(1)}`;
    marks = marks.filter(([t]) => now - t < SPAN);
    $t('tryMarks').innerHTML = marks.map(([t, z]) =>
      `<line class="mark-line" x1="${xs(t)}" x2="${xs(t)}" y1="0" y2="${H}"/><circle class="mark-dot" r="6" cx="${xs(t)}" cy="${ty(z).toFixed(1)}"/>`).join('');
    if (now - readAt > 100 && samples.length) {
      readAt = now;
      html('rAcc', unit(samples[samples.length - 1][1]));
      html('rBase', unit(base));
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);

  /* until someone plays, the buttons pulse; after the demo run, the trace invites them in */
  let played = false;
  const invite = $t('instPlay');
  const settle = () => {
    if (played) return;
    played = true; invite.hidden = true;
    tryEl.querySelectorAll('.cue').forEach(b => b.classList.remove('cue'));
    tryEl.querySelector('.try-prompt').hidden = true;
  };
  tryEl.addEventListener('click', e => { if (e.target.closest('[data-play], #tryMotion')) settle(); });
  if (touch) $t('playPothole').classList.remove('signal', 'cue');
  tryEl.querySelectorAll('[data-play]').forEach(b => b.addEventListener('click', () => {
    if (device) return;
    // real potholes vary: some clear the car threshold but not the motorcycle one
    play = { fn: b.dataset.play === 'bump' ? bump : pothole, start: clock, size: .75 + Math.random() * .65, peak: 0 };
  }));
  if ('IntersectionObserver' in window) {
    const seen = new IntersectionObserver(([e]) => {
      if (!e.isIntersecting) return;
      seen.disconnect();
      setTimeout(() => { if (!device && !play && !played) play = { fn: pothole, start: clock, size: 1.2, peak: 0 }; }, 700);
      setTimeout(() => { if (!played && !device) invite.hidden = false; }, 3400);
    }, { threshold: .5 });
    seen.observe(tryEl.querySelector('.inst'));
  } else invite.hidden = false;
  tryEl.querySelectorAll('[data-vehicle]').forEach(b => b.addEventListener('click', () => {
    thr = +b.dataset.vehicle;
    tryEl.querySelectorAll('[data-vehicle]').forEach(o => o.setAttribute('aria-pressed', o === b));
  }));

  /* the phone: project acceleration onto gravity, as the SDK does */
  const onMotion = e => {
    const gr = e.accelerationIncludingGravity, a = e.acceleration;
    if (!gr || gr.x == null) return;
    let z;
    if (a && a.x != null) {
      const gx = gr.x - a.x, gy = gr.y - a.y, gz = gr.z - a.z, gm = Math.hypot(gx, gy, gz) || 9.81;
      z = (gr.x * gx + gr.y * gy + gr.z * gz) / gm;
    } else z = Math.hypot(gr.x, gr.y, gr.z);
    if (!device) { device = true; $t('trySrc').textContent = 'Your phone · live'; say(idle()); }
    push(performance.now(), z);
  };
  $t('tryMotion').addEventListener('click', async () => {
    try {
      if (typeof DeviceMotionEvent.requestPermission === 'function' &&
          await DeviceMotionEvent.requestPermission() !== 'granted') { say('Motion access was declined.'); return; }
    } catch (err) { say('Motion access is not available here.'); return; }
    samples = []; pending = null;
    window.addEventListener('devicemotion', onMotion);
    $t('tryMotion').hidden = true;
    say('Waiting for the motion sensor…');
    setTimeout(() => { if (!device) say('No motion sensor found on this device.'); }, 2000);
  });
})();
