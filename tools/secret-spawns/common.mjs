// Shared: loading extracted recordings, the ADS rooms pack, and the placement law (ADS pack/rooms/Placement.kt).
import { readFileSync, readdirSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { join } from 'node:path';

export const SHAPES = { OneByOne: '1x1', TwoByOne: '1x2', ThreeByOne: '1x3', FourByOne: '1x4', TwoByTwo: '2x2', L: 'L' };
const CANON = {
  '1x1': [[0, 0]], '1x2': [[0, 0], [1, 0]], '1x3': [[0, 0], [1, 0], [2, 0]], '1x4': [[0, 0], [1, 0], [2, 0], [3, 0]],
  '2x2': [[0, 0], [1, 0], [0, 1], [1, 1]], L: [[0, 0], [0, 1], [1, 1]],
};
const size = s => [Math.max(...CANON[s].map(t => t[0])) + 1, Math.max(...CANON[s].map(t => t[1])) + 1];
export const footprint = s => { const [nx, nz] = size(s); return [31 + 32 * (nx - 1), 31 + 32 * (nz - 1)]; };
function placeTile(rot, ix, iz, nx, nz, tx0, tz0) {
  let a, b;
  if (rot === 'SOUTH') { a = ix; b = iz; } else if (rot === 'NORTH') { a = nx - 1 - ix; b = nz - 1 - iz; }
  else if (rot === 'WEST') { a = nz - 1 - iz; b = ix; } else { a = iz; b = nx - 1 - ix; }
  return [tx0 + a, tz0 + b];
}

/** The placement of a room (shape '1x1'.., rotation word, tiles [[tx,tz]]), or null when the law refuses it. */
export function place(shape, rot, tiles, fairy) {
  if (fairy) rot = 'SOUTH';
  if (!CANON[shape] || !['SOUTH', 'WEST', 'NORTH', 'EAST'].includes(rot) || !tiles.length) return null;
  const [w, d] = footprint(shape), [nx, nz] = size(shape);
  const tx0 = Math.min(...tiles.map(t => t[0])), tz0 = Math.min(...tiles.map(t => t[1]));
  const image = new Set(CANON[shape].map(([ix, iz]) => placeTile(rot, ix, iz, nx, nz, tx0, tz0).join(',')));
  const have = new Set(tiles.map(t => t.join(',')));
  if (image.size !== have.size || [...image].some(t => !have.has(t))) return null;
  const ox = -200 + 32 * tx0, oz = -200 + 32 * tz0;
  const boxW = rot === 'SOUTH' || rot === 'NORTH' ? w : d, boxD = rot === 'SOUTH' || rot === 'NORTH' ? d : w;
  return {
    shape, rot, w, d, ox, oz, boxW, boxD, tiles: have,
    /** canonical (x, z) of world column (wx, wz) (ints), or null outside the box. */
    toCanon(wx, wz) {
      const px = wx - ox, pz = wz - oz;
      if (px < 0 || px >= boxW || pz < 0 || pz >= boxD) return null;
      if (rot === 'SOUTH') return [px, pz];
      if (rot === 'NORTH') return [w - 1 - px, d - 1 - pz];
      if (rot === 'WEST') return [pz, d - 1 - px];
      return [w - 1 - pz, px];
    },
    toWorld(x, z) {
      let px, pz;
      if (rot === 'SOUTH') { px = x; pz = z; } else if (rot === 'NORTH') { px = w - 1 - x; pz = d - 1 - z; }
      else if (rot === 'WEST') { px = d - 1 - z; pz = x; } else { px = z; pz = w - 1 - x; }
      return [ox + px, oz + pz];
    },
    /** world column in one of the room's tiles or the gap between two of them. */
    owns(wx, wz) {
      const axis = v => { const t = Math.floor((v + 200) / 32); return v - (-200 + 32 * t) === 31 ? [t, t + 1] : [t]; };
      return axis(wx).every(tx => axis(wz).every(tz => have.has(tx + ',' + tz)));
    },
  };
}

export function loadPack(file) {
  const j = JSON.parse(gunzipSync(readFileSync(file)).toString());
  const byId = new Map(j.designs.map(d => [d.id, d]));
  return { j, byId, aliases: j.aliases || {} };
}

/** The pack design for an ADR room entry. */
export function designOf(pack, room) {
  const [name, type] = room;
  const id = pack.aliases[name] ?? name;
  let d = pack.byId.get(id);
  if (!d) d = pack.j.designs.find(x => x.name === name || x.odin?.name === name);
  return d || null;
}

/** Placed rooms of a recording: [{ room, design, pl }]. */
export function placedRooms(pack, rec) {
  const out = [];
  for (const room of rec.rooms || []) {
    const [name, type, shapeWord, rot, , tiles] = room;
    const shape = SHAPES[shapeWord];
    if (!shape || !tiles?.length) continue;
    const pl = place(shape, rot, tiles, type === 'FAIRY');
    if (!pl) continue;
    out.push({ name, type, design: designOf(pack, room), pl });
  }
  return out;
}

/** The placed room owning world column (x, z), with its canonical cell. */
export function roomAt(placed, x, z) {
  const wx = Math.floor(x), wz = Math.floor(z);
  for (const r of placed) if (r.pl.owns(wx, wz)) { const c = r.pl.toCanon(wx, wz); if (c) return { r, c }; }
  return null;
}

/** Players' tracks: name -> { t: Int32Array, x, y, z: Float64Array } with NaN where the player was gone (pgone). */
export function tracks(rec) {
  const gones = new Map();
  for (const [t, name] of rec.pg) { if (!gones.has(name)) gones.set(name, []); gones.get(name).push(t); }
  const out = new Map();
  for (const [name, flat] of Object.entries(rec.P)) {
    const ev = [];
    for (let i = 0; i < flat.length; i += 4) ev.push([flat[i], flat[i + 1], flat[i + 2], flat[i + 3]]);
    for (const t of gones.get(name) || []) ev.push([t + 0.5, NaN, NaN, NaN]);
    ev.sort((a, b) => a[0] - b[0]);
    out.set(name, { t: ev.map(e => e[0]), x: ev.map(e => e[1]), y: ev.map(e => e[2]), z: ev.map(e => e[3]) });
  }
  return out;
}
/** A track's position at tick t (the last entry at or before it), or null. */
export function at(tr, t) {
  let lo = 0, hi = tr.t.length - 1, k = -1;
  while (lo <= hi) { const m = (lo + hi) >> 1; if (tr.t[m] <= t) { k = m; lo = m + 1; } else hi = m - 1; }
  if (k < 0 || Number.isNaN(tr.x[k])) return null;
  return [tr.x[k], tr.y[k], tr.z[k]];
}

export function* recordings(dir, limit = Infinity) {
  let n = 0;
  for (const f of readdirSync(dir)) {
    if (!f.endsWith('.json.gz')) continue;
    if (n++ >= limit) return;
    try { yield JSON.parse(gunzipSync(readFileSync(join(dir, f))).toString()); } catch {}
  }
}
