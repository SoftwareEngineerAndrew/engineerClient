// Streams each ADR run's world header (meta, roomsAll, dslots, floor, party) and stops reading at the
// first timed line, so only the start of every file is downloaded. One recording per group.
//   node fetch-layouts.mjs <list.json> <out.jsonl>
import { readFileSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const [listFile, outFile] = process.argv.slice(2);
const rows = JSON.parse(readFileSync(listFile, 'utf8'));
const WORLD = new Set(['meta', 'roomsAll', 'lib', 'dslots', 'door', 'vol', 'chunk', 'pal', 'skull', 'skin', 'party', 'floor']);
const KEEP = new Set(['meta', 'roomsAll', 'dslots', 'party', 'floor']);

const done = new Set();
if (existsSync(outFile)) for (const l of readFileSync(outFile, 'utf8').split('\n')) if (l) done.add(JSON.parse(l).group);

// One recording per group: the biggest (most likely to have the whole layout loaded).
const byGroup = new Map();
for (const r of rows) {
  if (!/^[FM][1-7]$/.test(r.floor)) continue;
  const g = r.group || r.id;
  const cur = byGroup.get(g);
  if (!cur || r.bytes > cur.bytes) byGroup.set(g, r);
}
const todo = [...byGroup.entries()].filter(([g]) => !done.has(g));
console.error(`${byGroup.size} groups, ${todo.length} to fetch`);

function header(id) {
  return new Promise(async (resolve, reject) => {
    const ac = new AbortController();
    let res;
    try { res = await fetch(`https://undonecoffee.com/adr/api/runs/${id}`, { signal: ac.signal }); }
    catch (e) { return reject(e); }
    if (!res.ok) return reject(new Error(`${res.status}`));
    const it = res.body[Symbol.asyncIterator](); const first = await it.next(); const gz = first.value && first.value[0] === 0x1f && first.value[1] === 0x8b;
    const xz = spawn(gz ? 'gzip' : 'xz', ['-dc'], { stdio: ['pipe', 'pipe', 'ignore'] });
    const kept = [];
    let buf = '', finished = false, bytes = 0;
    const finish = () => {
      if (finished) return; finished = true;
      try { ac.abort(); } catch {}
      try { xz.kill(); } catch {}
      resolve({ kept, bytes });
    };
    xz.stdout.setEncoding('utf8');
    xz.stdout.on('data', chunk => {
      if (finished) return;
      buf += chunk;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i); buf = buf.slice(i + 1);
        const k = /^\{"k":"(\w+)"/.exec(line)?.[1];
        if (!k) continue;
        if (!WORLD.has(k)) return finish();
        if (KEEP.has(k)) kept.push(line);
      }
    });
    xz.stdout.on('end', finish);
    xz.stdin.on('error', () => {});
    try {
      for (let p = first; !p.done; p = await it.next()) { const part = p.value;
        if (finished) break;
        bytes += part.length;
        if (!xz.stdin.write(part)) await new Promise(r => xz.stdin.once('drain', r));
      }
      if (!finished) xz.stdin.end();
    } catch (e) { if (!finished) { finished = true; reject(e); } }
  });
}

let next = 0, got = 0, failed = 0, total = 0;
let lastStart = 0;
async function slot() { for (;;) { const now = Date.now(); if (now - lastStart >= 260) { lastStart = now; return; } await new Promise(r => setTimeout(r, 260 - (now - lastStart))); } }
async function worker() {
  while (next < todo.length) {
    const [g, r] = todo[next++];
    await slot();
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const { kept, bytes } = await header(r.id);
        total += bytes;
        const rec = { group: g, id: r.id, floor: r.floor, cleared: r.cleared, timeMs: r.timeMs, startMs: r.startMs, party: r.party, lines: kept };
        appendFileSync(outFile, JSON.stringify(rec) + '\n');
        got++;
        break;
      } catch (e) {
        if (attempt === 2) { failed++; console.error(r.id, e.message); }
        await new Promise(res => setTimeout(res, 3000 * (attempt + 1)));
      }
    }
    if ((got + failed) % 100 === 0) console.error(`${got} got, ${failed} failed, ${(total / 1e6).toFixed(1)} MB`);
  }
}
await Promise.all(Array.from({ length: 4 }, worker));
console.error(`done: ${got} got, ${failed} failed, ${(total / 1e6).toFixed(1)} MB`);
