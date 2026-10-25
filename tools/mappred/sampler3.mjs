// F7 completion by the game's own steps, as reverse engineered (README "How the maps are made" and "What the
// game's code could be doing"), each sample a whole map consistent with what the map shows:
//  1. the big rooms: one random spot inside the map per room, all again if any clashes - every complete layout
//     of the mix alike (here: with the discovered rooms where they are); the mix by how often the maps have it,
//     over how often it gets placed (the game draws a new mix when it starts again)
//  2. the door tree grown cell by cell from the entrance (its one door inward): off the newest cell with chance
//     p, else a random free side off the tree; a big room entered once, its other cells only from its own. Only
//     the steps the map allows are taken (doors where it shows doors, none where it shows walls), each sample
//     weighted by the chance the game's step was one of them
//  3. made again unless the 1x1 dead ends besides blood are the puzzles + trap + yellow (+ rares on 5-puzzle
//     maps); blood any 1x1 dead end 9 or 10 steps out with a 1x1 four steps back (the fairy), all alike; the
//     wither doors on its path; trap, yellow, puzzles and rares on the other dead ends at random
// No fitted tables: what the samples agree on is the prediction.
import { edgeId } from './features.mjs';
import { SHAPES, PLACEMENTS, OUT } from './sampler2.mjs';
import { startTree, addCell as treeAdd, growStep, cloneTree } from './tree3.mjs';
const W = 6, H = 6, N = 36;
const OI = Object.fromEntries(OUT.map((c, i) => [c, i]));
const NB = Array.from({ length: N }, (_, t) => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < H) o.push(t + W); if (z > 0) o.push(t - W); return o; });
/** every placement of each shape inside the grid, as cell lists */
const PL = Object.fromEntries(SHAPES.map(s => [s, PLACEMENTS[s].flatMap(o => { const out = []; for (let a = 0; a < N; a++) { const x0 = a % W, z0 = (a / W) | 0; const cs = []; for (const [dx, dz] of o) { const x = x0 + dx, z = z0 + dz; if (x >= W || z >= H) break; cs.push(z * W + x); } if (cs.length === o.length) out.push(cs); } return out; })]));
const TYPE_CLS = { PUZZLE: 'P', TRAP: 'T', CHAMPION: 'Y', BLOOD: 'B', FAIRY: 'F', RARE: 'R' };
const fact = n => { let r = 1; for (let i = 2; i <= n; i++) r *= i; return r; };

export function sample(obs, combos, opts) {
  const rnd = opts.rnd, NS = opts.samples || 300, maxTries = opts.maxTries || NS * 400;
  // the door tree's rule (tree3.mjs): treelik.mjs's best on the real maps
  const rule = opts.rule || { level: 'room', pick: 'node', p: 0.15, q: 0.1 };
  const { tile, edge } = obs;
  // what each cell side shows: 'door' (with its kind), 'wall', or null (nobody has seen it); inside a room: 'same'
  const side = new Map(); const kindOf = new Map();
  const sk = (a, b) => a < b ? a * N + b : b * N + a;
  for (let t = 0; t < N; t++) for (const u of NB[t]) { if (u < t) continue; const dx = u % W - t % W, dz = ((u / W) | 0) - ((t / W) | 0);
    const k = edge.get(edgeId(t % W, (t / W) | 0, dx, dz)); if (k === undefined || k === 'unk') continue;
    if (k === 'same') { side.set(sk(t, u), 'same'); continue; }
    if (k === 'n' || k === 'w' || k === 'b') { side.set(sk(t, u), 'door'); kindOf.set(sk(t, u), k); } else side.set(sk(t, u), 'wall'); }
  // the discovered rooms
  const known = [], kOwner = new Int32Array(N).fill(-1); let entCell = -1; const knownBig = new Array(5).fill(0);
  for (const [id, r] of obs.rooms) { const cells = r.tiles.map(q => q.z * W + q.x); const k = known.length; known.push({ id, cells, shape: r.shape, type: r.type });
    for (const c of cells) kOwner[c] = k; if (r.type === 'ENTRANCE') entCell = cells[0]; const si = SHAPES.indexOf(r.shape); if (si >= 0) knownBig[si]++; }
  const unknown = []; for (let t = 0; t < N; t++) if (kOwner[t] < 0) unknown.push(t);
  const result = { accepted: 0, tries: 0, unknown, probs: null, weight: 0 };
  const counts = unknown.map(() => new Float64Array(OUT.length)); const uIdx = new Int32Array(N).fill(-1); unknown.forEach((t, i) => { uIdx[t] = i; });
  const finish = () => { const a = result.weight; result.probs = counts.map(c => a ? Array.from(c, v => v / a) : null); return result; };
  if (entCell < 0) return finish();
  const inward = entCell < W ? entCell + W : entCell >= N - W ? entCell - W : entCell % W === 0 ? entCell + 1 : entCell - 1;
  // unknown cells with a door the map shows from a discovered room
  const doorIn = new Uint8Array(N); for (const t of unknown) for (const u of NB[t]) if (kOwner[u] >= 0 && side.get(sk(t, u)) === 'door') doorIn[t] = 1;
  // the mixes the discovered rooms allow, by weight
  const zmix = opts.zmix || {};
  const mixes = combos.filter(c => c.counts.every((v, i) => v >= knownBig[i])).map(c => ({ counts: c.counts, w: c.w / (zmix['big/try1|' + c.counts.join('')] || 1) }));
  if (!mixes.length) return finish();
  const mixW = mixes.reduce((a, m) => a + m.w, 0);
  const puzzles = obs.puzzles;
  const KT = { T: 0, Y: 0, P: 0, R: 0 }; for (const r of known) { const c = TYPE_CLS[r.type]; if (c && KT[c] !== undefined) KT[c]++; }
  const knownBlood = known.findIndex(r => r.type === 'BLOOD'), knownFairy = known.findIndex(r => r.type === 'FAIRY');

  const T = Object.fromEntries(SHAPES.map(x => [x, PL[x].length]));
  const sideOfO = (owner, a, b) => owner[a] === owner[b] ? 'same' : side.get(sk(a, b)) ?? null;

  /** 1. a new particle: a mix, then the big rooms not yet seen one by one in random order, each on a spot it
   *  fits drawn alike, weighted by (spots it fits / spots inside the map) - the chance one random spot fits,
   *  so the layouts come out as the game's (one spot each, again on a clash) */
  const newParticle = () => {
    let r0 = rnd() * mixW, mi = 0; while (r0 > mixes[mi].w && mi < mixes.length - 1) r0 -= mixes[mi++].w;
    const todo = []; mixes[mi].counts.forEach((v, i) => { for (let k = knownBig[i]; k < v; k++) todo.push(SHAPES[i]); });
    for (let i = todo.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [todo[i], todo[j]] = [todo[j], todo[i]]; }
    const owner = Int32Array.from(kOwner); const rooms = known.map(r => ({ cells: r.cells, big: r.shape !== 'OneByOne', known: true, type: r.type }));
    let w = 1;
    for (const s2 of todo) {
      // two doors from the discovered part into one room would close a loop
      const fit = PL[s2].filter(cs => cs.every(t => owner[t] === -1) && cs.filter(t => doorIn[t]).length <= 1);
      if (!fit.length) return null;
      w *= fit.length / T[s2]; const cs = fit[Math.floor(rnd() * fit.length)];
      for (const t of cs) owner[t] = rooms.length; rooms.push({ cells: cs, big: true, known: false });
    }
    for (const t of unknown) if (owner[t] === -1) { owner[t] = rooms.length; rooms.push({ cells: [t], big: false, known: false }); }
    const pt = startTree(owner, rooms, entCell); pt.rnd = rnd; pt.w = w;
    if (!mapOk(pt, entCell, inward)) return null; treeAdd(pt, rule, entCell, inward);
    return pt;
  };
  // the map allows the step u -> v: not through a wall; a room entered once, with no other door the map shows
  // between it and a room already in the tree
  function mapOk(pt, u, v) {
    const { owner, rooms, entered } = pt;
    if (sideOfO(owner, u, v) === 'wall') return false;
    if (owner[v] === owner[u]) return true;
    const S = owner[v];
    for (const c of rooms[S].cells) for (const x of NB[c]) { const Rx = owner[x]; if (Rx === S || !entered[Rx]) continue; if (c === v && x === u) continue; if (side.get(sk(c, x)) === 'door') return false; }
    return true;
  }
  /** 3. the finished tree: the map's doors all used, the dead ends the puzzles want, blood and the fairy, the
   *  wither doors, the specials; the weight times the chance of what the map shows of them, or 0 */
  const finishTree = pt => {
    const { owner, rooms, parent, depth } = pt; const R = rooms.length;
    for (const [key, st] of side) { if (st !== 'door') continue; const a = (key / N) | 0, b = key % N; if (parent[a] !== b && parent[b] !== a) return null; }
    const deg = new Int32Array(R), rParent = new Int32Array(R).fill(-1), rEntry = new Int32Array(R).fill(-1);
    for (let v = 0; v < N; v++) { const u = parent[v]; if (u < 0) { rEntry[owner[v]] = v; continue; } if (owner[u] !== owner[v]) { deg[owner[u]]++; deg[owner[v]]++; rParent[owner[v]] = owner[u]; rEntry[owner[v]] = v; } }
    const entR = owner[entCell];
    const dead = []; for (let r = 0; r < R; r++) if (r !== entR && !rooms[r].big && deg[r] === 1) dead.push(r);
    const D = dead.length - 1;  // besides blood
    if (puzzles < 5 ? D !== puzzles + 2 : D < 7 || D > 8) return null;
    const fairyOf = b => { let f = rooms[b].cells[0]; for (let k = 0; k < 4; k++) f = parent[f]; return owner[f]; };
    const cands = []; let nAll = 0;
    for (const b of dead) { const t = rooms[b].cells[0]; if (depth[t] !== 9 && depth[t] !== 10) continue; const fr = fairyOf(b); if (fr === entR || rooms[fr].big) continue; nAll++;
      if (rooms[b].known && rooms[b].type !== 'BLOOD') continue; if (rooms[fr].known && rooms[fr].type !== 'FAIRY') continue;
      if (knownBlood >= 0 && b !== knownBlood) continue; if (knownFairy >= 0 && fr !== knownFairy) continue;
      cands.push([b, fr]); }
    if (!nAll) return null;
    // the wither doors the map shows must be the path's
    const fits = ([b]) => { const onP = new Map(); const pathR = []; for (let r = b; r >= 0; r = rParent[r]) pathR.push(r); pathR.reverse();
      for (let i = 0; i + 1 < pathR.length; i++) { const v = rEntry[pathR[i + 1]]; onP.set(sk(v, parent[v]), i === 0 ? 'n' : i === pathR.length - 2 ? 'b' : 'w'); }
      for (const [key, k2] of kindOf) if ((onP.get(key) || 'n') !== k2) return false; return true; };
    const good = cands.filter(fits); if (!good.length) return null;
    const [blood, fairy] = good[Math.floor(rnd() * good.length)];
    const need = { T: 1, Y: 1, P: puzzles, R: Math.max(0, D - 2 - Math.min(puzzles, 5)) };
    if (need.T < KT.T || need.Y < KT.Y || need.P < KT.P || need.R < KT.R) return null;
    const nK = KT.T + KT.Y + KT.P + KT.R;
    const wType = (fact(D - nK) / (fact(need.T - KT.T) * fact(need.Y - KT.Y) * fact(need.P - KT.P) * fact(need.R - KT.R))) / (fact(D) / (fact(need.T) * fact(need.Y) * fact(need.P) * fact(need.R)));
    const left = []; for (const c of ['T', 'Y', 'P', 'R']) for (let k = KT[c]; k < need[c]; k++) left.push(c);
    for (let i = left.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [left[i], left[j]] = [left[j], left[i]]; }
    const type = new Array(R).fill(null); let li = 0; for (const r of dead) if (r !== blood && !rooms[r].known) type[r] = left[li++];
    type[blood] = 'B'; type[fairy] = 'F';
    return { f: good.length / nAll * wType, type };
  };
  const ess = ps => { let a = 0, b = 0; for (const x of ps) { a += x.w; b += x.w * x.w; } return b ? a * a / b : 0; };
  const resample = ps => { const tot = ps.reduce((a, x) => a + x.w, 0); if (!tot) return ps; const n = ps.length, mean = tot / n, out = []; let u = rnd() * tot / n, c = 0, i = 0;
    for (let k = 0; k < n; k++) { const target = u + k * tot / n; while (c + ps[i].w < target && i < n - 1) { c += ps[i].w; i++; } out.push(clone(ps[i], mean)); } return out; };
  const clone = cloneTree;

  // sequential Monte Carlo: batches of particles grown together, resampled when their weights spread out
  const NP = opts.particles || 200;
  while (result.accepted < NS && result.tries < maxTries) {
    let ps = []; for (let i = 0; i < NP; i++) { result.tries++; const pt = newParticle(); if (pt) ps.push(pt); else ps.push({ w: 0 }); }
    ps = ps.filter(x => x.w > 0); if (!ps.length) continue;
    while (ps.length < NP) ps.push(clone(ps[Math.floor(rnd() * ps.length)], 0));  // keep the batch size; zero-weight fillers drop at the next resample
    if (ess(ps) < NP / 2) ps = resample(ps);
    for (let s2 = 0; s2 < N - 2; s2++) {
      for (const pt of ps) if (pt.w > 0 && !pt.done) growStep(pt, rule, mapOk);
      if (!ps.some(x => x.w > 0)) break;
      if (ess(ps) < NP / 2) ps = resample(ps);
    }
    for (const pt of ps) {
      if (!(pt.w > 0) || pt.inT.some(x => !x)) continue;
      const fin = finishTree(pt); if (!fin) continue;
      const wgt = pt.w * fin.f; if (!(wgt > 0)) continue;
      result.accepted++; result.weight += wgt;
      for (let r = 0; r < pt.rooms.length; r++) { const rm = pt.rooms[r]; if (rm.known) continue; const c = OI[rm.big ? 'n' : fin.type[r] || 'o']; for (const t of rm.cells) counts[uIdx[t]][c] += wgt; }
      if (opts.onSample) opts.onSample({ owner: pt.owner, rooms: pt.rooms, type: fin.type, parent: pt.parent, weight: wgt });
    }
  }
  return finish();
}
