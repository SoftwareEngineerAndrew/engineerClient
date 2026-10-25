// Score test for features a rule lacks: d/dtheta of the mean log chance of the real trees (given layout, kept)
// if each free side's chance were multiplied by exp(theta * feature). Per map:
//   E_posterior over growth orders of the real tree [sum over steps (f(taken) - E_rule f)]  -  the same over kept free runs
// RULE=level/pick/p/q[/...]  LAYOUTS=... node scorediag.mjs [maps]
import { complete } from './common.mjs';
import { makeRng } from './mlp.mjs';
import { N, NB, startTree, seedTree, growStep, cloneTree, parseRule, rooted } from './tree3.mjs';
const W = 6;
const rnd = makeRng(+(process.env.SEED || 11));
const NP = +(process.env.NP || 300), FREE = +(process.env.FREE || 300);
const rule = parseRule(process.env.RULE || 'room/node/0/0/alpha=0.5/beta=0.5');
const maps = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7).slice(0, +(process.argv[2] || 300)).map(L => {
  const owner = new Int32Array(N).fill(-1); const rooms = L.rooms.map(r => ({ cells: r.tiles.map(t => t.z * W + t.x), big: r.shape !== 'OneByOne', type: r.type }));
  rooms.forEach((r, i) => { for (const c of r.cells) owner[c] = i; });
  const door = new Set(); for (const d of L.doors) if (d.what !== '-' && d.a >= 0 && d.b >= 0 && d.a !== d.b) { const a = d.az * W + d.ax, b = d.bz * W + d.bx; door.add(a < b ? a * N + b : b * N + a); }
  const ent = rooms.find(r => r.type === 'ENTRANCE').cells[0];
  const entR = owner[ent], bloodR = rooms.findIndex(r => r.type === 'BLOOD'), fairyR = rooms.findIndex(r => r.type === 'FAIRY');
  const D = rooms.filter((r, i) => !r.big && i !== entR && i !== bloodR && L.g.get(L.rooms[i].i).length === 1).length;
  return { owner, rooms, door, ent, entR, bloodR, fairyR, D };
});
const key = (a, b) => a < b ? a * N + b : b * N + a;
function deadAndCands(M, st) {
  const { owner, rooms } = M; const R = rooms.length; const deg = new Int32Array(R);
  for (let v = 0; v < N; v++) { const u = st.parent[v]; if (u >= 0 && owner[u] !== owner[v]) { deg[owner[u]]++; deg[owner[v]]++; } }
  const dead = []; for (let r = 0; r < R; r++) if (r !== M.entR && !rooms[r].big && deg[r] === 1) dead.push(r);
  const cands = []; for (const b of dead) { const t = rooms[b].cells[0]; if (st.depth[t] !== 9 && st.depth[t] !== 10) continue; let f = t; for (let k = 0; k < 4; k++) f = st.parent[f]; const fr = owner[f]; if (fr !== M.entR && !rooms[fr].big) cands.push([b, fr]); }
  return { dead, cands };
}
const man = (a, b) => Math.abs(a % W - b % W) + Math.abs(((a / W) | 0) - ((b / W) | 0));
// the features of a free side [u, v] off node nd, in the state before the step
const FEATS = {
  rdist: (st, u, v) => st.rdist[u],
  rdist1x1: (st, u, v) => st.rooms[st.owner[v]].cells.length === 1 ? st.rdist[u] : 0,
  rdistBig: (st, u, v) => st.rooms[st.owner[v]].cells.length > 1 ? st.rdist[u] : 0,
  atEntry: (st, u, v) => st.rooms[st.owner[u]].cells.length > 1 && st.rentry[st.owner[u]] === u ? 1 : 0,
  tgtBig: (st, u, v) => st.rooms[st.owner[v]].cells.length > 1 ? 1 : 0,
  tgtCells: (st, u, v) => st.rooms[st.owner[v]].cells.length,
  fromBig: (st, u, v) => st.rooms[st.owner[u]].cells.length > 1 ? 1 : 0,
  fromCells: (st, u, v) => st.rooms[st.owner[u]].cells.length,
  tgtOpen: (st, u, v) => { const S = st.owner[v]; let n = 0; for (const c of st.rooms[S].cells) for (const y of NB[c]) if (st.owner[y] !== S && !st.entered[st.owner[y]]) n++; return n; },
  tgtToTree: (st, u, v) => { const S = st.owner[v]; let n = 0; for (const c of st.rooms[S].cells) for (const y of NB[c]) if (st.owner[y] !== S && st.entered[st.owner[y]] && y !== st.ent) n++; return n; },
  nodeKids: (st, u, v) => st.kids[st.owner[u]],
  nodeRdepth: (st, u, v) => st.rdepth[st.owner[u]],
  nodeAge: (st, u, v) => st.nodes.length - 1 - st.nodes.indexOf(st.owner[u]),
  isNewest: (st, u, v) => st.nodes[st.nodes.length - 1] === st.owner[u] ? 1 : 0,
  isFirst: (st, u, v) => st.nodes[0] === st.owner[u] ? 1 : 0,
  cellDepth: (st, u, v) => st.depth[u],
  manEnt: (st, u, v) => man(v, st.ent),
  edgeV: (st, u, v) => { const x = v % W, z = (v / W) | 0; return (x === 0 || x === 5 || z === 0 || z === 5) ? 1 : 0; },
  cornerV: (st, u, v) => { const x = v % W, z = (v / W) | 0; return ((x === 0 || x === 5) && (z === 0 || z === 5)) ? 1 : 0; },
  horiz: (st, u, v) => Math.abs(u - v) === 1 ? 1 : 0,
  away: (st, u, v) => man(v, st.ent) - man(u, st.ent),
  nodeFree: () => 0,  // (onStep fills it in: the log of the node's free sides, alpha's term)
};
const FN = Object.keys(FEATS); const K = FN.length;
function onStep(st, all, taken) {
  let Z = 0; const mu = new Float64Array(K);
  // log of the node's free sides (alpha's term)
  const nfree = new Map(); for (const [[u], , nd] of all) nfree.set(nd, (nfree.get(nd) || 0) + 1);
  const f = (u, v, nd) => FN.map(n => n === 'nodeFree' ? Math.log(nfree.get(nd)) : FEATS[n](st, u, v));
  let ft = null;
  for (const [[u, v], pr, nd] of all) { const x = f(u, v, nd); Z += pr; for (let k = 0; k < K; k++) mu[k] += pr * x[k]; if (u === taken[0] && v === taken[1]) ft = x; }
  for (let k = 0; k < K; k++) st.score[k] += ft[k] - mu[k] / Z;
}
const clone = (st, w) => { const c = cloneTree(st, w); c.score = Float64Array.from(st.score); return c; };
const tot = new Float64Array(K), tot2 = new Float64Array(K); let nm = 0, llsum = 0;
for (const M of maps) {
  const { owner, rooms, door, ent } = M;
  const allowed = (st, u, v) => { if (owner[u] === owner[v]) return true; if (!door.has(key(u, v))) return false;
    const S = owner[v]; for (const c of rooms[S].cells) for (const x of NB[c]) { const Rx = owner[x]; if (Rx === S || !st.entered[Rx]) continue; if (c === v && x === u) continue; if (door.has(key(c, x))) return false; } return true; };
  const mk = () => { const st = startTree(owner, rooms, ent); st.rnd = rnd; st.onStep = onStep; st.score = new Float64Array(K); seedTree(st, rule); return st; };
  let ps = []; for (let i = 0; i < NP; i++) ps.push(mk());
  for (;;) { let any = false; for (const st of ps) if (st.w > 0 && !st.done) { if (growStep(st, rule, allowed)) any = true; }
    if (!any) break;
    let a = 0, b = 0; for (const x of ps) { a += x.w; b += x.w * x.w; }
    if (a * a / b < NP / 2) { const mean = a / NP, out = []; let u = rnd() * a / NP, c = 0, i = 0; for (let k = 0; k < NP; k++) { const t = u + k * a / NP; while (c + ps[i].w < t && i < NP - 1) { c += ps[i].w; i++; } out.push(clone(ps[i], mean)); } ps = out; } }
  let z = 0; const sp = new Float64Array(K);
  for (const st of ps) { if (!(st.w > 0)) continue; for (const va of rooted(st, rule)) { const { cands } = deadAndCands(M, va); if (cands.some(([b, f]) => b === M.bloodR && f === M.fairyR)) { const wt = st.w * va.p / cands.length; z += wt; for (let k = 0; k < K; k++) sp[k] += wt * st.score[k]; } } }
  if (!(z > 0)) continue;
  // kept free runs
  let kw = 0; const sk = new Float64Array(K);
  for (let i = 0; i < FREE; i++) { const st = mk(); while (growStep(st, rule, null)); for (const va of rooted(st, rule)) { const { dead, cands } = deadAndCands(M, va); if (dead.length - 1 === M.D && cands.length) { kw += va.p; for (let k = 0; k < K; k++) sk[k] += va.p * st.score[k]; } } }
  if (!(kw > 0)) continue;
  nm++; llsum += Math.log(z / NP) - Math.log(kw / FREE);
  for (let k = 0; k < K; k++) { const d = sp[k] / z - sk[k] / kw; tot[k] += d; tot2[k] += d * d; }
}
console.log(`${nm} maps, rule ${rule.name}, mean log chance ${(llsum / nm).toFixed(3)}; score per map (d mean-ll / d theta), its s.e., z`);
for (let k = 0; k < K; k++) { const m = tot[k] / nm, se = Math.sqrt((tot2[k] / nm - m * m) / nm); console.log(`  ${FN[k].padEnd(12)} ${m.toFixed(4).padStart(9)}  ${se.toFixed(4)}  ${(m / se).toFixed(1).padStart(6)}`); }
