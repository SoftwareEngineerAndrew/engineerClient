import fs from 'fs';
const runs = JSON.parse(fs.readFileSync('ss.json'));
const cell = (y, z) => 'abcd'[123 - y] + (z - 91);
const hist = {}; const H = (k, v) => { (hist[k] ||= []).push(v); };
const patterns = {}; const P = k => patterns[k] = (patterns[k] || 0) + 1;
let wrongs = [];
for (const r of runs) {
  const ev = r.ev.filter(e => e[0] >= r.p3 - 20 && e[0] <= r.p3 + 1500);
  // group mass button changes
  const cnt = new Map(); for (const e of ev) if (e[1] === 'up' || e[1] === 'air') cnt.set(e[0] + e[1], (cnt.get(e[0] + e[1]) || 0) + 1);
  const seq = []; const seen = new Set();
  for (const e of ev) {
    if (e[1] === 'up' || e[1] === 'air') { const k = e[0] + e[1]; if (cnt.get(k) >= 10 && !seen.has(k)) { seen.add(k); seq.push({ t: e[0], k: e[1] === 'up' ? 'BTNS' : 'GONE' }); } continue; }
    if (e[1] === 'on') seq.push({ t: e[0], k: 'ON', c: cell(e[2], e[3]) });
    if (e[1] === 'off') seq.push({ t: e[0], k: 'OFF', c: cell(e[2], e[3]) });
    if (e[1] === 'down') seq.push({ t: e[0], k: 'DOWN', c: cell(e[2], e[3]) });
    if (e[1] === 'start') seq.push({ t: e[0], k: 'START' });
    if (e[1] === 'use' && e[2] === 110 && e[4] === 91) seq.push({ t: e[0], k: 'USESTART' });
    if (e[1] === 'chat' && /completed a device/.test(e[2])) seq.push({ t: e[0], k: 'DONE', m: e[2] });
  }
  // shows: runs of ON between BTNS
  const shows = []; let cur = null;
  for (const s of seq) {
    if (s.k === 'ON') { if (!cur) { cur = { t: s.t, cells: [], ons: [] }; shows.push(cur); } cur.cells.push(s.c); cur.ons.push(s.t); }
    if (s.k === 'BTNS') { if (cur) { cur.btns = s.t; } cur = null; }
    if (s.k === 'OFF' && cur) cur.lastOff = s.t;
  }
  if (!shows.length) continue;
  P(shows.map(s => s.cells.length).join(','));
  for (const s of shows) {
    for (let i = 1; i < s.ons.length; i++) H('on->on', s.ons[i] - s.ons[i - 1]);
    if (s.btns && s.lastOff) H('lastOff->btns', s.btns - s.lastOff);
    if (s.btns) H('lastOn->btns', s.btns - s.ons[s.ons.length - 1]);
  }
  // prefix relation of consecutive shows
  for (let i = 1; i < shows.length; i++) {
    const a = shows[i - 1].cells, b = shows[i].cells;
    const pre = a.every((c, j) => b[j] === c) ? 'prefix' : a.slice(1).every((c, j) => b[j] === c) ? 'prefix-after-first' : 'other';
    P(`rel ${a.length}->${b.length} ${pre}`);
  }
  // repeats within final sequence
  const last = shows[shows.length - 1].cells; P('repeats:' + (new Set(last).size < last.length));
  // start presses count before first ON
  const starts = seq.filter(s => (s.k === 'START') && s.t <= shows[0].t + 2).length; P('startsBeforeFirstShow=' + starts);
  // after BTNS of first show, time to GONE
  const g = seq.find(s => s.k === 'GONE' && s.t > (shows[0].btns || 1e9)); if (g && shows[0].btns) H('firstBtns->gone', g.t - shows[0].btns);
  // last correct press -> next gone
  for (let i = 0; i < seq.length; i++) if (seq[i].k === 'DOWN') { const nx = seq.slice(i + 1).find(s => s.k !== 'OFF'); if (nx && (nx.k === 'GONE')) H('down->gone', nx.t - seq[i].t); }
  const done = seq.find(s => s.k === 'DONE' && s.t > shows[shows.length-1].t); if (done && shows[shows.length-1].btns) H('lastBtns->done', done.t - shows[shows.length-1].btns);
  // wrong presses: DOWN cell not expected per current show
  let show = null, idx = 0;
  for (const s of seq) {
    if (s.k === 'ON') { if (!show || show.done) { show = { cells: [], done: false }; idx = 0; } show.cells.push(s.c); }
    if (s.k === 'BTNS' && show) show.done = true;
    if (s.k === 'DOWN' && show) { if (s.c !== show.cells[idx]) wrongs.push({ id: r.id, t: s.t - r.p3, exp: show.cells[idx], got: s.c, len: show.cells.length }); idx++; }
  }
}
const q = a => { a.sort((x, y) => x - y); return `n=${a.length} p10=${a[a.length * .1 | 0]} med=${a[a.length / 2 | 0]} p90=${a[a.length * .9 | 0]}`; };
for (const [k, v] of Object.entries(hist)) console.log(k.padEnd(18), q(v));
console.log(Object.entries(patterns).sort((a, b) => b[1] - a[1]).slice(0, 40).map(([k, v]) => `${v}\t${k}`).join('\n'));
console.log('wrong presses', wrongs.length, JSON.stringify(wrongs.slice(0, 12)));
