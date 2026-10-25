// F7 completion by the game's own steps, as reverse engineered (README "How the maps are made" and "What the
// game's code could be doing"), each sample a whole map consistent with what the map shows:
//  1. the big rooms: one random spot inside the map per room, all again if any clashes - every complete layout
//     of the mix alike (here: with the discovered rooms where they are); the mix by how often the maps have it,
//     over how often it gets placed (the game draws a new mix when it starts again)
//  2. the door tree grown room by room from the entrance (its one door inward) by a tree3.mjs rule - by default
//     treelik.mjs's best on the real maps: a room of the tree by the square root of its free sides, then one of
//     them, the nearer where that room was entered the likelier (exp(-0.5 x steps inside it)) and the nearer the
//     entrance (exp(-0.2 x grid steps)). Only the steps the map allows are taken (doors where it shows doors,
//     none where it shows walls), each sample weighted by the chance the game's step was one of them
//  3. made again unless the 1x1 dead ends besides blood are the puzzles + trap + yellow (+ rares on 5-puzzle
//     maps: 7 to 10 of them); blood any 1x1 dead end 9 or 10 steps out with a 1x1 four steps back (the fairy),
//     all alike; the wither doors on its path; trap, yellow, puzzles and rares on the other dead ends at random
// No fitted tables: what the samples agree on is the prediction. The draws of step 3 (a 2x2's inside, blood
// among its spots, the specials' shuffle) are summed over rather than drawn, and sampling goes on until 20
// batches' worth of weight, as each batch's samples come from few ancestors: the same model, less noise.
import { edgeId } from './features.mjs';
import { SHAPES, PLACEMENTS, OUT } from './sampler2.mjs';
import { startTree, addCell as treeAdd, growStep, cloneTree, rooted } from './tree3.mjs';
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
  const rule = opts.rule || { level: 'room', pick: 'node', p: 0, q: 0, alpha: 0.5, beta: 0.5, mu: 0.2 };
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
  // the 1x1 dead ends a map has besides the entrance (blood among them): puzzles + trap + yellow + blood; with 5
  // puzzles 8 and up (rares on the rest: 7 + 0..3 besides blood in the recorded maps; rule dmax= the most besides blood)
  const DMAX5 = rule.dmax ?? 10, Tmin = puzzles < 5 ? puzzles + 3 : 8, Tmax = puzzles < 5 ? puzzles + 3 : DMAX5 + 1;
  // the discovered 1x1s' doors are all on the map: those with one are dead ends whatever the rest
  let knownDead = 0; for (const r of known) if (r.shape === 'OneByOne' && r.type !== 'ENTRANCE') { const c = r.cells[0]; let d = 0; for (const u of NB[c]) if (side.get(sk(c, u)) === 'door') d++; if (d === 1) knownDead++; }

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
  /** can the growing tree still end with Tmin..Tmax 1x1 dead ends? A 1x1 off the tree with no door out yet is
   *  sure to stay a dead end once every room around it is in the tree; one not in it yet may become one. Trees
   *  that can't are dropped now rather than at the end (rule deadkill=1; on held-out maps it does worse: the
   *  batches collapse sooner) */
  function deadOk(pt) {
    const { owner, rooms, entered, kids } = pt; let sure = knownDead, maybe = knownDead;
    for (let r = 0; r < rooms.length; r++) { const rm = rooms[r]; if (rm.known || rm.big) continue;
      if (!entered[r]) { maybe++; continue; }
      if (kids[r]) continue; maybe++;
      const c = rm.cells[0]; let closed = true; for (const u of NB[c]) if (!entered[owner[u]]) { closed = false; break; } if (closed) sure++; }
    return sure <= Tmax && maybe >= Tmin;
  }
  /** 3. the finished tree: the map's doors all used, the dead ends the puzzles want, blood and the fairy, the
   *  wither doors, the specials; the weight times the chance of what the map shows of them, or 0 */
  const finishTree = pt => {
    const { owner, rooms, parent, depth } = pt; const R = rooms.length;
    for (const [key, st] of side) { if (st !== 'door') continue; const a = (key / N) | 0, b = key % N; if (parent[a] !== b && parent[b] !== a) { if (opts.why) opts.why.door = (opts.why.door || 0) + 1; return null; } }
    const deg = new Int32Array(R), rParent = new Int32Array(R).fill(-1), rEntry = new Int32Array(R).fill(-1);
    for (let v = 0; v < N; v++) { const u = parent[v]; if (u < 0) { rEntry[owner[v]] = v; continue; } if (owner[u] !== owner[v]) { deg[owner[u]]++; deg[owner[v]]++; rParent[owner[v]] = owner[u]; rEntry[owner[v]] = v; } }
    const entR = owner[entCell];
    const dead = []; for (let r = 0; r < R; r++) if (r !== entR && !rooms[r].big && deg[r] === 1) dead.push(r);
    const D = dead.length - 1;  // besides blood
    if (D + 1 < Tmin || D + 1 > Tmax) { if (opts.why) opts.why[`D${D}`] = (opts.why[`D${D}`] || 0) + 1; return null; }
    // blood and the fairy over the 2x2s' insides (tree3 rooted: the walk through a 2x2 is its inside's), unless
    // the rule says inside=draw (the one drawn as the tree grew, as before)
    const vars = rule.inside === 'draw' ? [{ parent, depth, p: 1 }] : rooted(pt, rule);
    const opts2 = []; let fsum = 0;
    // the wither doors the map shows must be the path's (the rooms' path: the same over the insides)
    const fits = ([b]) => { const onP = new Map(); const pathR = []; for (let r = b; r >= 0; r = rParent[r]) pathR.push(r); pathR.reverse();
      for (let i = 0; i + 1 < pathR.length; i++) { const v = rEntry[pathR[i + 1]]; onP.set(sk(v, parent[v]), i === 0 ? 'n' : i === pathR.length - 2 ? 'b' : 'w'); }
      for (const [key, k2] of kindOf) if ((onP.get(key) || 'n') !== k2) return false; return true; };
    for (const va of vars) {
      const fairyOf = b => { let f = rooms[b].cells[0]; for (let k = 0; k < 4; k++) f = va.parent[f]; return owner[f]; };
      const cands = []; let nAll = 0;
      for (const b of dead) { const t = rooms[b].cells[0]; if (va.depth[t] !== 9 && va.depth[t] !== 10) continue; const fr = fairyOf(b); if (fr === entR || rooms[fr].big) continue; nAll++;
        if (rooms[b].known && rooms[b].type !== 'BLOOD') continue; if (rooms[fr].known && rooms[fr].type !== 'FAIRY') continue;
        if (knownBlood >= 0 && b !== knownBlood) continue; if (knownFairy >= 0 && fr !== knownFairy) continue;
        cands.push([b, fr]); }
      if (!nAll) continue;
      const good = cands.filter(fits); if (!good.length) continue;
      const f = va.p * good.length / nAll; fsum += f; opts2.push([f, good, va]);
    }
    if (!(fsum > 0)) { if (opts.why) opts.why.blood = (opts.why.blood || 0) + 1; return null; }
    let r2 = rnd() * fsum, oi = 0; while (r2 > opts2[oi][0] && oi < opts2.length - 1) r2 -= opts2[oi++][0];
    const [, good, va] = opts2[oi];
    const [blood, fairy] = good[Math.floor(rnd() * good.length)];
    const need = { T: 1, Y: 1, P: puzzles, R: Math.max(0, D - 2 - Math.min(puzzles, 5)) };
    if (need.T < KT.T || need.Y < KT.Y || need.P < KT.P || need.R < KT.R) return null;
    const nK = KT.T + KT.Y + KT.P + KT.R;
    const wType = (fact(D - nK) / (fact(need.T - KT.T) * fact(need.Y - KT.Y) * fact(need.P - KT.P) * fact(need.R - KT.R))) / (fact(D) / (fact(need.T) * fact(need.Y) * fact(need.P) * fact(need.R)));
    const left = []; for (const c of ['T', 'Y', 'P', 'R']) for (let k = KT[c]; k < need[c]; k++) left.push(c);
    for (let i = left.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [left[i], left[j]] = [left[j], left[i]]; }
    const type = new Array(R).fill(null); let li = 0; for (const r of dead) if (r !== blood && !rooms[r].known) type[r] = left[li++];
    type[blood] = 'B'; type[fairy] = 'F';
    // what the draws above average to (unless the rule says types=draw, as before): each room's chance of each
    // class over the 2x2s' insides, the blood spots and the specials' shuffle - the same samples, less noise
    let dist = null;
    if (rule.types !== 'draw') {
      dist = new Map(); const at = r => { let d = dist.get(r); if (!d) { d = new Float64Array(OUT.length); dist.set(r, d); } return d; };
      const share = {}; for (const c of left) share[c] = (share[c] || 0) + 1 / left.length;
      const deadU = dead.filter(r => !rooms[r].known);
      for (const [fo, gd] of opts2) for (const [b, fr] of gd) { const w = fo / fsum / gd.length;
        if (!rooms[b].known) at(b)[OI.B] += w; if (!rooms[fr].known) at(fr)[OI.F] += w;
        for (const r of deadU) if (r !== b) for (const c in share) at(r)[OI[c]] += w * share[c]; }
    }
    return { f: fsum * wType, type, parent: va.parent, dist };
  };
  const ess = ps => { let a = 0, b = 0; for (const x of ps) { a += x.w; b += x.w * x.w; } return b ? a * a / b : 0; };
  const resample = ps => { const tot = ps.reduce((a, x) => a + x.w, 0); if (!tot) return ps; const n = ps.length, mean = tot / n, out = []; let u = rnd() * tot / n, c = 0, i = 0;
    for (let k = 0; k < n; k++) { const target = u + k * tot / n; while (c + ps[i].w < target && i < n - 1) { c += ps[i].w; i++; } out.push(clone(ps[i], mean)); } return out; };
  const clone = cloneTree;

  // sequential Monte Carlo: batches of particles grown together, resampled when their weights spread out
  const NP = rule.np || opts.particles || 200;  // rule np= / opts.particles: the batch size
  // a batch's samples descend from few ancestors, so the batches are the independent draws: go on until NS
  // samples and (rule batches= / opts.batches) that many batches' worth of weight, (sum w)^2 / sum w^2 over them
  // (with NS samples alone, a batch whose ancestors happen to fit often fills them: one or two layouts)
  const minB = rule.batches ?? opts.batches ?? 20; let bw1 = 0, bw2 = 0;
  while ((result.accepted < NS || (bw2 ? bw1 * bw1 / bw2 : 0) < minB) && result.tries < maxTries) {
    let ps = []; for (let i = 0; i < NP; i++) { result.tries++; const pt = newParticle(); if (pt) ps.push(pt); else ps.push({ w: 0 }); }
    ps = ps.filter(x => x.w > 0); if (!ps.length) continue;
    while (ps.length < NP) ps.push(clone(ps[Math.floor(rnd() * ps.length)], 0));  // keep the batch size; zero-weight fillers drop at the next resample
    if (ess(ps) < NP / 2) ps = resample(ps);
    for (let s2 = 0; s2 < N - 2; s2++) {
      for (const pt of ps) if (pt.w > 0 && !pt.done) { growStep(pt, rule, mapOk); if (pt.w > 0 && rule.deadkill && !deadOk(pt)) pt.w = 0; }
      if (!ps.some(x => x.w > 0)) break;
      if (ess(ps) < NP / 2) ps = resample(ps);
    }
    const bs = { start: ps.filter(x => x.w > 0).length, grown: 0, kept: 0, w: 0 };
    for (const pt of ps) {
      if (!(pt.w > 0) || pt.inT.some(x => !x)) continue;
      bs.grown++;
      const fin = finishTree(pt); if (!fin) continue;
      const wgt = pt.w * fin.f; if (!(wgt > 0)) continue;
      bs.kept++; bs.w += wgt;
      result.accepted++; result.weight += wgt;
      for (let r = 0; r < pt.rooms.length; r++) { const rm = pt.rooms[r]; if (rm.known) continue;
        const d = fin.dist && !rm.big && fin.dist.get(r);
        if (d) { let o = 1; for (let c = 0; c < OUT.length; c++) if (d[c]) { o -= d[c]; for (const t of rm.cells) counts[uIdx[t]][c] += wgt * d[c]; } if (o > 1e-12) for (const t of rm.cells) counts[uIdx[t]][OI.o] += wgt * o; continue; }
        const c = OI[rm.big ? 'n' : fin.dist ? 'o' : fin.type[r] || 'o']; for (const t of rm.cells) counts[uIdx[t]][c] += wgt; }
      if (opts.onSample) opts.onSample({ owner: pt.owner, rooms: pt.rooms, type: fin.type, parent: fin.parent, weight: wgt });
    }
    bw1 += bs.w; bw2 += bs.w * bs.w;
    // onBatch({ start, grown, kept, w }): per batch, the particles placed, fully grown, kept, and their weight
    if (opts.onBatch) opts.onBatch(bs);
  }
  return finish();
}
