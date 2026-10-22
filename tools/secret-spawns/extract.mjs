// Downloads every run in list-all.json and keeps what secret spawn ranges need, one gzipped JSON per
// recording in <out>/<id>.json.gz (the run file itself is not kept). Re-runs skip what is there.
//   node extract.mjs <list-all.json> <out dir> [concurrency]
import { readFileSync, writeFileSync, existsSync, mkdirSync, renameSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';
import { readRun } from './decode.mjs';

const [listFile, outDir, conc = '6'] = process.argv.slice(2);
mkdirSync(outDir, { recursive: true });
// Runs that started over a week ago open 30 a day per connection (the site keeps its archive from being copied): the last week only.
const WEEK = Date.now() - 7 * 86400_000 + 3600_000;
const rows = JSON.parse(readFileSync(listFile, 'utf8')).filter(r => r.floor && r.startMs > WEEK);
const todo = rows.filter(r => !existsSync(join(outDir, r.id + '.json.gz')) && !existsSync(join(outDir, r.id + '.bad')));
console.error(`${rows.length} runs, ${todo.length} to do`);

const CHAT = /secret|essence|bat|key|chest|lever|blessing|found|obtained|picked/i;

export function extract(r, lines) {
  const o = { id: r.id, group: r.group || r.id, floor: r.floor, self: r.self, cleared: r.cleared, P: {}, pg: [], sp: [], gn: [], pk: [], sk: [], hb: [], tp: [], bs: [], rm: [], use: [], chat: [] };
  const pal = [], ents = new Set(), heads = new Set(), last = new Map();
  for (const l of lines) {
    switch (l.k) {
      case 'meta': o.meta = { format: l.format, mod: l.mod, mc: l.mc, farHalf: l.farHalf }; if (l.self) o.self = l.self; break;
      case 'party': o.party = l.m; break;
      case 'roomsAll': o.rooms = l.r; o.roomsT = l.t; break;
      case 'rooms': if (!o.rooms0) o.rooms0 = l.r; o.roomsLast = l.r; break;
      case 'pal': pal[l.i] = l.s; break;
      case 'p': for (const d of l.d) {
        const name = d[0];
        if (typeof name !== 'string' || name.includes('#')) continue;
        if (d[7] !== undefined && d[7] !== 4) continue;
        const prev = last.get(name);
        if (prev && prev[0] === d[1] && prev[1] === d[2] && prev[2] === d[3]) continue;
        last.set(name, [d[1], d[2], d[3]]);
        (o.P[name] ||= []).push(l.t, d[1], d[2], d[3]);
      } break;
      case 'pgone': o.pg.push([l.t, l.name]); last.delete(l.name); break;
      case 'spawn':
        if (l.type === 'minecraft:item' || l.type === 'minecraft:bat') {
          ents.add(l.id);
          o.sp.push([l.t, l.id, l.type === 'minecraft:bat' ? 1 : 0, l.x, l.y, l.z, l.item ?? '']);
        }
        break;
      case 'gone': if (ents.has(l.id)) o.gn.push([l.t, l.id]); break;
      case 'pickup': o.pk.push([l.t, l.id, l.by]); break;
      case 'skull': { o.sk.push([l.t, l.x, l.y, l.z, l.tex]); heads.add(l.x + ',' + l.y + ',' + l.z); break; }
      case 'block': {
        const s = pal[l.s] ?? '';
        const key = l.x + ',' + l.y + ',' + l.z;
        if (/head|skull/.test(s)) { heads.add(key); o.hb.push([l.t, l.x, l.y, l.z, s]); }
        else if (heads.has(key)) o.hb.push([l.t, l.x, l.y, l.z, s]);
        else if (/chest|lever|redstone_block/.test(s)) o.hb.push([l.t, l.x, l.y, l.z, s]);
        break;
      }
      case 'tp': o.tp.push([l.t, l.x, l.y, l.z]); break;
      case 'batsound': o.bs.push([l.t, l.x, l.y, l.z, l.v]); break;
      case 'room': o.rm.push([l.t, l.name]); break;
      case 'use': o.use.push([l.t, l.x, l.y, l.z]); break;
      case 'chat': if (CHAT.test(l.m)) o.chat.push([l.t, l.m]); break;
      case 'end': o.end = l.t; break;
      case 'floor': o.floorLine = l; break;
    }
  }
  return o;
}

async function get(id) {
  for (let i = 0; ; i++) {
    try {
      const res = await fetch(`https://undonecoffee.com/adr/api/runs/${id}`);
      if (res.ok) return Buffer.from(await res.arrayBuffer());
      if (res.status === 404 || res.status === 410 || i >= 4) throw new Error(`HTTP ${res.status}`);
    } catch (err) { if (i >= 4 || /HTTP 4(04|10)/.test(err.message)) throw err; }
    await new Promise(r => setTimeout(r, 3000 * (i + 1)));
  }
}

let next = 0, done = 0, bytes = 0, failed = 0;
const t0 = Date.now();
async function worker() {
  while (next < todo.length) {
    const r = todo[next++];
    try {
      const buf = await get(r.id);
      bytes += buf.length;
      const lines = [];
      await readRun(buf, l => { if (l.k !== 'e' && l.k !== 'cam' && l.k !== 'eq' && l.k !== 'stand' && l.k !== 'tag' && l.k !== 'mp' && l.k !== 'st' && l.k !== 'sw') lines.push(l); });
      const o = extract(r, lines);
      const f = join(outDir, r.id + '.json.gz');
      writeFileSync(f + '.part', gzipSync(JSON.stringify(o)));
      renameSync(f + '.part', f);
    } catch (err) {
      failed++;
      console.error(r.id, err.message);
      if (/HTTP 4(04|10)/.test(err.message)) writeFileSync(join(outDir, r.id + '.bad'), err.message);
    }
    if (++done % 100 === 0) console.error(`${done}/${todo.length} ${(bytes / 1e9).toFixed(2)} GB ${((Date.now() - t0) / 60000).toFixed(1)} min, ${failed} failed`);
  }
}
if (process.argv[1].endsWith('extract.mjs')) {
  await Promise.all(Array.from({ length: +conc }, worker));
  console.error(`done: ${done}, ${failed} failed`);
}
