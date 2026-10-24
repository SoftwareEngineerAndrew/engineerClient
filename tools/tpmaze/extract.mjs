// Teleport Maze observations from the public ADR runs: for every group whose map has a Teleport Maze (one recording, the
// biggest), every player's samples inside the maze in the room's canonical frame, and the recorder's own teleports.
// A run without the maze stops downloading at its header. Re-runs skip the groups already in <out.jsonl>.
//   node extract.mjs <list-all.json> <out.jsonl> [concurrency]
import { readFileSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { colsReader } from '../secret-spawns/decode.mjs';
import { place, SHAPES } from '../secret-spawns/common.mjs';

const [listFile, outFile, conc = '4'] = process.argv.slice(2);
// Runs that started over a week ago open 30 a day per connection: the last week only (as secret-spawns/extract.mjs).
const WEEK = Date.now() - 7 * 86400_000 + 3600_000;
const byGroup = new Map();
for (const r of JSON.parse(readFileSync(listFile, 'utf8'))) {
  if (!r.floor || r.startMs <= WEEK) continue;
  const g = r.group || r.id, cur = byGroup.get(g);
  if (!cur || r.bytes > cur.bytes) byGroup.set(g, r);
}
const done = new Set();
if (existsSync(outFile)) for (const l of readFileSync(outFile, 'utf8').split('\n')) if (l) done.add(JSON.parse(l).group);
const todo = [...byGroup.values()].filter(r => !done.has(r.group || r.id));
console.error(`${byGroup.size} groups, ${todo.length} to do`);

/** Canonical (x, z) of world (x, z), not rounded (common.mjs's toCanon works on block columns). */
function canon(pl, x, z) {
  const px = x - pl.ox, pz = z - pl.oz;
  if (pl.rot === 'SOUTH') return [px, pz];
  if (pl.rot === 'NORTH') return [pl.w - px, pl.d - pz];
  if (pl.rot === 'WEST') return [pz, pl.d - px];
  return [pl.w - pz, px];
}
/** Canonical yaw of world yaw [yaw] (MC's: 0 looks along +z, 90 along -x). */
function canonYaw(pl, yaw) {
  const r = yaw * Math.PI / 180, dx = -Math.sin(r), dz = Math.cos(r);
  const [cx, cz] = pl.rot === 'SOUTH' ? [dx, dz] : pl.rot === 'NORTH' ? [-dx, -dz] : pl.rot === 'WEST' ? [dz, -dx] : [-dz, dx];
  return Math.atan2(-cx, cz) * 180 / Math.PI;
}
const r3 = v => Math.round(v * 1000) / 1000;

async function one(r) {
  const ac = new AbortController();
  const res = await fetch(`https://undonecoffee.com/adr/api/runs/${r.id}`, { signal: ac.signal });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const it = res.body[Symbol.asyncIterator]();
  const first = await it.next();
  const gz = first.value && first.value[0] === 0x1f && first.value[1] === 0x8b;
  const child = spawn(gz ? 'gzip' : 'xz', ['-dc'], { stdio: ['pipe', 'pipe', 'ignore'] });
  const closed = new Promise(ok => child.once('close', ok));
  let stop = false, maze = null, sawRoomsAll = false, box = null, self = r.self;
  const raw = new Map(), tps = [];
  const track = name => raw.get(name) || raw.set(name, []).get(name);
  const feed = colsReader(l => {
    if (stop) return;
    if (l.k === 'meta' && l.self) self = l.self;
    if ((l.k === 'roomsAll' || l.k === 'rooms') && Array.isArray(l.r)) {
      for (const room of l.r) if (room[0] === 'Teleport Maze' && room[5]?.length && (!maze || room[3])) maze = room;
      if (l.k === 'roomsAll') { sawRoomsAll = true; if (!maze) { stop = true; return; } }
      if (maze) box = [-200 + 32 * maze[5][0][0], -200 + 32 * maze[5][0][1]];
    }
    if (!box) return;
    const inBox = (x, z) => x >= box[0] && x < box[0] + 31 && z >= box[1] && z < box[1] + 31;
    if (l.k === 'p') for (const d of l.d) {
      const name = d[0];
      if (typeof name !== 'string' || name.includes('#') || (d[7] !== undefined && d[7] !== 4)) continue;
      // [-1] marks leaving the room, so a gap in a track is never read as a teleport
      if (!inBox(d[1], d[3])) { if (raw.has(name) && raw.get(name).at(-1)[0] !== -1) raw.get(name).push([-1]); continue; }
      track(name).push([l.t, d[1], d[2], d[3], d[4], d[5]]);
    }
    if (l.k === 'tp' && inBox(l.x, l.z)) tps.push([l.t, l.x, l.y, l.z, l.yaw, l.pitch]);
  });
  let rest = '';
  child.stdout.setEncoding('utf8');
  child.stdin.on('error', () => {});
  const reading = (async () => {
    for await (const chunk of child.stdout) {
      rest += chunk;
      let i;
      while (!stop && (i = rest.indexOf('\n')) >= 0) {
        const line = rest.slice(0, i); rest = rest.slice(i + 1);
        if (!line) continue;
        let l; try { l = JSON.parse(line); } catch { continue; }
        try { feed(l); } catch {}
        // movement before any map header: nothing to place the maze by
        if (!sawRoomsAll && (l.k === 'cols' || l.k === 'p')) stop = true;
      }
      if (stop) break;
    }
  })();
  let bytes = 0;
  try {
    for (let p = first; !p.done && !stop; p = await it.next()) {
      bytes += p.value.length;
      if (!child.stdin.write(p.value)) await Promise.race([new Promise(ok => child.stdin.once('drain', ok)), closed]);
    }
  } catch {}
  if (stop) { ac.abort(); child.kill(); } else child.stdin.end();
  await reading.catch(() => {});
  if (!stop) feed.end();
  const row = { id: r.id, group: r.group || r.id, floor: r.floor, startMs: r.startMs, self, bytes };
  const pl = maze?.[3] ? place(SHAPES[maze[2]], maze[3], maze[5], false) : null;
  if (!pl) { appendFileSync(outFile, JSON.stringify({ ...row, maze: maze ? 'unplaced' : null }) + '\n'); return; }
  const players = {};
  for (const [name, s] of raw) {
    if (!s.some(e => e[0] !== -1)) continue;
    players[name] = s.map(e => { if (e[0] === -1) return e; const [cx, cz] = canon(pl, e[1], e[3]); return [e[0], r3(cx), r3(e[2]), r3(cz), r3(canonYaw(pl, e[4])), e[5]]; });
  }
  const selfTps = tps.map(e => { const [cx, cz] = canon(pl, e[1], e[3]); return [e[0], r3(cx), r3(e[2]), r3(cz), r3(canonYaw(pl, e[4])), e[5]]; });
  appendFileSync(outFile, JSON.stringify({ ...row, maze: { rot: maze[3], tiles: maze[5], check: maze[4] }, players, selfTps }) + '\n');
}

let n = 0, failed = 0;
const queue = todo.slice();
await Promise.all(Array.from({ length: +conc }, async () => {
  while (queue.length) {
    const r = queue.shift();
    for (let attempt = 0; ; attempt++) {
      try { await one(r); break; }
      catch (e) { if (attempt >= 2 || /HTTP 4(04|10)/.test(e.message)) { failed++; console.error(r.id, e.message); break; } await new Promise(ok => setTimeout(ok, 2000 * (attempt + 1))); }
    }
    if (++n % 100 === 0) console.error(n, 'done,', failed, 'failed');
    await new Promise(ok => setTimeout(ok, 250));
  }
}));
console.error('finished', n, 'failed', failed);
