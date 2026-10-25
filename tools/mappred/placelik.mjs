// How are the big rooms placed? The exact chance of each real map's 7 big rooms (given its entrance and its
// shapes) under candidate placement procedures, summed over every order they could have gone in (a DP over
// the subsets placed so far), over the successful runs only (a run that gets stuck is started again).
//   LAYOUTS=layouts.jsonl node placelik.mjs [maps]
//
// order: big (biggest first, ties random), rnd, small, or a fixed shape order (each shape's copies random);
// where: uni (any spot that fits alike), anchor (a free top-left cell where it fits, then a way round that
//        fits there), cell (a free cell where some shape left fits, then a room that fits there, then a way),
//        joint (a room left and a spot it fits drawn together, every such pair alike: what "a random room, cell
//        and way round, tried until one fits" gives - rooms with more spots, more ways round, go sooner),
//        try<K> (the room's order as given; K tries at a random spot inside the map, the whole run started again
//        if none fits: K = 1 makes every complete layout alike, K large is uni);
// held:  k random cells kept free while placing (k = 0 none).
// ACC=n: also over the runs the door tree would keep (the map made again unless its dead ends come out as the
// real map's count with a blood spot 9-10 steps out and a 1x1 fairy 4 before it): n layouts per model, a few
// trees each (Prim, newest-first 0.3, the entrance's one door inward) - the real layout's own chance of that
// is the same under every model, so only how often each model's layouts pass moves the comparison.
import { complete } from './common.mjs';
import { makeRng } from './mlp.mjs';
const W = 6, N = 36;
const rnd = makeRng(+(process.env.SEED || 7));
const SHAPES = ['TwoByOne', 'ThreeByOne', 'FourByOne', 'TwoByTwo', 'L'];
const SIZE = { TwoByOne: 2, ThreeByOne: 3, FourByOne: 4, TwoByTwo: 4, L: 3 };
const ORIENT = {
  TwoByOne: [[[0, 0], [1, 0]], [[0, 0], [0, 1]]],
  ThreeByOne: [[[0, 0], [1, 0], [2, 0]], [[0, 0], [0, 1], [0, 2]]],
  FourByOne: [[[0, 0], [1, 0], [2, 0], [3, 0]], [[0, 0], [0, 1], [0, 2], [0, 3]]],
  TwoByTwo: [[[0, 0], [1, 0], [0, 1], [1, 1]]],
  L: [[[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 0], [1, 1]], [[0, 0], [0, 1], [1, 1]]],
};
/** every placement of each shape inside the grid: { cells, anchor (top-left of the bounding box) } */
const PL = {};
for (const s of SHAPES) { PL[s] = []; for (const o of ORIENT[s]) for (let a = 0; a < N; a++) { const x0 = a % W, z0 = (a / W) | 0; const cells = [];
  for (const [dx, dz] of o) { const x = x0 + dx, z = z0 + dz; if (x >= W || z >= W) break; cells.push(z * W + x); }
  if (cells.length === o.length) PL[s].push({ cells, anchor: a }); } }
const fits = (p, free) => p.cells.every(t => free[t]);

// ------------------------------------------------------------ one step's chance, and one sampled step
/** The chance the next room is room i (of the rooms left) at its placement p, given what's free. */
function stepP(model, rooms, left, i, free) {
  const r = rooms[i]; let po;
  if (model.where === 'joint') { let n = 0; for (const j of left) for (const p of PL[rooms[j].s]) if (fits(p, free)) n++; return 1 / n; }
  if (model.where === 'cell') {
    // a free cell where some room left fits by its top-left; a room left that fits there; a way round
    let nA = 0; for (let a = 0; a < N; a++) if (free[a] && left.some(j => PL[rooms[j].s].some(p => p.anchor === a && fits(p, free)))) nA++;
    const atA = left.filter(j => PL[rooms[j].s].some(p => p.anchor === r.p.anchor && fits(p, free))).length;
    const ways = PL[r.s].filter(p => p.anchor === r.p.anchor && fits(p, free)).length;
    return 1 / (nA * atA * ways);
  }
  const o = model.order;
  if (o === 'rnd') po = 1 / left.length;
  else if (o === 'big' || o === 'small') { const sz = left.map(j => SIZE[rooms[j].s]); const m = o === 'big' ? Math.max(...sz) : Math.min(...sz); if (SIZE[r.s] !== m) return 0; po = 1 / sz.filter(v => v === m).length; }
  else { const rk = j => o.indexOf(rooms[j].s); const m = Math.min(...left.map(rk)); if (rk(i) !== m) return 0; po = 1 / left.filter(j => rk(j) === m).length; }
  if (model.where === 'uni') { let n = 0; for (const p of PL[r.s]) if (fits(p, free)) n++; return po / n; }
  if (model.where === 'try') { let n = 0; for (const p of PL[r.s]) if (fits(p, free)) n++; return po / n * (1 - (1 - n / PL[r.s].length) ** model.K); }
  // anchor: a top-left cell where the shape fits some way, then one of the ways that fit there
  const anchors = new Map(); for (const p of PL[r.s]) if (fits(p, free)) anchors.set(p.anchor, (anchors.get(p.anchor) || 0) + 1);
  return po / (anchors.size * anchors.get(r.p.anchor));
}
/** Sample a next step: [room index, placement] or null if stuck; also its chance. */
function stepSample(model, rooms, left, free) {
  const pick = a => a[Math.floor(rnd() * a.length)];
  if (model.where === 'joint') { const all = []; for (const j of left) for (const p of PL[rooms[j].s]) if (fits(p, free)) all.push([j, p]); if (!all.length) return null; const [j, p] = pick(all); return [j, p, 1 / all.length]; }
  if (model.where === 'cell') {
    const as = []; for (let a = 0; a < N; a++) if (free[a] && left.some(j => PL[rooms[j].s].some(p => p.anchor === a && fits(p, free)))) as.push(a);
    if (!as.length) return null; const a = pick(as);
    const js = left.filter(j => PL[rooms[j].s].some(p => p.anchor === a && fits(p, free))); const j = pick(js);
    const ps = PL[rooms[j].s].filter(p => p.anchor === a && fits(p, free)); return [j, pick(ps), 1 / (as.length * js.length * ps.length)];
  }
  const o = model.order; let cand;
  if (o === 'rnd') cand = left;
  else if (o === 'big' || o === 'small') { const sz = left.map(j => SIZE[rooms[j].s]); const m = o === 'big' ? Math.max(...sz) : Math.min(...sz); cand = left.filter(j => SIZE[rooms[j].s] === m); }
  else { const rk = j => o.indexOf(rooms[j].s); const m = Math.min(...left.map(rk)); cand = left.filter(j => rk(j) === m); }
  const j = pick(cand); const ok = PL[rooms[j].s].filter(p => fits(p, free)); if (!ok.length) return null;
  if (model.where === 'uni') return [j, pick(ok), 1 / (cand.length * ok.length)];
  // try: sampled as uni, with the chance that K tries find a spot as the run's weight (the run's chance over uni's)
  if (model.where === 'try') return [j, pick(ok), 1 / (cand.length * ok.length), 1 - (1 - ok.length / PL[rooms[j].s].length) ** model.K];
  const anchors = [...new Set(ok.map(p => p.anchor))]; const a = pick(anchors); const ps = ok.filter(p => p.anchor === a);
  return [j, pick(ps), 1 / (cand.length * anchors.length * ps.length)];
}

// ------------------------------------------------------------ a whole layout
/** The exact chance of the real placements, given the cells free at the start: a DP over subsets placed. */
function layoutP(model, rooms, free0) {
  const n = rooms.length, f = new Float64Array(1 << n); f[0] = 1;
  for (let m = 0; m < (1 << n); m++) {
    if (!f[m]) continue; const free = Uint8Array.from(free0); const left = [];
    for (let i = 0; i < n; i++) if (m >> i & 1) for (const t of rooms[i].p.cells) free[t] = 0; else left.push(i);
    for (const i of left) { const q = stepP(model, rooms, left, i, free); if (q) f[m | 1 << i] += f[m] * q; }
  }
  return f[(1 << n) - 1];
}
/** A sampled run from free0: whether it places every room, the placements, and its weight (try: its chance
 *  over uni's). */
function run(model, rooms, free0) {
  const free = Uint8Array.from(free0), left = rooms.map((_, i) => i), out = []; let w = 1;
  while (left.length) {
    const st = stepSample(model, rooms, left, free); if (!st) return [false, out, 0];
    const [j, p, , f = 1] = st; out.push([j, p]); w *= f;
    for (const t of p.cells) free[t] = 0; left.splice(left.indexOf(j), 1);
  }
  return [true, out, w];
}
// ------------------------------------------------------------ would the door tree keep it?
const NB = Array.from({ length: N }, (_, t) => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < W) o.push(t + W); if (z > 0) o.push(t - W); return o; });
/** The share of T door trees over this layout (placements of the big rooms) the game would keep. */
function accept(M, placed, T) {
  const owner = new Int32Array(N).fill(-1); owner[M.ent] = 0; let R = 1; const big = [false];
  for (const p of placed) { for (const t of p.cells) owner[t] = R; big.push(true); R++; }
  for (let t = 0; t < N; t++) if (owner[t] < 0) { owner[t] = R++; big.push(false); }
  const slots = Array.from({ length: R }, () => new Map());
  for (let t = 0; t < N; t++) for (const u of NB[t]) { const a = owner[t], b = owner[u]; if (a === b || a === 0 || b === 0) continue; if (!slots[a].has(b)) slots[a].set(b, []); slots[a].get(b).push([t, u]); }
  const ent = M.ent, inward = ent < W ? ent + W : ent >= N - W ? ent - W : ent % W === 0 ? ent + 1 : ent - 1;
  const inRoom = (r, a, b) => { if (a === b) return 0; const seen = new Map([[a, 0]]), q = [a]; while (q.length) { const u = q.shift(); for (const v of NB[u]) if (owner[v] === r && !seen.has(v)) { seen.set(v, seen.get(u) + 1); if (v === b) return seen.get(v); q.push(v); } } return 99; };
  let ok = 0;
  for (let k = 0; k < T; k++) {
    const parent = new Int32Array(R).fill(-2), inT = new Int32Array(R), dist = new Int32Array(R);
    parent[0] = -1; const r1 = owner[inward]; parent[r1] = 0; inT[r1] = inward; dist[r1] = 1; const order = [r1];
    let head = 0;
    while (order.length < R - 1) {
      let u = -1, v = -1;
      // TQ: with this chance the door comes off the oldest room with a free neighbour (the game's shallow tree)
      if (TQ && rnd() < TQ) while (head < order.length) { const a = order[head]; const fr = [...slots[a].keys()].filter(w => parent[w] === -2); if (fr.length) { u = a; v = fr[Math.floor(rnd() * fr.length)]; break; } head++; }
      if (u < 0 && rnd() < TP) { const last = order[order.length - 1]; const fr = [...slots[last].keys()].filter(w => parent[w] === -2); if (fr.length) { u = last; v = fr[Math.floor(rnd() * fr.length)]; } }
      if (u < 0) { let c = 0; for (const a of order) for (const b of slots[a].keys()) if (parent[b] === -2) { c++; if (rnd() * c < 1) { u = a; v = b; } } }
      if (u < 0) break;
      const sl = slots[u].get(v), [tu, tv] = sl[Math.floor(rnd() * sl.length)];
      parent[v] = u; inT[v] = tv; dist[v] = dist[u] + inRoom(u, inT[u], tu) + 1; order.push(v);
    }
    if (order.length < R - 1) continue;
    const deg = new Int32Array(R); for (let r = 1; r < R; r++) { deg[r]++; if (parent[r] >= 0) deg[parent[r]]++; }
    let dead = 0, blood = false;
    for (let r = 1; r < R; r++) { if (big[r] || deg[r] !== 1) continue; dead++;
      if (!blood && (dist[r] === 9 || dist[r] === 10)) for (let u = parent[r]; u > 0; u = parent[u]) if (dist[u] <= dist[r] - 4) { if (dist[u] === dist[r] - 4 && !big[u]) blood = true; break; } }
    if (blood && (M.anyDead ? dead >= 5 && dead <= 8 : dead === M.dead.length + 1)) ok++;
  }
  return ok / T;
}
const comb = (n, k) => { let r = 1; for (let i = 0; i < k; i++) r = r * (n - i) / (i + 1); return r; };

// ------------------------------------------------------------ the maps
// SYNTH=file.json: synth.mjs maps instead of the recorded ones
const synthMaps = process.env.SYNTH && JSON.parse((await import('node:fs')).readFileSync(process.env.SYNTH, 'utf8')).map(m => ({ ent: m.ent, dead: m.dead, B: m.blood, ones: m.ones,
  rooms: m.big.map(([s, cs]) => { cs = [...cs].sort((a, b) => a - b); return { s, p: PL[s].find(q => [...q.cells].sort((a, b) => a - b).every((t, k) => t === cs[k])) }; }) })).filter(M => M.rooms.length === 7 && M.rooms.every(r => r.p));
const maps = synthMaps ? synthMaps.slice(0, +(process.argv[2] || 1e9)) : complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7).slice(0, +(process.argv[2] || 1e9)).map(L => {
  const E = L.rooms.find(r => r.type === 'ENTRANCE'); const ent = E.tiles[0].z * W + E.tiles[0].x;
  const rooms = L.rooms.filter(r => r.shape !== 'OneByOne').map(r => { const cs = r.tiles.map(t => t.z * W + t.x).sort((a, b) => a - b);
    const p = PL[r.shape].find(q => q.cells.length === cs.length && [...q.cells].sort((a, b) => a - b).every((t, k) => t === cs[k])); return { s: r.shape, p }; });
  const cellOf = r => r.tiles[0].z * W + r.tiles[0].x;
  const dead = L.rooms.filter(r => r.shape === 'OneByOne' && r !== E && r.type !== 'BLOOD' && L.g.get(r.i).length === 1).map(cellOf);
  const B = cellOf(L.rooms.find(r => r.type === 'BLOOD')), F = cellOf(L.rooms.find(r => r.type === 'FAIRY'));
  const ones = L.rooms.filter(r => r.shape === 'OneByOne' && r !== E).map(cellOf);
  return { ent, rooms, dead, B, F, ones };
}).filter(M => M.rooms.every(r => r.p));
console.log(`${maps.length} maps with 7 big rooms`);
const TQ = +(process.env.TQ || 0), TP = +(process.env.TP ?? 0.3);
const ZSIMS = +(process.env.ZSIMS || 2000), SIMS = +(process.env.SIMS || 300), HS = +(process.env.HS || 40), ACC = +(process.env.ACC || 0), TREES = +(process.env.TREES || 8);
const baseFree = M => { const f = new Uint8Array(N).fill(1); f[M.ent] = 0; return f; };
const sample = (a, k) => { const b = [...a]; for (let i = 0; i < k; i++) { const j = i + Math.floor(rnd() * (b.length - i)); [b[i], b[j]] = [b[j], b[i]]; } return b.slice(0, k); };
// GEN=<model>:<k>: each map's big rooms made again by that model with k held cells, kept only if the door
// tree would keep it (accept() once) - the test run on maps whose procedure is known, under its own rules
if (process.env.GEN) {
  const [gname, gk] = process.env.GEN.split(':'); const [order, where] = gname.includes('/') ? gname.split('/') : [undefined, gname];
  const gm = where.startsWith('try') ? { order, where: 'try', K: +where.slice(3) } : { order, where }; let kept = 0;
  // a pool of runs the door tree keeps, one drawn by its weight (try: its chance over uni's): resampling, near
  // exact for a big pool
  for (const M of maps) {
    const pool = [], ws = [];
    for (let tr = 0; tr < 20000 && pool.length < 300; tr++) {
      const f = new Uint8Array(N).fill(1); f[M.ent] = 0; if (+gk) for (const t of sample([...Array(N).keys()].filter(t => t !== M.ent), +gk)) f[t] = 0;
      const [succ, out, w] = run(gm, M.rooms, f); if (!succ || !accept(M, out.map(([, p]) => p), 1)) continue; pool.push(out); ws.push(w);
    }
    if (!pool.length) continue;
    let r = rnd() * ws.reduce((a, b) => a + b, 0), k = 0; while (r > ws[k] && k < ws.length - 1) r -= ws[k++];
    const placed = pool[k].map(([j, p]) => ({ s: M.rooms[j].s, p })); const cov = new Set(placed.flatMap(r => r.p.cells));
    M.rooms = placed; M.ones = [...Array(N).keys()].filter(t => t !== M.ent && !cov.has(t)); kept++;
  }
  maps.splice(0, maps.length, ...maps.filter(M => M.ones.length + 22 === 35 || M.rooms.reduce((a, r) => a + r.p.cells.length, 0) + M.ones.length === 35));
  console.log(`GEN ${process.env.GEN}: ${kept} of ${maps.length} maps remade`);
}

/** log chance of the map's big rooms under a model with k random held cells (restart from scratch when stuck) */
function logLik(model, M, k) {
  const f0 = baseFree(M);
  let pl;
  if (!k) pl = layoutP(model, M.rooms, f0);
  else {
    // the held cells must be among the map's 1x1 cells: their chance of that, times the mean over such sets
    let s = 0; for (let h = 0; h < HS; h++) { const f = Uint8Array.from(f0); for (const t of sample(M.ones, k)) f[t] = 0; s += layoutP(model, M.rooms, f); }
    pl = s / HS * comb(M.ones.length, k) / comb(N - 1, k);
  }
  return Math.log(Math.max(pl, 1e-300)) - Math.log(Math.max(succRate(model, M, k), 1e-12));
}
/** How often a run succeeds (and, with ACC, its door tree is kept): the same for every map with the entrance in
 *  the same kind of cell (next to a corner or mid-edge, all alike by symmetry), the same shapes and dead-end
 *  count, so worked out once for each with ZSIMS runs - a log of a noisy small mean would favour the models
 *  that succeed least. */
// ZCACHE=file: the success rates kept between runs (they don't depend on the layouts, only on the keys)
const { existsSync, readFileSync: rfs, writeFileSync: wfs } = await import('node:fs');
const zCache = new Map(process.env.ZCACHE && existsSync(process.env.ZCACHE) ? Object.entries(JSON.parse(rfs(process.env.ZCACHE, 'utf8'))) : []);
const zKey = `|ACC${ACC ? 1 : 0}|T${TREES}|Q${TQ}|P${TP}|Z${ZSIMS}`;
function succRate(model, M, k) {
  const ex = M.ent % W, ez = (M.ent / W) | 0; const along = ez === 0 || ez === W - 1 ? ex : ez; const cls = along === 1 || along === 4 ? 1 : 2;
  const key = `${model.name}|${k}|${cls}|${M.rooms.map(r => r.s).sort().join(',')}|${M.dead.length}${zKey}`;
  if (zCache.has(key)) return zCache.get(key);
  const C = { ...M, ent: cls }; const f0 = baseFree(C); let ok = 0;
  for (let i = 0; i < ZSIMS; i++) { const f = Uint8Array.from(f0); if (k) for (const t of sample([...Array(N).keys()].filter(t => t !== C.ent), k)) f[t] = 0;
    const [succ, out, w] = run(model, C.rooms, f); if (succ) ok += w * (ACC ? accept(C, out.map(([, p]) => p), TREES) : 1); }
  zCache.set(key, ok / ZSIMS); return ok / ZSIMS;
}

// ENTRATE=<model>: how often a run (and its door tree, with ACC) succeeds from a cell next to a corner against
// a mid-edge one, each map's shapes and dead-end count, entrance (1,0) and (2,0): if the game starts the whole
// map again (entrance too) on a failure, that's how much likelier the near-corner entrances come out
if (process.env.ENTRATE) {
  const nm = process.env.ENTRATE; const [order, where] = nm.split('/'); const m = where.startsWith('try') ? { order, where: 'try', K: +where.slice(3) } : { order, where };
  const z = { 1: 0, 2: 0 };
  for (const M0 of maps) for (const ex of [1, 2]) { const M = { ...M0, ent: ex }; const f0 = baseFree(M);
    for (let i = 0; i < SIMS; i++) { const [succ, out, w] = run(m, M.rooms, f0); if (succ) z[ex] += w * (ACC ? accept(M, out.map(([, p]) => p), TREES) : 1); } }
  console.log(`${nm}: success from a near-corner entrance / a mid-edge one = ${(z[1] / z[2]).toFixed(3)} (real maps: 1.90 on the north/south edges, 2.22 on the sides)`);
  process.exit(0);
}
// MIXRATE=<model>[,<model>...]: if the whole map is made again on a failure, room mix and all, the mixes that
// succeed more often come out more often than they're drawn. Every mix of 7 (at most two of a shape), drawn
// (a) 7 independent picks of the 5 shapes or (b) every mix alike; times each one's success (placement and a
// door tree with 4-7 dead ends besides blood, the entrance's cell as the real maps have it); against the real mixes
if (process.env.MIXRATE) {
  const real = new Map(); for (const M of maps) { const k = M.rooms.map(r => r.s).sort().join(','); real.set(k, (real.get(k) || 0) + 1); }
  const mixes = []; for (const nR of [6, 7, 8]) (function rec(i, left, acc) { if (i === 5) { if (!left) mixes.push(acc.flatMap((c, j) => Array(c).fill(SHAPES[j]))); return; } for (let c = 0; c <= Math.min(2, left); c++) rec(i + 1, left - c, [...acc, c]); })(0, nR, []);
  const zOut = {};
  const fact = n => n <= 1 ? 1 : n * fact(n - 1);
  const nearShare = maps.filter(M => { const ex = M.ent % W, ez = (M.ent / W) | 0; const a = ez === 0 || ez === W - 1 ? ex : ez; return a === 1 || a === 4; }).length / maps.length;
  for (const nm of process.env.MIXRATE.split(',')) {
    const [order, where] = nm.split('/'); const m = where.startsWith('try') ? { order, where: 'try', K: +where.slice(3), name: nm } : { order, where, name: nm };
    const rows = mixes.map(sh => { const rooms = sh.map(x => ({ s: x })); let z = 0;
      for (const [cls, wt] of [[1, nearShare], [2, 1 - nearShare]]) { const C = { ent: cls, rooms, dead: [], anyDead: true }; const f0 = baseFree(C); let ok = 0;
        for (let i = 0; i < ZSIMS; i++) { const [succ, out, w] = run(m, rooms, f0); if (succ) ok += w * (ACC ? accept(C, out.map(([, p]) => p), TREES) : 1); } z += wt * ok / ZSIMS; }
      const key = [...sh].sort().join(','); const cnt = [0, 0, 0, 0, 0]; for (const x of sh) cnt[SHAPES.indexOf(x)]++;
      zOut[`${nm}|${cnt.join('')}`] = z;
      return { key, tiles: sh.reduce((a, x) => a + SIZE[x], 0), z, pa: 1 / cnt.reduce((a, c) => a * fact(c), 1), obs: real.get(key) || 0 }; }).filter(r => r.key.split(',').length === 7);
    for (const [lab, pk] of [['(a) independent picks', 'pa'], ['(b) every mix alike', null]]) {
      const pr = rows.map(r => (pk ? r[pk] : 1) * r.z); const Z = pr.reduce((a, b) => a + b, 0);
      let ll = 0, n = 0; rows.forEach((r, i) => { if (r.obs) { ll += r.obs * Math.log(Math.max(pr[i] / Z, 1e-12)); n += r.obs; } });
      const byT = {}; rows.forEach((r, i) => { byT[r.tiles] = (byT[r.tiles] || 0) + pr[i] / Z; });
      const realT = {}; for (const r of rows) realT[r.tiles] = (realT[r.tiles] || 0) + r.obs / maps.length;
      console.log(`${nm} ${lab}: log-lik of the real mixes ${(ll / n).toFixed(3)} per map; big tiles  ` + Object.keys(byT).sort((a, b) => a - b).map(t => `${t}: ${(byT[t] * 100).toFixed(1)}% (real ${((realT[t] || 0) * 100).toFixed(1)}%)`).join('  '));
    }
  }
  if (process.env.ZMIX) wfs(process.env.ZMIX, JSON.stringify(zOut));
  process.exit(0);
}
const MODELS = [];
for (const where of ['uni', 'anchor']) for (const order of ['big', 'rnd', 'small']) MODELS.push({ name: `${order}/${where}`, order, where });
MODELS.push({ name: 'cell', where: 'cell' }, { name: 'joint', where: 'joint' });
for (const K of [1, 2, 4, 8, 16]) for (const order of ['big', 'rnd']) MODELS.push({ name: `${order}/try${K}`, order, where: 'try', K });
if (process.env.ONLY) MODELS.splice(0, MODELS.length, ...MODELS.filter(m => process.env.ONLY.split(',').includes(m.name)));
const HELD = (process.env.HELD || '0,3,5,7').split(',').map(Number);
const res = {};
for (const M of maps) for (const m of MODELS) for (const k of HELD) { if ((m.where === 'cell' || m.where === 'joint') && k && !process.env.JOINTHELD) continue; (res[`${m.name} held ${k}`] ||= []).push(logLik(m, M, k)); }
if (process.env.ZCACHE) wfs(process.env.ZCACHE, JSON.stringify(Object.fromEntries(zCache)));
const ref = res['big/uni held 0'] || Object.values(res)[0];
console.log('\nmean log chance of a map\'s big rooms (higher = the procedure makes the real maps likelier); against big/uni, nothing held');
for (const [k, v] of Object.entries(res).sort((a, b) => b[1].reduce((x, y) => x + y) - a[1].reduce((x, y) => x + y))) {
  const d = v.map((x, i) => x - ref[i]); const md = d.reduce((a, b) => a + b) / d.length; const sd = Math.sqrt(d.reduce((a, b) => a + (b - md) ** 2, 0) / d.length / d.length);
  console.log(`  ${k.padEnd(22)} ${(v.reduce((a, b) => a + b) / v.length).toFixed(3).padStart(8)}   ${md >= 0 ? '+' : ''}${md.toFixed(3)} (s.e. ${sd.toFixed(3)})`);
}
