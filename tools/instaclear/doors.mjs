// Every wither door opened in the runs in runs/: the room behind it, whether it instacleared, when
// that room's mobs were made, how long a player had stood in the tile in front of the door, and
// whether the recording client had seen a mob in the room behind. One line per door to doors.tsv,
// the tables of README.md to stdout.
//   node doors.mjs
import { readdirSync, writeFileSync } from 'node:fs';
import { loadCatalogue, readRun } from './read.mjs';

const cat = await loadCatalogue();
const strip = s => (s ?? '').replace(/§./g, '');
// A dungeon mob: its entity, or the "✯ Name 1.2M❤" stand Hypixel puts over it (many mobs are only
// in the recording as their stand).
const MOB = /zombie|skeleton|enderman|spider|creeper|blaze|silverfish|minecraft:player|ghast|witch|guardian|golem|slime|magma|piglin/;
const isMob = l => l.x != null && (MOB.test(l.type ?? '') || (/armor_stand/.test(l.type ?? '') && /❤/.test(l.c ?? '')));

// Map tiles: tile t spans -200 + 32t .. -170 + 32t; the gap between tiles (where doors are) is -169 + 32t.
const tileOf = (x, z) => [Math.floor((x + 200) / 32), Math.floor((z + 200) / 32)];
const inGap = (x, z) => ((x + 200) % 32 + 32) % 32 > 30.5 || ((z + 200) % 32 + 32) % 32 > 30.5;

const rows = [];
for (const f of readdirSync('runs')) {
  let rooms, self, floor, mort;
  const mobs = [], ids = [], doors = [], keys = [], bars = [], roomLines = [], pal = [], party = new Set();
  const pos = new Map(), precise = new Map(); // name -> [[t, x, z]]: map markers and exact positions; exact only
  const addPos = (m, name, t, x, z) => (m.get(name) || m.set(name, []).get(name)).push([t, x, z]);
  try {
    readRun(`runs/${f}`, cat, l => {
      switch (l.k) {
        case 'meta': self = l.self; break;
        case 'floor': floor = l.floor; break;
        case 'party': for (const m of l.m) party.add(m[0]); break;
        case 'roomsAll': rooms = l.r; break;
        case 'rooms': roomLines.push(l); break;
        case 'pal': pal[l.i] = l.s; break;
        case 'block': if (/barrier/.test(pal[l.s] ?? '')) bars.push([l.t, l.x, l.z]); break;
        case 'spawn': if (l.id != null && l.t != null) { ids.push([l.t, l.id]); if (isMob(l)) mobs.push(l); } break;
        case 'pflat': if (typeof l.key === 'string' && !l.key.includes('#') && l.x != null) { addPos(pos, l.key, l.t, l.x, l.z); addPos(precise, l.key, l.t, l.x, l.z); } break;
        case 'mp': for (const d of l.d || []) if (typeof d[0] === 'string') addPos(pos, d[0], l.t, d[1], d[2]); break;
        case 'chat': {
          const m = strip(l.c ?? l.m);
          const door = /^(?:\[[^\]]+\] )?(\S+) opened a WITHER door/.exec(m);
          if (door) doors.push([l.t, door[1]]);
          if (/obtained (Wither|Blood) Key|(Wither|Blood) Key was picked up/.test(m)) keys.push(l.t);
          if (/Mort: Here, I found/.test(m)) mort = l.t;
        }
      }
    });
  } catch (e) { console.error(f, e.message); continue; }
  if (!rooms || !doors.length) continue;
  for (const v of [...pos.values(), ...precise.values()]) v.sort((a, b) => a[0] - b[0]);
  roomLines.sort((a, b) => a.t - b.t);

  // When an entity was made, server side: entity ids only go up, so a mob's id against the
  // highest id the client had seen by each tick dates it, even one only seen later.
  ids.sort((a, b) => a[0] - b[0]);
  const clock = []; let top = 0;
  for (const [t, id] of ids) if (id > top) { top = id; clock.push([t, id]); }
  const made = id => {
    if (!clock.length || id > clock.at(-1)[1]) return null;
    let lo = 0, hi = clock.length - 1;
    while (lo < hi) { const m = (lo + hi) >> 1; if (clock[m][1] >= id) hi = m; else lo = m + 1; }
    return clock[lo][0];
  };

  const roomAt = (a, b) => rooms.find(r => r[5].some(t => t[0] === a && t[1] === b));
  const key = r => r[0] + '@' + r[5][0];
  const roomOf = (x, z) => { const [a, b] = tileOf(x, z); return roomAt(a, b); };
  const boxDist = (r, x, z) => Math.min(...r[5].map(([a, b]) => {
    const x0 = -200 + 32 * a, z0 = -200 + 32 * b;
    return Math.hypot(Math.max(x0 - x, 0, x - x0 - 30), Math.max(z0 - z, 0, z - z0 - 30));
  }));
  const posAt = (name, t, within) => { let best = null; for (const p of pos.get(name) || []) { if (p[0] > t) break; if (p[0] >= t - within) best = p; } return best; };
  const whiteAt = k => { for (const l of roomLines) for (const r of l.r) if (key(r) === k && (r[4] === 'WHITE' || r[4] === 'GREEN')) return l.t; return null; };
  const mobsIn = k => mobs.filter(s => { const r = roomOf(s.x, s.z); return r && key(r) === k; });

  for (const [dt, opener] of doors) {
    // The door: its blocks turning to barrier as it opens.
    const b = bars.filter(q => q[0] >= dt - 2 && q[0] <= dt + 3);
    if (b.length < 6) continue;
    const dx = b.reduce((s, q) => s + q[1], 0) / b.length, dz = b.reduce((s, q) => s + q[2], 0) / b.length;
    const [ta, tb] = tileOf(dx, dz);
    const ox = ((dx + 200) % 32 + 32) % 32;
    const [A, B] = ox > 28 || ox < 3
      ? [roomAt(ox > 28 ? ta : ta - 1, tb), roomAt(ox > 28 ? ta + 1 : ta, tb)]
      : (() => { const oz = ((dz + 200) % 32 + 32) % 32, b0 = oz > 28 ? tb : tb - 1; return [roomAt(ta, b0), roomAt(ta, b0 + 1)]; })();
    if (!A || !B) continue;
    // Behind: the side the opener isn't on.
    const op = posAt(opener, dt, 10);
    if (!op) continue;
    const [near, behind] = boxDist(A, op[1], op[2]) <= boxDist(B, op[1], op[2]) ? [A, B] : [B, A];
    const k = key(behind);
    if (behind[1] !== 'NORMAL') continue; // fairy: no mobs, nothing to lose

    const white = whiteAt(k);
    const keyAfter = keys.some(t => t > dt);
    // Instaclear: the room went white straight away, and no key came of it.
    const insta = white !== null && white - dt <= 15 && (!keyAfter || white - dt <= 8);
    const madeTicks = mobsIn(k).map(s => made(s.id)).filter(t => t !== null).sort((a, b) => a - b);
    const mobsMade = madeTicks.length ? madeTicks[0] - dt : null;
    const seenBefore = mobsIn(k).filter(s => s.t < dt).length;
    const me = posAt(self, dt, 3);
    const selfToDoor = me ? Math.hypot(me[1] - dx, me[2] - dz) : null;
    // The tile of the near room in front of the door, and when a player (exact positions) first stood in it.
    const doorTile = near[5].find(([p, q]) => Math.abs(-185 + 32 * p - dx) <= 17 && Math.abs(-185 + 32 * q - dz) <= 17);
    let inTile = null;
    if (doorTile) for (const [n, v] of precise) {
      if (party.size && !party.has(n)) continue;
      const first = v.find(([t, x, z]) => t <= dt && !inGap(x, z) && tileOf(x, z).join() === doorTile.join());
      if (first && (inTile === null || first[0] < inTile)) inTile = first[0];
    }
    rows.push({
      run: f, floor, door: dt, sinceMort: mort != null ? dt - mort : null, near: near[0], nearType: near[1], behind: behind[0],
      white: white === null ? null : white - dt, keyAfter, insta, mobsMade, seenBefore,
      selfToDoor: selfToDoor === null ? null : Math.round(selfToDoor), inDoorTile: inTile === null ? null : dt - inTile,
    });
  }
}

const cols = Object.keys(rows[0] ?? {});
writeFileSync('doors.tsv', [cols.join('\t'), ...rows.map(r => cols.map(c => r[c] ?? '').join('\t'))].join('\n') + '\n');

function table(title, bucket, filter = () => true) {
  const h = new Map();
  for (const r of rows.filter(filter)) { const b = bucket(r); if (b == null) continue; const e = h.get(b) || { ic: 0, ok: 0 }; r.insta ? e.ic++ : e.ok++; h.set(b, e); }
  console.log(`\n${title}\n| | instaclear | fine | rate |\n|---|---|---|---|`);
  for (const [b, e] of [...h].sort((x, y) => String(x[0]).localeCompare(String(y[0]), undefined, { numeric: true })))
    console.log(`| ${b} | ${e.ic} | ${e.ok} | ${(100 * e.ic / (e.ic + e.ok)).toFixed(1)}% |`);
}
const bin = (v, edges) => { for (let i = 0; i < edges.length - 1; i++) if (v < edges[i + 1]) return `${String.fromCharCode(97 + i)}) ${edges[i]}..${edges[i + 1] - 1}`; return `${String.fromCharCode(97 + edges.length - 1)}) ${edges.at(-1)}+`; };
console.log(`${rows.length} wither doors into normal rooms, ${rows.filter(r => r.insta).length} instaclears`);
table("When the room behind's mobs were made (ticks after the door opened)", r => r.mobsMade === null ? null : bin(r.mobsMade, [-1000, -100, -20, -5, 0, 6, 1000]));
table('How long a player had stood in the tile in front of the door (exact positions)', r => r.inDoorTile === null ? null : bin(r.inDoorTile, [0, 5, 10, 15, 20, 30, 60]));
const near = r => r.selfToDoor !== null && r.selfToDoor <= 20;
table('Recording client within 20 blocks of the door: had it seen a mob in the room behind?', r => (r.seenBefore > 0 ? 'saw a mob' : 'saw none'), near);
table('Rule: hold the door while no mob is seen in the room behind and the door tile has been stood in < 15 ticks (client near the door)',
  r => (r.seenBefore === 0 && (r.inDoorTile ?? 0) < 15 ? 'held' : 'let through'), near);
