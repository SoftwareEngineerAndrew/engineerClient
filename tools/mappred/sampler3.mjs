// F7 completion by the game's own steps, as reverse engineered (README "How the maps are made" and "What the
// game's code could be doing"), each sample a whole map consistent with what the map shows:
//  1. the big rooms: one random spot inside the map per room, all again if any clashes - every complete layout
//     of the mix alike (here: with the discovered rooms where they are); the mix by how often the maps have it,
//     over how often it gets placed (the game draws a new mix when it starts again), times c!/(c-k)! for c rooms
//     of a shape in the mix with k of them seen (the mix's chance of having a room of each shape where the map
//     shows one); 6-room mixes too
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
//
// Options, each a rule name=value (evalbig game:<rule>/prune=2/move=1/...) or the same opts field (the rule's
// wins). On by default (the model as the recorded maps have it; =0 for sampler3 as it was):
//   mixes6  the mixes it is given with 6 big rooms too (else 7-room mixes only; evalbig gives mixesFor(train))
//   mixfac  the mix weight times c!/(c-k)! for the k seen rooms of a shape the mix has c of
// Off by default (the defaults are sampler3 at f7ef0ba):
//   prune=1 a tree that can no longer end with the dead ends the puzzles want is dropped as soon as that shows,
//           counting the walls the map shows (exact: finishTree would drop it; README: deadkill=1, the same idea
//           without the walls, did worse - its batches collapse sooner); prune=2 also one that can no longer have
//           blood where the map says (a seen blood or fairy at the wrong walk on every 2x2 inside, nothing 9-10
//           steps out left behind the wither door it shows), and no big room on the cell behind its blood door
//   distinct=1  a sample is a different finished map (layout and room-level tree), not a resampled copy
//   move=k  after each resample, k tries per particle at moving a big room the tree hasn't come next to yet to
//           another spot it fits that the tree hasn't come next to either (an MCMC move that keeps the target:
//           the growth so far never looked there, and every layout is alike)
//   rarew=w a 5-puzzle tree with D dead ends besides blood weighs w^(D - 7) (the trees make more rares than the
//           maps: real over this tree's own falls about x0.7 a dead end, x0.5 with the whole layout given; MODEL.md "5-puzzle
//           maps": the rare mass comes out right at 0.7 and no score moves, 109 fresh + 148 training maps)
//   ess=f   resample when the ESS falls below f x the batch (0.5)
//   endrs=0 no resampling once every tree is finished (it only adds noise; matters with prune / deadkill)
//   joined=1  when the discovered rooms are all joined to the entrance by the doors the map shows between
//           them: a discovered room only entered from a discovered one, a room behind a shown door only by it
//   lshare=f  go on until the samples are also worth NS x f different layouts (copies of one layout: one)
//   cal=1   the tiles' chances through CAL (below), as the classic sampler's go through MapStats.CALIBRATION: the samples are
//           too sure of themselves (fitted a = 0.75-0.95 per class on game samples of 5-puzzle and 2-4 puzzle training maps);
//           cal=2: CAL_MOD, the same fitted on the mod's own run (GameSampler.kt: 2000 samples, 120 batches' worth), which is
//           less sure of itself to start with (a = 0.81-0.97): for porting, not for the 300-sample runs of evalbig
//   legacy=1  sampler3 as first written: inside=draw/types=draw/batches=0/dmax=8/mixes6=0/mixfac=0
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

/** The big-room mixes of the F7 maps (counts in SHAPES order, how many maps had each), 6-room maps too (1%):
 *  the 7-room ones are sampler2's combosFor(Ls)['7'], in the same order. */
export function mixesFor(Ls) {
  const m = new Map(); for (const L of Ls) if (L.floor[1] === '7') { const k = SHAPES.map(s => L.rooms.filter(r => r.shape === s).length).join(''); m.set(k, (m.get(k) || 0) + 1); }
  return [...m].map(([k, w]) => ({ counts: [...k].map(Number), w }));
}

/** Calibration of the tiles' chances (calfit.mjs, KEEPZERO, on the game process's own samples - 300 samples, 20 batches' worth -
 *  of 148 5-puzzle and 120 2-4 puzzle training maps at 2/4/6/9/12 rooms known): q_c ~ (p_c + 0.001)^a_c * e^(b_c + d_c * (rooms
 *  known - 6) / 3), a class with no sample stays at 0; per class in sampler2's OUT order (big, 1x1, puzzle, trap, yellow,
 *  blood, fairy, rare). Held out (calfit on the training rows alone, 300 samples): 147 5-puzzle maps -1.0548 -> -1.0486, 100 2-4 puzzle
 *  maps -1.0315 -> -1.0281 */
export const CAL = { a: [0.7545, 0.7603, 0.8309, 0.9276, 0.9469, 0.8820, 0.8809, 1.0158, 1], b: [0.1099, -0.1581, -0.0394, 0.0045, 0.0491, 0.0443, 0.0530, -0.0633, 0], d: [-0.0326, 0.0070, 0.0029, -0.0028, -0.0093, 0.0448, -0.0036, -0.0064, 0] };

/** The same on the mod's GameSampler.kt runs (MapSnapshotsCheck, 2000 samples, 120 batches' worth) of the same training maps: held-out
 *  5-puzzle maps (147, 735 snapshots) -1.0369 -> -1.0350 */
export const CAL_MOD = { a: [0.8123, 0.8378, 0.8816, 0.9570, 0.9706, 0.9312, 0.9158, 1.0328, 1], b: [0.0656, -0.1019, -0.0325, 0.0128, 0.0441, 0.0454, 0.0341, -0.0677, 0], d: [-0.0236, 0.0047, 0.0020, -0.0030, -0.0076, 0.0283, 0.0005, -0.0014, 0] };

export function sample(obs, combos, opts) {
  const rnd = opts.rnd, NS = opts.samples || 300, maxTries = opts.maxTries || NS * 400;
  // the door tree's rule (tree3.mjs): treelik.mjs's best on the real maps; legacy=1: the old sampling's defaults
  const rule0 = opts.rule || { level: 'room', pick: 'node', p: 0, q: 0, alpha: 0.5, beta: 0.5, mu: 0.2 };
  const rule = (rule0.legacy ?? opts.legacy) ? { inside: 'draw', types: 'draw', batches: 0, dmax: 8, mixes6: 0, mixfac: 0, ...rule0 } : rule0;
  // the options (header): the rule's name=value, else opts, else the default
  const opt = (k, d) => rule[k] ?? opts[k] ?? d;
  const O = { mixes6: opt('mixes6', 1), mixfac: opt('mixfac', 1), prune: opt('prune', 0), distinct: opt('distinct', 0), move: opt('move', 0),
    rarew: opt('rarew', 1), cal: opt('cal', 0), ess: opt('ess', 0.5), endrs: opt('endrs', 1), joined: opt('joined', 0), lshare: opt('lshare', 0) };
  const { tile, edge } = obs;
  // what each cell side shows: 'door' (with its kind), 'wall', or null (nobody has seen it); inside a room: 'same'
  const side = new Map(); const kindOf = new Map();
  const sk = (a, b) => a < b ? a * N + b : b * N + a;
  for (let t = 0; t < N; t++) for (const u of NB[t]) { if (u < t) continue; const dx = u % W - t % W, dz = ((u / W) | 0) - ((t / W) | 0);
    const k = edge.get(edgeId(t % W, (t / W) | 0, dx, dz)); if (k === undefined || k === 'unk') continue;
    if (k === 'same') { side.set(sk(t, u), 'same'); continue; }
    if (k === 'n' || k === 'w' || k === 'b') { side.set(sk(t, u), 'door'); kindOf.set(sk(t, u), k); } else side.set(sk(t, u), 'wall'); }
  // the same for the hot loops: the walls as a flat array, each cell's shown doors
  const wallA = new Uint8Array(N * N), doorNb = Array.from({ length: N }, () => []);
  for (const [key, st] of side) { const a = (key / N) | 0, b = key % N; if (st === 'wall') wallA[a * N + b] = wallA[b * N + a] = 1; else if (st === 'door') { doorNb[a].push(b); doorNb[b].push(a); } }
  // the discovered rooms
  const known = [], kOwner = new Int32Array(N).fill(-1); let entCell = -1; const knownBig = new Array(5).fill(0);
  for (const [id, r] of obs.rooms) { const cells = r.tiles.map(q => q.z * W + q.x); const k = known.length; known.push({ id, cells, shape: r.shape, type: r.type });
    for (const c of cells) kOwner[c] = k; if (r.type === 'ENTRANCE') entCell = cells[0]; const si = SHAPES.indexOf(r.shape); if (si >= 0) knownBig[si]++; }
  const unknown = []; for (let t = 0; t < N; t++) if (kOwner[t] < 0) unknown.push(t);
  const result = { accepted: 0, tries: 0, unknown, probs: null, weight: 0, layouts: 0 };
  const counts = unknown.map(() => new Float64Array(OUT.length)); const uIdx = new Int32Array(N).fill(-1); unknown.forEach((t, i) => { uIdx[t] = i; });
  // linW: the accepted weight of each layout drawn (its copies at the resamples share it); result.layouts: how
  // many layouts the samples are worth (their weights' effective number)
  const linW = new Map(); let nLin = 0;
  const layoutsNow = () => { let a = 0, b = 0; for (const v of linW.values()) { a += v; b += v * v; } return b ? a * a / b : 0; };
  // cal=1: each tile's chances through CAL (what the samples gave a class none of stays 0, the tile's total is kept)
  const CT = O.cal === 2 ? CAL_MOD : CAL;
  const calibrate = p => { const k = (known.length - 6) / 3, q = new Float64Array(p.length); let z = 0, tot = 0;
    for (let c = 0; c < p.length; c++) { tot += p[c]; if (p[c] > 0) { q[c] = Math.exp(CT.a[c] * Math.log(p[c] + 0.001) + CT.b[c] + CT.d[c] * k); z += q[c]; } }
    return z > 0 ? Array.from(q, v => v / z * tot) : p; };
  const finish = () => { const a = result.weight; result.probs = counts.map(c => a ? Array.from(c, v => v / a) : null); if (O.cal) result.probs = result.probs.map(p => p && calibrate(p)); result.layouts = layoutsNow(); return result; };
  if (entCell < 0) return finish();
  const inward = entCell < W ? entCell + W : entCell >= N - W ? entCell - W : entCell % W === 0 ? entCell + 1 : entCell - 1;
  // unknown cells with a door the map shows from a discovered room
  const doorIn = new Uint8Array(N); for (const t of unknown) for (const u of NB[t]) if (kOwner[u] >= 0 && side.get(sk(t, u)) === 'door') doorIn[t] = 1;
  // the mixes the discovered rooms allow, by weight (mixes6=0: 7-room mixes only; mixfac: times c!/(c-k)! for c
  // rooms of a shape with k of them seen - two 1x3s are twice as likely as one to have one where a 1x3 is shown)
  const zmix = opts.zmix || {};
  const pool = O.mixes6 ? combos : combos.filter(c => c.counts.reduce((a, b) => a + b, 0) === 7);
  const mult = c => c.counts.reduce((a, v, i) => a * fact(v) / fact(v - knownBig[i]), 1);
  const mixes = pool.filter(c => c.counts.every((v, i) => v >= knownBig[i])).map(c => ({ counts: c.counts, w: O.mixfac ? c.w / (zmix['big/try1|' + c.counts.join('')] || 1) * mult(c) : c.w / (zmix['big/try1|' + c.counts.join('')] || 1) }));
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
  // where the map says blood is (prune=2): the seen blood / fairy, else the one unknown cell behind a wither or
  // blood door the map shows from a discovered room (blood is behind it; behind a blood door, blood itself)
  const bloodCell = knownBlood >= 0 ? known[knownBlood].cells[0] : -1, fairyCell = knownFairy >= 0 ? known[knownFairy].cells[0] : -1;
  let headCell = -1, headKind = null, nHead = 0;
  if (bloodCell < 0) for (const t of unknown) for (const u of NB[t]) if (kOwner[u] >= 0) { const k = kindOf.get(sk(t, u)); if (k === 'w' || k === 'b') { headCell = t; headKind = k; nHead++; } }
  if (nHead > 1) { headCell = -1; headKind = null; }
  const noBig = O.prune > 1 && headKind === 'b' ? headCell : -1;  // blood behind the map's blood door is a 1x1
  // joined=1: the discovered rooms all joined to the entrance by the doors the map shows between them (always
  // when found by walking through doors; not always in a real run's order): then each one's door in is one of those
  const kSeen = new Uint8Array(known.length); { const q = [kOwner[entCell]]; kSeen[q[0]] = 1;
    while (q.length) { const k = q.pop(); for (const c of known[k].cells) for (const x of doorNb[c]) { const k2 = kOwner[x]; if (k2 >= 0 && !kSeen[k2]) { kSeen[k2] = 1; q.push(k2); } } } }
  const joined = !!O.joined && kSeen.every(x => x);

  const T = Object.fromEntries(SHAPES.map(x => [x, PL[x].length]));
  // each shape's spots on unknown cells with at most one cell behind a door the map shows (two doors from the
  // discovered part into one room would close a loop), none on noBig, as cell bit masks for the clash test
  const PRE = Object.fromEntries(SHAPES.map(s => [s, PL[s].flatMap(cells => { let nd = 0, lo = 0, hi = 0;
    for (const t of cells) { if (kOwner[t] !== -1 || t === noBig) return []; nd += doorIn[t]; if (t < 30) lo |= 1 << t; else hi |= 1 << (t - 30); }
    return nd <= 1 ? [{ cells, lo, hi }] : []; })]));
  // a room's doors the map shows to other rooms, as (its cell, the other cell) pairs (for mapOk)
  const doorsOf = (owner, cells, r) => { const d = []; for (const c of cells) for (const x of doorNb[c]) if (owner[x] !== r) d.push(c, x); return d; };

  /** 1. a new particle: a mix, then the big rooms not yet seen one by one in random order, each on a spot it
   *  fits drawn alike, weighted by (spots it fits / spots inside the map) - the chance one random spot fits,
   *  so the layouts come out as the game's (one spot each, again on a clash) */
  const newParticle = () => {
    let r0 = rnd() * mixW, mi = 0; while (r0 > mixes[mi].w && mi < mixes.length - 1) r0 -= mixes[mi++].w;
    const todo = []; mixes[mi].counts.forEach((v, i) => { for (let k = knownBig[i]; k < v; k++) todo.push(SHAPES[i]); });
    for (let i = todo.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [todo[i], todo[j]] = [todo[j], todo[i]]; }
    const owner = Int32Array.from(kOwner); const rooms = known.map(r => ({ cells: r.cells, big: r.shape !== 'OneByOne', known: true, type: r.type }));
    let w = 1, oLo = 0, oHi = 0;
    for (const s2 of todo) {
      const pre = PRE[s2]; let nf = 0; for (const f of pre) if (!(f.lo & oLo) && !(f.hi & oHi)) nf++;
      if (!nf) return null;
      w *= nf / T[s2]; let j = Math.floor(rnd() * nf), cs = null; for (const f of pre) if (!(f.lo & oLo) && !(f.hi & oHi) && j-- === 0) { cs = f; break; }
      oLo |= cs.lo; oHi |= cs.hi; for (const t of cs.cells) owner[t] = rooms.length; rooms.push({ cells: cs.cells, big: true, known: false, shape: s2 });
    }
    for (const t of unknown) if (owner[t] === -1) { owner[t] = rooms.length; rooms.push({ cells: [t], big: false, known: false }); }
    for (let r = 0; r < rooms.length; r++) rooms[r].doors = doorsOf(owner, rooms[r].cells, r);
    const pt = startTree(owner, rooms, entCell); pt.rnd = rnd; pt.w = w; pt.lin = nLin++;
    if (!mapOk(pt, entCell, inward)) return null; treeAdd(pt, rule, entCell, inward);
    return pt;
  };
  // the map allows the step u -> v: not through a wall; a room entered once, with no other door the map shows
  // between it and a room already in the tree
  function mapOk(pt, u, v) {
    const { owner, rooms, entered } = pt; const S = owner[v];
    if (S === owner[u]) return true;
    if (wallA[u * N + v]) return false;
    if (joined && !opts.check && !joinedOk(pt, u, v)) return false;
    const d = rooms[S].doors;
    for (let i = 0; i < d.length; i += 2) { if (!entered[owner[d[i + 1]]]) continue; if (d[i] === v && d[i + 1] === u) continue; return false; }
    return true;
  }
  // joined=1: a discovered room entered from the discovered part, a room behind a door the map shows through it
  function joinedOk(pt, u, v) {
    const { owner, rooms } = pt, S = owner[v];
    if (rooms[S].known) return rooms[owner[u]].known;
    for (const c of rooms[S].cells) if (doorIn[c] && !(c === v && rooms[owner[u]].known)) return false;
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
  /** prune=1: the same, the walls the map shows counted (a 1x1 in the tree with no door out and no side left
   *  that isn't a wall or into the tree is a dead end for good): true when it can't */
  const doomed = pt => {
    const { owner, rooms, inT, entered, parent } = pt; let sure = knownDead, maybe = 0;
    for (let r = known.length; r < rooms.length; r++) { if (rooms[r].big) continue; const c = rooms[r].cells[0];
      if (!inT[c]) { maybe++; continue; }
      let kids = 0, open = 0; for (const x of NB[c]) { if (parent[x] === c) kids++; else if (!inT[x] && !entered[owner[x]] && !wallA[c * N + x]) open++; }
      if (kids) continue; if (open) maybe++; else sure++; }
    return sure > Tmax || sure + maybe < Tmin;
  };
  /** prune=2: true when the tree can no longer have blood where the map says, on any of its 2x2 insides (as
   *  finishTree sums over them): a seen blood (or the cell behind a blood door) in the tree not 9-10 steps out
   *  with a 1x1 fairy four back; a seen fairy in the tree not 5-6 out; behind a wither door no 1x1 9-10 out with
   *  no door on yet and no cell 9 or fewer out that can still grow. The checks on cells already in the tree
   *  never change once passed (pt.bok) */
  const back4 = (pa, c) => { for (let k = 0; k < 4 && c >= 0; k++) c = pa[c]; return c; };
  const fairyOk = (pt, f) => { if (f < 0 || f === entCell) return false; const rm = pt.rooms[pt.owner[f]]; if (rm.big) return false; return rm.known ? rm.type === 'FAIRY' : fairyCell < 0; };
  const canGrow = (pt, c) => { if (c === entCell) return false; const { owner, inT, entered } = pt; for (const x of NB[c]) if (!inT[x] && (owner[x] === owner[c] || !entered[owner[x]]) && !wallA[c * N + x]) return true; return false; };
  const insideDraw = rule.inside === 'draw';
  const variants = pt => insideDraw ? [{ parent: pt.parent, depth: pt.depth }] : rooted(pt, rule);
  const bloodAt = (pt, va, c) => (va.depth[c] === 9 || va.depth[c] === 10) && fairyOk(pt, back4(va.parent, c));
  const witherOk = (pt, va) => {
    const { inT, owner, rooms } = pt, { parent, depth } = va;
    const kids = Array.from({ length: N }, () => []); for (let v = 0; v < N; v++) if (inT[v] && parent[v] >= 0) kids[parent[v]].push(v);
    const st = [headCell];
    while (st.length) { const c = st.pop(); const rm = rooms[owner[c]];
      if (c !== headCell && !rm.big && !rm.known && !kids[c].length && bloodAt(pt, va, c)) return true;
      if (depth[c] <= 9 && canGrow(pt, c)) return true;
      for (const k of kids[c]) st.push(k); }
    return false;
  };
  const bloodDoomed = pt => {
    const { inT } = pt; let vs = null; const V = () => vs ||= variants(pt);
    // bok bits: 1 the seen blood / the cell behind the blood door, 2 the seen fairy (checked and fine)
    if (bloodCell >= 0) { if (!inT[bloodCell] || pt.bok & 1) return false; if (!V().some(va => bloodAt(pt, va, bloodCell))) return true; pt.bok |= 1; return false; }
    if (fairyCell >= 0 && inT[fairyCell] && !(pt.bok & 2)) { if (!V().some(va => va.depth[fairyCell] === 5 || va.depth[fairyCell] === 6)) return true; pt.bok |= 2; }
    if (headCell < 0 || !inT[headCell]) return false;
    if (headKind === 'b') { if (pt.bok & 1) return false; if (!V().some(va => bloodAt(pt, va, headCell))) return true; pt.bok |= 1; return false; }
    return !V().some(va => witherOk(pt, va));
  };
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
    const vars = insideDraw ? [{ parent, depth, p: 1 }] : rooted(pt, rule);
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
    // rarew: a 5-puzzle tree weighs rarew per dead end past 7 besides blood (its rares)
    const fr5 = puzzles >= 5 && O.rarew !== 1 ? O.rarew ** (D - 7) : 1;
    return { f: fr5 === 1 ? fsum * wType : fsum * wType * fr5, type, parent: va.parent, dist, D };
  };
  const ess = ps => { let a = 0, b = 0; for (const x of ps) { a += x.w; b += x.w * x.w; } return b ? a * a / b : 0; };
  const resample = ps => { const tot = ps.reduce((a, x) => a + x.w, 0); if (!tot) return ps; const n = ps.length, mean = tot / n, out = []; let u = rnd() * tot / n, c = 0, i = 0;
    for (let k = 0; k < n; k++) { const target = u + k * tot / n; while (c + ps[i].w < target && i < n - 1) { c += ps[i].w; i++; } out.push(clone(ps[i], mean)); } return out; };
  const clone = cloneTree;
  // move=k: after resampling, k tries per particle at moving a big room the tree hasn't come next to yet (no
  // cell in it or beside it) to another spot it fits that the tree hasn't come next to either, all such spots
  // alike (its own among them). The growth so far didn't depend on where those rooms are (only rooms beside the
  // tree enter the game's chances or the map's checks) and every complete layout is alike, so this keeps the
  // target and the weights; it gives the copies a resample makes their own layouts where the tree hasn't been
  const near = new Uint8Array(N);
  const rejuvenate = pt => {
    const { inT } = pt; near.fill(0); for (let v = 0; v < N; v++) if (inT[v]) { near[v] = 1; for (const x of NB[v]) near[x] = 1; }
    let { owner, rooms } = pt, copied = false;
    const movable = []; for (let r = known.length; r < rooms.length; r++) if (rooms[r].big && rooms[r].cells.every(c => !near[c])) movable.push(r);
    if (!movable.length) return;
    for (let m = 0; m < O.move; m++) {
      const r = movable[Math.floor(rnd() * movable.length)], rm = rooms[r];
      const free = t => !near[t] && (owner[t] === r || owner[t] >= known.length && !rooms[owner[t]].big);
      const fit = PRE[rm.shape].filter(f => f.cells.every(free));
      const cs = fit[Math.floor(rnd() * fit.length)].cells;
      if (cs.every(t => owner[t] === r)) continue;
      // copy on write: the copies of one particle share its layout
      if (!copied) { owner = pt.owner = Int32Array.from(owner); rooms = pt.rooms = rooms.slice(); copied = true; }
      const ones = cs.filter(t => owner[t] !== r).map(t => owner[t]), freed = rm.cells.filter(t => !cs.includes(t));
      for (const t of cs) owner[t] = r;
      ones.forEach((o, i) => { owner[freed[i]] = o; rooms[o] = { cells: [freed[i]], big: false, known: false }; rooms[o].doors = doorsOf(owner, rooms[o].cells, o); });
      rooms[r] = { ...rm, cells: cs }; rooms[r].doors = doorsOf(owner, cs, r);
      result.moved = (result.moved || 0) + 1;
    }
  };
  const resampleMove = ps => { const out = resample(ps); if (O.move && out !== ps) for (const pt of out) if (pt.w > 0) rejuvenate(pt); return out; };
  // distinct=1: a finished map's key, its layout and room-level tree (a big room's inside summed over, unless
  // inside=draw or at level cell)
  const keyOf = pt => { const { owner, parent } = pt, roomOnly = rule.level === 'room' && !insideDraw;
    let s = ''; for (let v = 0; v < N; v++) s += owner[v] + ':' + (roomOnly && parent[v] >= 0 && owner[parent[v]] === owner[v] ? -2 : parent[v]) + ','; return s; };

  // sequential Monte Carlo: batches of particles grown together, resampled when their weights spread out
  const NP = rule.np || opts.particles || 200;  // rule np= / opts.particles: the batch size
  // a batch's samples descend from few ancestors, so the batches are the independent draws: go on until NS
  // samples and (rule batches= / opts.batches) that many batches' worth of weight, (sum w)^2 / sum w^2 over them
  // (with NS samples alone, a batch whose ancestors happen to fit often fills them: one or two layouts); lshare=f:
  // and until they are worth NS x f layouts
  const minB = rule.batches ?? opts.batches ?? 20; let bw1 = 0, bw2 = 0;
  const essT = NP * O.ess;
  while ((result.accepted < NS || (bw2 ? bw1 * bw1 / bw2 : 0) < minB || O.lshare && layoutsNow() < NS * O.lshare) && result.tries < maxTries) {
    let ps = []; for (let i = 0; i < NP; i++) { result.tries++; const pt = newParticle(); if (pt) ps.push(pt); else ps.push({ w: 0 }); }
    ps = ps.filter(x => x.w > 0); if (!ps.length) continue;
    while (ps.length < NP) ps.push(clone(ps[Math.floor(rnd() * ps.length)], 0));  // keep the batch size; zero-weight fillers drop at the next resample
    if (ess(ps) < essT) ps = resampleMove(ps);
    for (let s2 = 0; s2 < N - 2; s2++) {
      for (const pt of ps) if (pt.w > 0 && !pt.done) { const n0 = pt.nodes.length; growStep(pt, rule, mapOk);
        if (pt.w > 0 && rule.deadkill && !deadOk(pt)) pt.w = 0;
        // opts.check (a test of prune / joined): mark the trees they would drop instead, and count at the end
        // those finishTree keeps (result.checkFail, should be 0) among the marked (result.checkMarked)
        if (opts.check && joined && pt.nodes.length > n0) { const v = rule.level === 'room' ? pt.rentry[pt.nodes[n0]] : pt.nodes[n0], u = pt.parent[v]; if (u >= 0 && pt.owner[u] !== pt.owner[v] && !joinedOk(pt, u, v)) pt.chk = 1; }
        if (pt.w > 0 && O.prune && (doomed(pt) || O.prune > 1 && bloodDoomed(pt))) { if (opts.check) pt.chk = 1; else { pt.w = 0; result.pruned = (result.pruned || 0) + 1; } } }
      if (!ps.some(x => x.w > 0)) break;
      // once every tree is finished nothing changes but a resample (endrs=0: not that either)
      const live = ps.some(x => x.w > 0 && !x.done);
      if ((live || O.endrs) && ess(ps) < essT) ps = resampleMove(ps);
      if (!live) break;
    }
    const bs = { start: ps.filter(x => x.w > 0).length, grown: 0, kept: 0, w: 0 };
    const seen = O.distinct ? new Set() : null;
    for (const pt of ps) {
      if (!(pt.w > 0) || pt.inT.some(x => !x)) continue;
      bs.grown++;
      const fin = finishTree(pt);
      if (pt.chk) { result.checkMarked = (result.checkMarked || 0) + 1; if (fin && pt.w * fin.f > 0) result.checkFail = (result.checkFail || 0) + 1; }
      if (!fin) continue;
      const wgt = pt.w * fin.f; if (!(wgt > 0)) continue;
      bs.kept++; bs.w += wgt;
      // distinct=1: resampled copies of one finished map are one sample
      if (seen) { const h = keyOf(pt); if (!seen.has(h)) { seen.add(h); result.accepted++; } } else result.accepted++;
      result.weight += wgt; linW.set(pt.lin, (linW.get(pt.lin) || 0) + wgt);
      for (let r = 0; r < pt.rooms.length; r++) { const rm = pt.rooms[r]; if (rm.known) continue;
        const d = fin.dist && !rm.big && fin.dist.get(r);
        if (d) { let o = 1; for (let c = 0; c < OUT.length; c++) if (d[c]) { o -= d[c]; for (const t of rm.cells) counts[uIdx[t]][c] += wgt * d[c]; } if (o > 1e-12) for (const t of rm.cells) counts[uIdx[t]][OI.o] += wgt * o; continue; }
        const c = OI[rm.big ? 'n' : fin.dist ? 'o' : fin.type[r] || 'o']; for (const t of rm.cells) counts[uIdx[t]][c] += wgt; }
      if (opts.onSample) opts.onSample({ owner: pt.owner, rooms: pt.rooms, type: fin.type, parent: fin.parent, weight: wgt, D: fin.D });
    }
    bw1 += bs.w; bw2 += bs.w * bs.w;
    // onBatch({ start, grown, kept, w }): per batch, the particles placed, fully grown, kept, and their weight
    if (opts.onBatch) opts.onBatch(bs);
  }
  return finish();
}
