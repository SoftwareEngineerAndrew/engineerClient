// Full run files for a sample of cleared 5-player F7/M7 runs, keeping what tells when each room was
// discovered on the map: the rooms lines (compacted to name/tiles/checkmark), room enters, and the
// run-start / door chat. node fetch-timelines.mjs <list-all.json> <out.jsonl> <count>
import { readFileSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
const [listFile, outFile, count] = process.argv.slice(2);
const rows = JSON.parse(readFileSync(listFile, 'utf8')).filter(r => r.cleared && ['F7', 'M7'].includes(r.floor) && r.party.length === 5);
const groups = new Map(); for (const r of rows) { const g = r.group || r.id; if (!groups.has(g) || r.bytes > groups.get(g).bytes) groups.set(g, r); }
let pick = [...groups.values()];
// deterministic shuffle
let seed = 12345; const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
for (let i = pick.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [pick[i], pick[j]] = [pick[j], pick[i]]; }
const done = new Set(); if (existsSync(outFile)) for (const l of readFileSync(outFile, 'utf8').split('\n')) if (l) done.add(JSON.parse(l).id);
pick = pick.slice(0, +count).filter(r => !done.has(r.id));
console.error(pick.length, 'to fetch');
const CHAT = /Mort: Here, I found this map|opened a WITHER door|BLOOD DOOR has been opened|has obtained (Wither|Blood) Key|\[BOSS\]/;
async function one(r) {
  const res = await fetch(`https://undonecoffee.com/adr/api/runs/${r.id}`);
  if (!res.ok) throw new Error(res.status);
  const it = res.body[Symbol.asyncIterator](); const first = await it.next(); const gz = first.value && first.value[0] === 0x1f && first.value[1] === 0x8b;
  const xz = spawn(gz ? 'gzip' : 'xz', ['-dc'], { stdio: ['pipe', 'pipe', 'ignore'] });
  let dead = false; xz.on('close', () => { dead = true; });
  const keep = []; let buf = '';
  xz.stdout.setEncoding('utf8');
  const ended = new Promise(ok => xz.stdout.on('end', ok));
  xz.stdout.on('data', c => {
    buf += c; let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i); buf = buf.slice(i + 1);
      const k = /^\{"k":"(\w+)"/.exec(line)?.[1];
      if (k === 'meta' || k === 'roomsAll' || k === 'dslots' || k === 'floor' || k === 'room') keep.push(line);
      else if (k === 'rooms') { const o = JSON.parse(line); keep.push(JSON.stringify({ k: 'rooms', t: o.t, r: o.r.map(x => [x[0], x[1], x[4], x[5]]) })); }
      else if (k === 'chat' && CHAT.test(line)) { const o = JSON.parse(line); keep.push(JSON.stringify({ k: 'chat', t: o.t, m: o.m })); }
    }
  });
  xz.stdin.on('error', () => {});
  for (let p = first; !p.done && !dead; p = await it.next()) if (!xz.stdin.write(p.value)) await new Promise(ok => { xz.stdin.once('drain', ok); xz.once('close', ok); });
  xz.stdin.end(); await ended;
  appendFileSync(outFile, JSON.stringify({ id: r.id, group: r.group, floor: r.floor, party: r.party, lines: keep }) + '\n');
}
let n = 0;
for (const r of pick) {
  try { await one(r); n++; } catch (e) { console.error(r.id, e.message); }
  if (n % 25 === 0) console.error(n, 'done');
  await new Promise(ok => setTimeout(ok, 1500));
}
console.error('finished', n);
