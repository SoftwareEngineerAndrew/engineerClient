// Monte-Carlo completion of a partly discovered map, with the rules found in the recorded maps:
//  - 7 big rooms from the usual shape mixes (each shape at most twice, ~22 tiles), the rest 1x1s
//  - the doors form one tree; every unknown room hangs off one door from the discovered part
//  - 1x1 dead ends are exactly blood + trap + yellow + the tab list's puzzles (+ rares on 5-puzzle maps)
//  - the wither path is a tile walk of 9 or 10 steps from the entrance to blood, and the fairy (a 1x1)
//    is exactly 4 steps before blood
//  - trap, yellow and puzzles are spread at random over the other 1x1 dead ends
// Every sample is one complete map that keeps all of that; the predictions are what the samples agree on.
import { edgeId } from './features.mjs';

export const SHAPES = ['TwoByOne', 'ThreeByOne', 'FourByOne', 'TwoByTwo', 'L'];
const PLACEMENTS = {
  TwoByOne: [[[0, 0], [1, 0]], [[0, 0], [0, 1]]],
  ThreeByOne: [[[0, 0], [1, 0], [2, 0]], [[0, 0], [0, 1], [0, 2]]],
  FourByOne: [[[0, 0], [1, 0], [2, 0], [3, 0]], [[0, 0], [0, 1], [0, 2], [0, 3]]],
  TwoByTwo: [[[0, 0], [1, 0], [0, 1], [1, 1]]],
  L: [[[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 0], [1, 1]], [[0, 0], [0, 1], [1, 1]], [[1, 0], [0, 1], [1, 1]]],
};
const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
export const OUT = ['n', 'o', 'P', 'T', 'Y', 'B', 'F', 'R'];
const OI = Object.fromEntries(OUT.map((c, i) => [c, i]));
const isDoor = k => k === 'n' || k === 'w' || k === 'b';

// Walk length weights (entrance to blood) and blood distance-from-entrance weights; calib2.mjs fits MW.
export const TABLES2 = {
  WD: Array.from({ length: 20 }, (_, d) => d === 9 || d === 10 ? 1 : d === 7 ? 0.023 : d === 8 ? 0.06 : 0),
  MW: Array.from({ length: 16 }, (_, m) => m >= 1 ? 1 : 0),
  FM: Array.from({ length: 16 }, (_, m) => m >= 1 ? 1 : 0),
  // big-room placement weight per tile, in the frame with the entrance on the top row, left half
  BW: new Array(36).fill(1),
};

/** Fewest steps between tiles s and t (indices) inside a room's tiles. */
function inRoom(tiles, s, t, W) {
  if (s === t) return 0;
  if (tiles.length === 1) return 99;
  const seen = new Map([[s, 0]]); const q = [s];
  for (let i = 0; i < q.length; i++) {
    const u = q[i], x = u % W, z = (u / W) | 0;
    for (const [dx, dz] of DIRS) {
      const nx = x + dx, nz = z + dz; if (nx < 0 || nz < 0 || nx >= W) continue;
      const v = nz * W + nx;
      if (seen.has(v) || !tiles.includes(v)) continue;
      seen.set(v, seen.get(u) + 1);
      if (v === t) return seen.get(v);
      q.push(v);
    }
  }
  return 99;
}

/** What the map shows that doesn't change between samples. */
export function prepare(obs, combos) {
  const { W, H, tile, edge } = obs;
  const ek = (x, z, dx, dz) => edge.get(edgeId(x, z, dx, dz));
  const unknown = [];
  for (let z = 0; z < H; z++) for (let x = 0; x < W; x++) if (tile[z][x] < 0) unknown.push(z * W + x);
  const isUnk = new Uint8Array(W * H); for (const t of unknown) isUnk[t] = 1;
  // entries: unknown tile -> { kind, from: the known tile across the door }
  const entry = new Map(); let bad = false;
  for (const t of unknown) {
    const x = t % W, z = (t / W) | 0;
    for (const [dx, dz] of DIRS) {
      const nx = x + dx, nz = z + dz;
      if (nx < 0 || nz < 0 || nx >= W || nz >= H || isUnk[nz * W + nx]) continue;
      const k = ek(x, z, dx, dz);
      if (!isDoor(k)) continue;
      if (entry.has(t)) bad = true; // two doors into one unknown tile from the known side: no tree does that
      entry.set(t, { kind: k, from: nz * W + nx });
    }
  }
  const found = { P: 0, T: 0, Y: 0, B: 0, F: 0, R: 0 };
  const knownBig = new Array(5).fill(0);
  let entId = -1;
  for (const [id, r] of obs.rooms) {
    const si = SHAPES.indexOf(r.shape); if (si >= 0) knownBig[si]++;
    if (found[r.cls] !== undefined) found[r.cls]++;
    if (r.type === 'ENTRANCE') entId = id;
  }
  // the known part of the wither path, as a tile walk from the entrance
  const roomTiles = id => obs.rooms.get(id).tiles.map(q => q.z * W + q.x);
  const doorsOf = id => {
    const out = [];
    for (const q of obs.rooms.get(id).tiles) for (const [dx, dz] of DIRS) {
      const nx = q.x + dx, nz = q.z + dz;
      if (nx < 0 || nz < 0 || nx >= W || nz >= H) continue;
      const o = tile[nz][nx]; if (o === id) continue;
      const k = ek(q.x, q.z, dx, dz);
      if (isDoor(k)) out.push({ to: o, kind: k, mine: q.z * W + q.x, theirs: nz * W + nx });
    }
    return out;
  };
  let head = -1, headDist = -1, fairyDist = -1, pathKnown = 0;
  if (entId >= 0) {
    const et = obs.rooms.get(entId).tiles[0];
    let cur = entId, at = et.z * W + et.x, dist = 0;
    const onPath = new Set([entId]);
    for (let step = 0; step < 40; step++) {
      pathKnown++;
      if (obs.rooms.get(cur).type === 'FAIRY') fairyDist = dist;
      // the next path door: the entrance's one door, then wither / blood doors
      const ds = doorsOf(cur).filter(d => step === 0 || d.kind === 'w' || d.kind === 'b').filter(d => !onPath.has(d.to));
      if (!ds.length) break;
      const d = ds[0];
      const nd = dist + inRoom(roomTiles(cur), at, d.mine, W) + 1;
      if (d.to < 0) { head = d.theirs; headDist = nd; break; }
      onPath.add(d.to); cur = d.to; at = d.theirs; dist = nd;
    }
  }
  const remP = Math.max(0, obs.puzzles - found.P);
  const needDead = remP + (found.T ? 0 : 1) + (found.Y ? 0 : 1) + (found.B ? 0 : 1);
  const okCombos = combos.filter(c => c.counts.every((v, i) => v >= knownBig[i]));
  let ent = [0, 0]; if (entId >= 0) { const e = obs.rooms.get(entId).tiles[0]; ent = [e.x, e.z]; }
  // each tile's index in the entrance frame (6x6 only; other sizes keep their own)
  const frame = new Int32Array(W * H);
  {
    const SYM = [(x, z) => [x, z], (x, z) => [5 - z, x], (x, z) => [5 - x, 5 - z], (x, z) => [z, 5 - x], (x, z) => [5 - x, z], (x, z) => [z, x], (x, z) => [x, 5 - z], (x, z) => [5 - z, 5 - x]];
    let f = SYM[0];
    if (W === 6 && H === 6) for (const g of SYM) { const [x, z] = g(ent[0], ent[1]); if (z === 0 && x <= 2) { f = g; break; } }
    for (let t = 0; t < W * H; t++) { const [x, z] = f(t % W, (t / W) | 0); frame[t] = z * W + x; }
  }
  return { W, H, unknown, isUnk, entry, bad, found, knownBig, head, headDist, fairyDist, pathKnown, remP, needDead, okCombos, ent, frame, puzzles: obs.puzzles };
}

export function sample(obs, combos, opts) {
  const P0 = prepare(obs, combos);
  const { W, H, unknown, isUnk, entry, found, knownBig, head, headDist, fairyDist, remP, needDead, okCombos, ent, frame } = P0;
  const rnd = opts.rnd, N = opts.samples || 500, TB = opts.tables || TABLES2;
  const entries = [...entry.keys()];
  const entryIndex = new Map(entries.map((t, i) => [t, i]));
  const tileIdx = new Int32Array(W * H).fill(-1); unknown.forEach((t, i) => { tileIdx[t] = i; });
  const counts = unknown.map(() => new Float64Array(OUT.length));
  const branch = entries.map(() => ({ tiles: 0, rooms: 0, c: new Float64Array(OUT.length) }));
  const result = { accepted: 0, tries: 0, unknown, entries: entries.map(t => ({ t, kind: entry.get(t).kind })), probs: null, branch: null };
  const finish = () => {
    const a = result.weight || 1;
    result.probs = counts.map(c => { const s = c.reduce((x, y) => x + y, 0); return s ? Array.from(c, v => v / s) : null; });
    result.branch = branch.map(b => ({ rooms: b.rooms / a, tiles: b.tiles / a, c: Array.from(b.c, v => v / a) }));
    return result;
  };
  if (P0.bad || !okCombos.length || (!found.B && head < 0)) return finish();
  const cw = okCombos.map(c => c.w); const cwSum = cw.reduce((a, b) => a + b, 0);
  const owner = new Int32Array(W * H);
  const maxTries = opts.maxTries || N * 80;

  while (result.accepted < N && result.tries < maxTries) {
    result.tries++;
    // 1. the big rooms still to come, placed biggest first; the rest are 1x1s
    let s = rnd() * cwSum, ci = 0; while (s > cw[ci] && ci < okCombos.length - 1) s -= cw[ci++];
    const todo = [];
    okCombos[ci].counts.forEach((v, i) => { for (let k = knownBig[i]; k < v; k++) todo.push(SHAPES[i]); });
    todo.sort((a, b) => PLACEMENTS[b][0].length - PLACEMENTS[a][0].length || rnd() - 0.5);
    owner.fill(-1);
    const rooms = [];
    let fail = false;
    for (const shape of todo) {
      const cands = [], cw2 = []; let cwT = 0;
      for (const cells of PLACEMENTS[shape]) for (const t0 of unknown) {
        const x0 = t0 % W, z0 = (t0 / W) | 0;
        let ok = true, ents = 0; const tiles = [];
        for (const [dx, dz] of cells) {
          const x = x0 + dx, z = z0 + dz;
          if (x >= W || z >= H) { ok = false; break; }
          const t = z * W + x;
          if (!isUnk[t] || owner[t] >= 0) { ok = false; break; }
          if (entry.has(t)) ents++;
          tiles.push(t);
        }
        if (ok && ents <= 1) { let w = 1; for (const t of tiles) w *= TB.BW[frame[t]] ?? 1; cands.push(tiles); cw2.push(w); cwT += w; }
      }
      if (!cands.length) { fail = true; break; }
      let s4 = rnd() * cwT, pk = 0; while (s4 > cw2[pk] && pk < cands.length - 1) s4 -= cw2[pk++];
      const pick = cands[pk];
      for (const t of pick) owner[t] = rooms.length;
      rooms.push({ tiles: pick, shape });
    }
    if (fail) continue;
    for (const t of unknown) if (owner[t] < 0) { owner[t] = rooms.length; rooms.push({ tiles: [t], shape: 'OneByOne' }); }
    const R = rooms.length;
    // 2. which rooms touch, and where a door between them could go
    const slots = Array.from({ length: R }, () => new Map()); // neighbour -> [[tile in me, tile in them]]
    for (const t of unknown) {
      const x = t % W, z = (t / W) | 0;
      for (const n of [x + 1 < W ? t + 1 : -1, z + 1 < H ? t + W : -1]) {
        if (n < 0 || !isUnk[n] || owner[n] === owner[t]) continue;
        const a = owner[t], b = owner[n];
        if (!slots[a].has(b)) { slots[a].set(b, []); slots[b].set(a, []); }
        slots[a].get(b).push([t, n]); slots[b].get(a).push([n, t]);
      }
    }
    // 3. the door tree, grown from the entry rooms (random door from the tree to a new room)
    const parent = new Int32Array(R).fill(-2), inTile = new Int32Array(R), outTile = new Int32Array(R), root = new Int32Array(R).fill(-1);
    const order = [];
    for (const t of entries) { const r = owner[t]; parent[r] = -1; inTile[r] = t; root[r] = entryIndex.get(t); order.push(r); }
    const grow = opts.grow ?? 0.3;
    while (order.length < R) {
      let u = -1, v = -1;
      if (rnd() < grow) {
        const last = order[order.length - 1];
        const fr = [...slots[last].keys()].filter(w => parent[w] === -2);
        if (fr.length) { u = last; v = fr[Math.floor(rnd() * fr.length)]; }
      }
      if (u < 0) {
        let cnt = 0;
        for (const a of order) for (const b of slots[a].keys()) if (parent[b] === -2) { cnt++; if (rnd() * cnt < 1) { u = a; v = b; } }
      }
      if (u < 0) break; // a pocket no entry reaches
      const sl = slots[u].get(v); const [tu, tv] = sl[Math.floor(rnd() * sl.length)];
      parent[v] = u; outTile[v] = tu; inTile[v] = tv; root[v] = root[u]; order.push(v);
    }
    if (order.length < R) continue;
    const deg = new Int32Array(R);
    for (let r = 0; r < R; r++) { deg[r]++; if (parent[r] >= 0) deg[parent[r]]++; }
    const dead = [];
    for (let r = 0; r < R; r++) if (rooms[r].shape === 'OneByOne' && deg[r] === 1) dead.push(r);
    if (dead.length !== needDead) {
      const extra = dead.length - needDead;
      if (!(P0.puzzles === 5 && extra > 0 && extra <= 2 && rnd() < (opts.rareRate ?? 0.25))) continue;
    }
    // 4. blood: a 1x1 dead end in the wither door's branch at walk distance 9-10 from the entrance,
    //    with a 1x1 fairy exactly 4 steps before it
    const type = new Array(R).fill(null);
    let weight = 1; // how likely this map is next to the others: the summed weight of its blood spots
    if (!found.B) {
      const hb = entryIndex.get(head), hr = owner[head];
      // walk distance at each head-branch room's entry tile
      const dist = new Int32Array(R).fill(-1);
      for (const r of order) {
        if (root[r] !== hb) continue;
        if (r === hr) dist[r] = headDist;
        else dist[r] = dist[parent[r]] + inRoom(rooms[parent[r]].tiles, inTile[parent[r]], outTile[r], W) + 1;
      }
      const cands = [], ws = [], fairies = [];
      for (const b of dead) {
        if (root[b] !== hb) continue;
        if ((entry.get(head).kind === 'b') !== (b === hr)) continue;
        const D = dist[b];
        let w = TB.WD[D] || 0; if (!w) continue;
        let fairy = -1;
        if (found.F) { if (D !== fairyDist + 4) continue; }
        else {
          // the room on blood's chain whose entry tile is at D - 4, and it must be a 1x1
          for (let u = parent[b]; u >= 0; u = parent[u]) if (dist[u] === D - 4 && rooms[u].shape === 'OneByOne') { fairy = u; break; }
          if (fairy < 0) continue;
        }
        const bt = rooms[b].tiles[0];
        w *= TB.MW[Math.abs(bt % W - ent[0]) + Math.abs(((bt / W) | 0) - ent[1])] || 0;
        if (fairy >= 0) { const ft = rooms[fairy].tiles[0]; w *= TB.FM[Math.abs(ft % W - ent[0]) + Math.abs(((ft / W) | 0) - ent[1])] || 0; }
        if (!w) continue;
        cands.push(b); ws.push(w); fairies.push(fairy);
      }
      if (!cands.length) continue;
      const tot = ws.reduce((a, b) => a + b, 0);
      weight = tot;
      let s2 = rnd() * tot, k = 0; while (s2 > ws[k] && k < cands.length - 1) s2 -= ws[k++];
      type[cands[k]] = 'B';
      if (!found.F) type[fairies[k]] = 'F';
      result.lastBlood = { D: dist[cands[k]] };
    } else if (!found.F) continue;
    // 5. trap, yellow, puzzles, rares on the other dead ends, at random
    const rest = dead.filter(r => !type[r]);
    for (let i = rest.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [rest[i], rest[j]] = [rest[j], rest[i]]; }
    let ri = 0;
    if (!found.T) type[rest[ri++]] = 'T';
    if (!found.Y) type[rest[ri++]] = 'Y';
    for (let k = 0; k < remP; k++) type[rest[ri++]] = 'P';
    while (ri < rest.length) type[rest[ri++]] = 'R';
    result.accepted++; result.weight = (result.weight || 0) + weight;
    if (opts.onSample) opts.onSample({ rooms, type, parent, root, R, ent, weight });
    for (let r = 0; r < R; r++) {
      const c = OI[type[r] || (rooms[r].shape === 'OneByOne' ? 'o' : 'n')];
      const b = branch[root[r]]; b.rooms += weight; b.tiles += weight * rooms[r].tiles.length; b.c[c] += weight;
      for (const t of rooms[r].tiles) counts[tileIdx[t]][c] += weight;
    }
  }
  return finish();
}
