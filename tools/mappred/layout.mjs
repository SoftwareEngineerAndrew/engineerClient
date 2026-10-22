// Turns a fetched run header (fetch-layouts.mjs) into a dungeon layout: rooms on the 6x6 grid and
// the doors between tiles.
import { readFileSync } from 'node:fs';

const MID = j => -185 + 32 * j;          // middle of tile j along an axis
const GAP = i => -201 + 32 * i;          // gap before tile i (between tile i-1 and i)

/** "x,z" of the door spot -> [ax, az, bx, bz] (tile a to tile b). */
const SPOTS = new Map();
for (let i = 1; i <= 5; i++) for (let j = 0; j <= 5; j++) {
  SPOTS.set(`${GAP(i)},${MID(j)}`, [i - 1, j, i, j]);   // horizontal: x gap
  SPOTS.set(`${MID(j)},${GAP(i)}`, [j, i - 1, j, i]);   // vertical: z gap
}

export const TYPE_CODE = { NORMAL: 'N', PUZZLE: 'P', TRAP: 'T', CHAMPION: 'Y', BLOOD: 'B', ENTRANCE: 'E', FAIRY: 'F', RARE: 'R', UNKNOWN: '?' };

export function parse(rec) {
  let meta = null, rooms = null, floor = rec.floor;
  const slots = new Map();
  for (const line of rec.lines) {
    const o = JSON.parse(line);
    if (o.k === 'meta') meta = o;
    else if (o.k === 'roomsAll') rooms = o.r;
    else if (o.k === 'floor') floor = o.floor || floor;
    else if (o.k === 'dslots') for (const [x, z, what, top] of o.d) slots.set(`${x},${z}`, { what, top });
  }
  if (!meta || !rooms) return null;
  if (meta.ads) return null; // the mod's own dungeon sim, not Hypixel
  const grid = Array.from({ length: 6 }, () => Array(6).fill(-1));
  const list = rooms.map((r, i) => ({ i, name: r[0], type: r[1], shape: r[2], rot: r[3], check: r[4], tiles: r[5].map(([x, z]) => ({ x, z })), max: r[7] }));
  for (const r of list) for (const t of r.tiles) if (t.x >= 0 && t.x < 6 && t.z >= 0 && t.z < 6) grid[t.z][t.x] = r.i;
  const doors = [];
  for (const [key, s] of slots) {
    const sp = SPOTS.get(key);
    if (!sp) continue;
    const [ax, az, bx, bz] = sp;
    doors.push({ ax, az, bx, bz, what: s.what, top: s.top, a: grid[az][ax], b: grid[bz][bx] });
  }
  return { id: rec.id, group: rec.group, floor, cleared: rec.cleared, party: rec.party, startMs: rec.startMs, mc: meta.mc, mod: meta.mod, rooms: list, grid, doors, slotsRead: slots.size };
}

export function load(file) {
  const out = [];
  for (const l of readFileSync(file, 'utf8').split('\n')) {
    if (!l) continue;
    const p = parse(JSON.parse(l));
    if (p) out.push(p);
  }
  return out;
}

/** Grid extent actually used by rooms. */
export function extent(L) {
  let mx = 0, mz = 0;
  for (let z = 0; z < 6; z++) for (let x = 0; x < 6; x++) if (L.grid[z][x] >= 0) { mx = Math.max(mx, x); mz = Math.max(mz, z); }
  return [mx + 1, mz + 1];
}

/** Door graph between rooms: real doors are spots between two different rooms whose kind isn't "-". */
export function graph(L) {
  const adj = new Map(L.rooms.map(r => [r.i, []]));
  for (const d of L.doors) {
    if (d.a < 0 || d.b < 0 || d.a === d.b || d.what === '-') continue;
    adj.get(d.a).push({ to: d.b, what: d.what, d });
    adj.get(d.b).push({ to: d.a, what: d.what, d });
  }
  return adj;
}

export function render(L) {
  const rows = [];
  for (let z = 0; z < 6; z++) {
    let s = '';
    for (let x = 0; x < 6; x++) {
      const i = L.grid[z][x];
      if (i < 0) { s += ' . '; continue; }
      const r = L.rooms[i];
      s += ' ' + (TYPE_CODE[r.type] || '?') + (r.shape === 'OneByOne' ? ' ' : r.tiles.length);
    }
    rows.push(s);
  }
  return rows.join('\n');
}
