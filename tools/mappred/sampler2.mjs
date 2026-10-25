// Monte-Carlo completion of a partly discovered map, with the rules found in the recorded maps (floors.mjs;
// each floor's numbers are common.mjs FLOORS):
//  - the floor's big rooms from its usual shape mixes (each shape at most twice), the rest 1x1s
//  - the doors form one tree; every unknown room hangs off one door from the discovered part
//  - 1x1 dead ends are exactly blood + trap + yellow + the tab list's puzzles (+ rares on 5-puzzle maps)
//  - the wither path is a tile walk of 9 or 10 steps from the entrance to blood (7 or 8 on F1-F4), and the
//    fairy (a 1x1) is exactly 4 steps before blood (3 on F1-F4)
//  - trap, yellow and puzzles are spread at random over the other 1x1 dead ends
//  - F4-F6: the dead ends the main grid is short of are puzzles in the column east of it, each behind one
//    door from a normal room west of it; none when the tab list's puzzles are more than that tops up to
// Every sample is one complete map that keeps all of that; the predictions are what the samples agree on.
import { edgeId } from './features.mjs';
import { FLOORS } from './common.mjs';

export const SHAPES = ['TwoByOne', 'ThreeByOne', 'FourByOne', 'TwoByTwo', 'L'];
export const PLACEMENTS = {
  TwoByOne: [[[0, 0], [1, 0]], [[0, 0], [0, 1]]],
  ThreeByOne: [[[0, 0], [1, 0], [2, 0]], [[0, 0], [0, 1], [0, 2]]],
  FourByOne: [[[0, 0], [1, 0], [2, 0], [3, 0]], [[0, 0], [0, 1], [0, 2], [0, 3]]],
  TwoByTwo: [[[0, 0], [1, 0], [0, 1], [1, 1]]],
  // never with its north-west corner missing (no recorded map has one)
  L: [[[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 0], [1, 1]], [[0, 0], [0, 1], [1, 1]]],
};
// opts.fourL: also the L the recorded maps never have (the old placements, to compare against)
const PLACEMENTS_4L = { ...PLACEMENTS, L: [...PLACEMENTS.L, [[1, 0], [0, 1], [1, 1]]] };
const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
// x: a puzzle column tile with no room
export const OUT = ['n', 'o', 'P', 'T', 'Y', 'B', 'F', 'R', 'x'];
const OI = Object.fromEntries(OUT.map((c, i) => [c, i]));
const isDoor = k => k === 'n' || k === 'w' || k === 'b';

/** Each floor's big room shape mixes (counts in SHAPES order) and how many maps had them: F3/F4 and F5/F6 alike, F7's 7-room maps only. */
export function combosFor(Ls) {
  const group = f => ({ 3: '34', 4: '34', 5: '56', 6: '56' })[f] ?? f;
  const by = {};
  for (const L of Ls) {
    const c = SHAPES.map(s => L.rooms.filter(r => r.shape === s).length);
    if (L.floor[1] === '7' && c.reduce((a, b) => a + b) !== 7) continue;
    const m = by[group(L.floor[1])] ||= new Map(); const k = c.join(''); m.set(k, (m.get(k) || 0) + 1);
  }
  const out = {};
  for (const f of '1234567') { const m = by[group(f)]; if (m) out[f] = [...m].map(([k, w]) => ({ counts: [...k].map(Number), w })); }
  return out;
}

/** Walk length weights (entrance to blood): the floor's two lengths, the two shorter ones rarely (a big room's corner cut). */
export function walkWeights(rules) {
  const top = rules.fairy === 3 ? 7 : 9;
  return Array.from({ length: 20 }, (_, d) => d === top || d === top + 1 ? 1 : d === top - 2 ? 0.023 : d === top - 1 ? 0.06 : 0);
}

/** Flat tables for a floor; calib2.mjs fits MW (blood distance from the entrance), FM (the fairy's) and BW. */
export function defaultTables(rules = FLOORS[7]) {
  return {
    WD: walkWeights(rules),
    MW: Array.from({ length: 16 }, (_, m) => m >= 1 ? 1 : 0),
    FM: Array.from({ length: 16 }, (_, m) => m >= 1 ? 1 : 0),
    // big-room placement weight per tile, in the frame with the entrance on the top row, left half (F7 only)
    BW: new Array(36).fill(1),
  };
}
export const TABLES2 = defaultTables();

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
  const rules = obs.rules || FLOORS[7];
  const ek = (x, z, dx, dz) => edge.get(edgeId(x, z, dx, dz));
  // unknown tiles of the main grid; puzzle column tiles behind a door the map shows (a room for sure), and
  // those whose west side nobody has seen (a room or not); a column tile with a wall to its west is empty
  const unknown = [], column = [], open = [], cells = [];
  for (let z = 0; z < H; z++) for (let x = 0; x < W; x++) {
    if (tile[z][x] >= 0) continue;
    const t = z * W + x;
    if (x < rules.w) { unknown.push(t); cells.push(t); }
    else if (rules.column && x === rules.w) {
      if (tile[z][x - 1] < 0) { open.push(t); cells.push(t); }
      else if (isDoor(ek(x, z, -1, 0))) { column.push(t); cells.push(t); }
    }
  }
  const isUnk = new Uint8Array(W * H); for (const t of unknown) isUnk[t] = 1;
  // entries: unknown tile -> { kind, from: the known tile across the door }
  const entry = new Map(); let bad = false;
  for (const t of [...unknown, ...column]) {
    const x = t % W, z = (t / W) | 0;
    for (const [dx, dz] of DIRS) {
      const nx = x + dx, nz = z + dz;
      if (nx < 0 || nz < 0 || nx >= W || nz >= H || tile[nz][nx] < 0) continue;
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
  let head = -1, headDist = -1, fairyDist = -1, pathKnown = 0, headK = 0;
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
      // a 2x2 entered and left by neighbouring cells: the game's walk may go round inside it (+2)
      if (obs.rooms.get(cur).shape === 'TwoByTwo' && Math.abs(at % W - d.mine % W) + Math.abs(((at / W) | 0) - ((d.mine / W) | 0)) === 1) headK++;
      if (d.to < 0) { head = d.theirs; headDist = nd; break; }
      onPath.add(d.to); cur = d.to; at = d.theirs; dist = nd;
    }
  }
  const remP = Math.max(0, obs.puzzles - found.P);
  const needDead = remP + (rules.trap && !found.T ? 1 : 0) + (found.Y ? 0 : 1) + (found.B ? 0 : 1);
  // the puzzle column only tops the dead ends (puzzles, trap, yellow) up to its target, never past it
  const topUp = !!rules.column && obs.puzzles + (rules.trap ? 1 : 0) + 1 <= rules.target;
  const okCombos = combos.filter(c => c.counts.every((v, i) => v >= knownBig[i]));
  let ent = [0, 0]; if (entId >= 0) { const e = obs.rooms.get(entId).tiles[0]; ent = [e.x, e.z]; }
  // each tile's index in the entrance frame (F7's 6x6 only; other floors keep their own)
  const frame = new Int32Array(W * H);
  {
    const SYM = [(x, z) => [x, z], (x, z) => [5 - z, x], (x, z) => [5 - x, 5 - z], (x, z) => [z, 5 - x], (x, z) => [5 - x, z], (x, z) => [z, x], (x, z) => [x, 5 - z], (x, z) => [5 - z, 5 - x]];
    let f = SYM[0];
    if (rules.w === 6 && rules.h === 6) for (const g of SYM) { const [x, z] = g(ent[0], ent[1]); if (z === 0 && x <= 2) { f = g; break; } }
    for (let t = 0; t < W * H; t++) { const [x, z] = f(t % W, (t / W) | 0); frame[t] = z * W + x; }
  }
  // the discovered tiles that are 1x1 rooms, the ones in big rooms, the entrance's
  const oneAt = new Uint8Array(W * H), bigAt = new Uint8Array(W * H); let entTile = -1;
  for (let z = 0; z < H; z++) for (let x = 0; x < W; x++) { const i = tile[z][x]; if (i < 0) continue; const r = obs.rooms.get(i); if (r?.tiles.length === 1) oneAt[z * W + x] = 1; else if (r) bigAt[z * W + x] = 1; if (r?.type === 'ENTRANCE') entTile = z * W + x; }
  return { W, H, rules, unknown, column, open, cells, isUnk, entry, bad, found, knownBig, head, headDist, fairyDist, pathKnown, remP, needDead, topUp, okCombos, ent, frame, oneAt, bigAt, entTile, headK, puzzles: obs.puzzles };
}

export function sample(obs, combos, opts) {
  const P0 = prepare(obs, combos);
  const { W, H, rules, unknown, column, open, cells, isUnk, entry, found, knownBig, head, headDist, fairyDist, remP, needDead, topUp, okCombos, ent, frame } = P0;
  const rnd = opts.rnd, N = opts.samples || 500, TB = opts.tables || defaultTables(rules);
  const entries = [...entry.keys()];
  const entryIndex = new Map(entries.map((t, i) => [t, i]));
  const tileIdx = new Int32Array(W * H).fill(-1); cells.forEach((t, i) => { tileIdx[t] = i; });
  const counts = cells.map(() => new Float64Array(OUT.length));
  const branch = entries.map(() => ({ tiles: 0, rooms: 0, c: new Float64Array(OUT.length) }));
  const result = { accepted: 0, tries: 0, unknown: cells, entries: entries.map(t => ({ t, kind: entry.get(t).kind })), probs: null, branch: null };
  const finish = () => {
    const a = result.weight || 0;
    // a puzzle column tile no sample put a room on is empty ('x')
    result.probs = counts.map(c => { if (!a) return null; const p = Array.from(c, v => v / a); p[OI.x] = Math.max(0, 1 - p.reduce((x, y) => x + y, 0)); return p; });
    result.branch = branch.map(b => ({ rooms: b.rooms / (a || 1), tiles: b.tiles / (a || 1), c: Array.from(b.c, v => v / (a || 1)) }));
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
    // opts.order 'rnd': the big rooms in random order (else biggest first, ties random)
    if (opts.order === 'rnd') for (let i = todo.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [todo[i], todo[j]] = [todo[j], todo[i]]; }
    else todo.sort((a, b) => PLACEMENTS[b][0].length - PLACEMENTS[a][0].length || rnd() - 0.5);
    owner.fill(-1);
    // opts.reserve: that many of the main grid's cells (scaled to the unknown ones) kept free of big rooms while
    // they're placed, as the real layouts look
    if (opts.reserve) { const k = Math.round(opts.reserve * unknown.length / (rules.w * H - 1)); const cs = [...unknown];
      for (let i = 0; i < k && i < cs.length; i++) { const j = i + Math.floor(rnd() * (cs.length - i)); [cs[i], cs[j]] = [cs[j], cs[i]]; owner[cs[i]] = -3; } }
    // opts.special: the special rooms still to come (puzzles, trap, yellow) put down first on random cells, the
    // big rooms placed round them, and the door tree never going on from one (each a dead end); blood is then
    // the one other dead end
    const nSpecial = opts.special ? remP + (rules.trap && !found.T ? 1 : 0) + (found.Y ? 0 : 1) : 0;
    if (nSpecial) { const cs = unknown.filter(t => owner[t] === -1); if (cs.length < nSpecial) continue;
      for (let i = 0; i < nSpecial; i++) { const j = i + Math.floor(rnd() * (cs.length - i)); [cs[i], cs[j]] = [cs[j], cs[i]]; owner[cs[i]] = -4; } }
    const rooms = [];
    let fail = false, tryW = 1;
    for (const shape of todo) {
      const cands = [], cw2 = [], cAt = []; let cwT = 0;
      const AW = TB.AW?.[shape];
      (opts.fourL ? PLACEMENTS_4L : PLACEMENTS)[shape].forEach((cells, oi) => { for (const t0 of unknown) {
        const x0 = t0 % W, z0 = (t0 / W) | 0;
        let ok = true, ents = 0; const tiles = [];
        for (const [dx, dz] of cells) {
          const x = x0 + dx, z = z0 + dz;
          if (x >= W || z >= H) { ok = false; break; }
          const t = z * W + x;
          if (!isUnk[t] || owner[t] !== -1) { ok = false; break; }
          if (entry.has(t)) ents++;
          tiles.push(t);
        }
        if (ok && ents <= 1) {
          let w = 1; for (const t of tiles) w *= TB.BW[frame[t]] ?? 1;
          // opts.place [edge, big, entrance, distance]: e^(b . features) of the spot, from the rooms so far
          // (each tile side on the map's edge, each side on a big room, touching the entrance, tiles from it)
          if (opts.place) {
            const set = new Set(tiles); let edgeS = 0, bigS = 0, entT = 0, cx = 0, cz = 0;
            for (const t of tiles) { const x = t % W, z = (t / W) | 0; cx += x; cz += z;
              for (const [dx, dz] of DIRS) { const nx = x + dx, nz = z + dz; if (nx < 0 || nz < 0 || nx >= rules.w || nz >= H) { edgeS++; continue; } const u = nz * W + nx; if (set.has(u)) continue;
                if (isUnk[u] ? owner[u] >= 0 : P0.bigAt[u]) bigS++; if (u === P0.entTile) entT = 1; } }
            const d = ent ? Math.abs(cx / tiles.length - ent[0]) + Math.abs(cz / tiles.length - ent[1]) : 0;
            const b = opts.place; w *= Math.exp(b[0] * edgeS + b[1] * bigS + b[2] * entT + b[3] * d);
          }
          // AW: each shape's own weight per orientation and top-left tile, in the map's own directions
          if (AW?.[oi]) w *= AW[oi][t0] ?? 1;
          cands.push(tiles); cw2.push(w); cAt.push([oi, t0]); cwT += w;
        }
      } });
      if (!cands.length) { fail = true; break; }
      // opts.tries K: the game tries K random spots inside the map and starts over if none fits, so this sample
      // counts as likely as K tries finding a spot (K = 1: every complete layout alike)
      if (opts.tries) { let T = 0; for (const o of PLACEMENTS[shape]) { const w2 = Math.max(...o.map(c => c[0])) + 1, h2 = Math.max(...o.map(c => c[1])) + 1; T += Math.max(0, rules.w - w2 + 1) * Math.max(0, H - h2 + 1); }
        tryW *= 1 - (1 - Math.min(1, cands.length / T)) ** opts.tries; }
      let s4 = rnd() * cwT, pk = 0; while (s4 > cw2[pk] && pk < cands.length - 1) s4 -= cw2[pk++];
      const pick = cands[pk];
      for (const t of pick) owner[t] = rooms.length;
      rooms.push({ tiles: pick, shape, orient: cAt[pk][0], anchor: cAt[pk][1] });
    }
    if (fail) continue;
    for (const t of unknown) if (owner[t] === -3) owner[t] = -1;
    for (const t of unknown) if (owner[t] < 0) { const sp = owner[t] === -4; owner[t] = rooms.length; rooms.push({ tiles: [t], shape: 'OneByOne', special: sp }); }
    // opts.bunch: maps with more 2x2 blocks of 1x1 rooms weigh more (exp(bunch * blocks))
    let layoutWeight = tryW;
    if (opts.bunch) {
      const one = t => isUnk[t] ? rooms[owner[t]].shape === 'OneByOne' : P0.oneAt[t];
      let q = 0; for (let z = 0; z + 1 < H; z++) for (let x = 0; x + 1 < rules.w; x++) { const t = z * W + x; if (one(t) && one(t + 1) && one(t + W) && one(t + W + 1)) q++; }
      layoutWeight = Math.exp(opts.bunch * q);
    }
    // the puzzle column's rooms behind a door the map shows
    for (const t of column) { owner[t] = rooms.length; rooms.push({ tiles: [t], shape: 'OneByOne', col: true }); }
    const R = rooms.length;
    // 2. which rooms touch, and where a door between them could go (the main grid's rooms only)
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
    // 3. the door tree, grown from the entry rooms (random door from the tree to a new room); room arrays
    //    leave space for the puzzle column rooms added in 5
    const RM = R + open.length;
    const parent = new Int32Array(RM).fill(-2), inTile = new Int32Array(RM), outTile = new Int32Array(RM), root = new Int32Array(RM).fill(-1);
    const order = [];
    for (const t of entries) { const r = owner[t]; parent[r] = -1; inTile[r] = t; root[r] = entryIndex.get(t); order.push(r); }
    const grow = opts.grow ?? 0.3;
    // opts.oldest: with this chance the door comes off the oldest room that still has a free neighbour (the
    // real trees are shallow: tools/mappred README, the generator)
    const oldest = opts.oldest ?? 0; let oldHead = 0;
    while (order.length < R) {
      let u = -1, v = -1;
      if (oldest && rnd() < oldest) {
        while (oldHead < order.length) { const a = order[oldHead]; const fr = rooms[a]?.special ? [] : [...slots[a].keys()].filter(w => parent[w] === -2); if (fr.length) { u = a; v = fr[Math.floor(rnd() * fr.length)]; break; } oldHead++; }
      }
      if (u < 0 && rnd() < grow) {
        const last = order[order.length - 1];
        const fr = rooms[last]?.special ? [] : [...slots[last].keys()].filter(w => parent[w] === -2);
        if (fr.length) { u = last; v = fr[Math.floor(rnd() * fr.length)]; }
      }
      if (u < 0) {
        let cnt = 0;
        for (const a of order) if (!rooms[a]?.special) for (const b of slots[a].keys()) if (parent[b] === -2) { cnt++; if (rnd() * cnt < 1) { u = a; v = b; } }
      }
      if (u < 0) break; // a pocket no entry reaches
      const sl = slots[u].get(v); const [tu, tv] = sl[Math.floor(rnd() * sl.length)];
      parent[v] = u; outTile[v] = tu; inTile[v] = tv; root[v] = root[u]; order.push(v);
    }
    if (order.length < R) continue;
    const deg = new Int32Array(RM);
    for (let r = 0; r < R; r++) { deg[r]++; if (parent[r] >= 0) deg[parent[r]]++; }
    const dead = [];
    for (let r = 0; r < R; r++) if (rooms[r].shape === 'OneByOne' && deg[r] === 1) dead.push(r);
    // dead ends past the specials are rare rooms (5-puzzle maps); short of them, the puzzle column makes up the rest
    let short = needDead - dead.length;
    if (short < 0) {
      if (!(P0.puzzles === 5 && short >= -2 && rnd() < (opts.rareRate ?? 0.25))) continue;
      short = 0;
    }
    if (short > 0 && !topUp || column.length && !topUp) continue;
    // 4. blood: a 1x1 dead end of the main grid in the wither door's branch at the floor's walk distance from
    //    the entrance, with a 1x1 fairy exactly the floor's steps before it
    const type = new Array(RM).fill(null);
    let weight = layoutWeight; // how likely this map is next to the others: the summed weight of its blood spots
    if (!found.B) {
      const hb = entryIndex.get(head), hr = owner[head];
      // walk distance at each head-branch room's entry tile
      const dist = new Int32Array(R).fill(-1), kk = new Int32Array(R);
      const adj1 = (a, b) => Math.abs(a % W - b % W) + Math.abs(((a / W) | 0) - ((b / W) | 0)) === 1;
      for (const r of order) {
        if (root[r] !== hb) continue;
        if (r === hr) { dist[r] = headDist; kk[r] = P0.headK; }
        else { const pr = parent[r]; dist[r] = dist[pr] + inRoom(rooms[pr].tiles, inTile[pr], outTile[r], W) + 1; kk[r] = kk[pr] + (rooms[pr].shape === 'TwoByTwo' && adj1(inTile[pr], outTile[r]) ? 1 : 0); }
      }
      const cands = [], ws = [], fairies = [];
      for (const b of dead) {
        if (root[b] !== hb || rooms[b].col || rooms[b].special) continue;
        if ((entry.get(head).kind === 'b') !== (b === hr)) continue;
        const D = dist[b];
        // opts.detour (pi): the walk counted the game's way - each 2x2 on the way entered and left by neighbouring
        // cells goes round (+2) with chance pi, and the walk has to come to 9 or 10 (the floor's two lengths)
        let w;
        if (opts.detour !== undefined) { const top = rules.fairy === 3 ? 7 : 9, pi = opts.detour, k = kk[b];
          w = D === top || D === top + 1 ? (1 - pi) ** k : D === top - 2 || D === top - 1 ? k * pi * (1 - pi) ** Math.max(k - 1, 0) : 0; }
        else w = TB.WD[D] || 0;
        if (!w) continue;
        let fairy = -1;
        if (found.F) { if (D !== fairyDist + rules.fairy) continue; }
        else {
          // the room on blood's chain whose entry tile is the floor's steps before it, and it must be a 1x1
          for (let u = parent[b]; u >= 0; u = parent[u]) if (dist[u] === D - rules.fairy && rooms[u].shape === 'OneByOne') { fairy = u; break; }
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
      weight *= tot;
      let s2 = rnd() * tot, k = 0; while (s2 > ws[k] && k < cands.length - 1) s2 -= ws[k++];
      type[cands[k]] = 'B';
      if (!found.F) type[fairies[k]] = 'F';
      result.lastBlood = { D: dist[cands[k]] };
    } else if (!found.F) continue;
    // 5. the puzzle column rooms still short, at random beside normal rooms (big, or a 1x1 with two doors already)
    const colRooms = dead.filter(r => rooms[r].col);
    if (short > 0) {
      const hosts = open.filter(t => { const h = owner[t - 1]; return rooms[h].shape !== 'OneByOne' || deg[h] >= 2 && type[h] !== 'F'; });
      if (hosts.length < short) continue;
      for (let i = 0; i < short; i++) {
        const j = i + Math.floor(rnd() * (hosts.length - i)); [hosts[i], hosts[j]] = [hosts[j], hosts[i]];
        const t = hosts[i], h = owner[t - 1], r = rooms.length;
        owner[t] = r; rooms.push({ tiles: [t], shape: 'OneByOne', col: true });
        parent[r] = h; outTile[r] = t - 1; inTile[r] = t; root[r] = root[h]; deg[r] = 1; deg[h]++;
        colRooms.push(r);
      }
    }
    // 6. trap, yellow, puzzles, rares on the other dead ends, at random; the puzzle column's last
    const rest = dead.filter(r => !type[r] && !rooms[r].col);
    for (let i = rest.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [rest[i], rest[j]] = [rest[j], rest[i]]; }
    for (let i = colRooms.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [colRooms[i], colRooms[j]] = [colRooms[j], colRooms[i]]; }
    rest.push(...colRooms);
    let ri = 0;
    if (rules.trap && !found.T) type[rest[ri++]] = 'T';
    if (!found.Y) type[rest[ri++]] = 'Y';
    for (let k = 0; k < remP; k++) type[rest[ri++]] = 'P';
    while (ri < rest.length) type[rest[ri++]] = 'R';
    result.accepted++; result.weight = (result.weight || 0) + weight;
    if (opts.onSample) opts.onSample({ rooms, type, parent, root, R: rooms.length, ent, weight });
    for (let r = 0; r < rooms.length; r++) {
      const c = OI[type[r] || (rooms[r].shape === 'OneByOne' ? 'o' : 'n')];
      const b = branch[root[r]]; b.rooms += weight; b.tiles += weight * rooms[r].tiles.length; b.c[c] += weight;
      for (const t of rooms[r].tiles) counts[tileIdx[t]][c] += weight;
    }
  }
  return finish();
}
