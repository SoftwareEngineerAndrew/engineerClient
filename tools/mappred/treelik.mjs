// Which door-tree rule does the game use? For each complete map (its rooms where they are), the chance a rule
// makes exactly its doors and puts blood where it is, among the trees the game keeps (the dead-end count it
// has, a blood spot): sequential Monte Carlo over the growth (every door known, so only the real tree is
// allowed; inside a big room anything), over the share of free runs on the same layout that the game keeps.
//   LAYOUTS=layouts.jsonl node treelik.mjs [maps]        RULES='cell/side/0.15/0,...' (level/pick/p/q[/name=value...]:
//   tree3.mjs parseRule, e.g. room/node/0/0/alpha=0.5/beta=0.5/mu=0.2)  NP, FREE: particles, free runs a map
import { complete } from './common.mjs';
import { makeRng } from './mlp.mjs';
import { N, NB, inwardOf, startTree, seedTree, growStep, cloneTree, parseRule, rooted } from './tree3.mjs';
const W = 6;
const rnd = makeRng(+(process.env.SEED || 11));
const NP = +(process.env.NP || 200), FREE = +(process.env.FREE || 200);
// IDS=<file>: only the maps with these ids (a JSON list), e.g. ones that aren't evalbig's held-out maps
const only = process.env.IDS ? new Set(JSON.parse((await import('node:fs')).readFileSync(process.env.IDS, 'utf8'))) : null;
const maps = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7 && (!only || only.has(L.id))).slice(0, +(process.argv[2] || 600)).map(L => {
  const owner = new Int32Array(N).fill(-1); const rooms = L.rooms.map(r => ({ cells: r.tiles.map(t => t.z * W + t.x), big: r.shape !== 'OneByOne', type: r.type }));
  rooms.forEach((r, i) => { for (const c of r.cells) owner[c] = i; });
  const door = new Set(); for (const d of L.doors) if (d.what !== '-' && d.a >= 0 && d.b >= 0 && d.a !== d.b) { const a = d.az * W + d.ax, b = d.bz * W + d.bx; door.add(a < b ? a * N + b : b * N + a); }
  const ent = rooms.find(r => r.type === 'ENTRANCE').cells[0];
  const entR = owner[ent], bloodR = rooms.findIndex(r => r.type === 'BLOOD'), fairyR = rooms.findIndex(r => r.type === 'FAIRY');
  const D = rooms.filter((r, i) => !r.big && i !== entR && i !== bloodR && L.g.get(L.rooms[i].i).length === 1).length;
  return { owner, rooms, door, ent, entR, bloodR, fairyR, D };
});
const key = (a, b) => a < b ? a * N + b : b * N + a;
/** the finished tree's dead ends (1x1, not the entrance) and blood candidates */
function deadAndCands(M, st) {
  const { owner, rooms } = M; const R = rooms.length; const deg = new Int32Array(R);
  // st: a tree, or one of its rooted() variants ({ parent, depth })
  for (let v = 0; v < N; v++) { const u = st.parent[v]; if (u >= 0 && owner[u] !== owner[v]) { deg[owner[u]]++; deg[owner[v]]++; } }
  const dead = []; for (let r = 0; r < R; r++) if (r !== M.entR && !rooms[r].big && deg[r] === 1) dead.push(r);
  const cands = []; for (const b of dead) { const t = rooms[b].cells[0]; if (st.depth[t] !== 9 && st.depth[t] !== 10) continue; let f = t; for (let k = 0; k < 4; k++) f = st.parent[f]; const fr = owner[f]; if (fr !== M.entR && !rooms[fr].big) cands.push([b, fr]); }
  return { dead, cands };
}
function logLik(M, rule) {
  const { owner, rooms, door, ent } = M; const inward = inwardOf(ent);
  // the map's own doors: a room entered only by one of them, with no other door to a room already in
  const allowed = (st, u, v) => { if (owner[u] === owner[v]) return true; if (!door.has(key(u, v))) return false;
    // (a growth that starts elsewhere joins the entrance only at the end)
    const S = owner[v]; for (const c of rooms[S].cells) for (const x of NB[c]) { const Rx = owner[x]; if (Rx === S || !st.entered[Rx] || st.root !== undefined && Rx === M.entR) continue; if (c === v && x === u) continue; if (door.has(key(c, x))) return false; } return true; };
  let ps = []; for (let i = 0; i < NP; i++) { const st = startTree(owner, rooms, ent); st.rnd = rnd; seedTree(st, rule); ps.push(st); }
  for (;;) { let any = false; for (const st of ps) if (st.w > 0 && !st.done) { if (growStep(st, rule, allowed)) any = true; }
    if (!any) break;
    let a = 0, b = 0; for (const x of ps) { a += x.w; b += x.w * x.w; } if (!a) return -Infinity;
    if (a * a / b < NP / 2) { const mean = a / NP, out = []; let u = rnd() * a / NP, c = 0, i = 0; for (let k = 0; k < NP; k++) { const t = u + k * a / NP; while (c + ps[i].w < t && i < NP - 1) { c += ps[i].w; i++; } out.push(cloneTree(ps[i], mean)); } ps = out; } }
  // blood where it is: one of the candidates alike
  let z = 0; for (const st of ps) { if (!(st.w > 0)) continue; let all = 0; for (let v = 0; v < N; v++) if (!st.inT[v]) all++; if (all) continue;
    // over the 2x2s' insides (INSIDE=draw: the one drawn as it grew)
    for (const va of process.env.INSIDE === 'draw' ? [{ parent: st.parent, depth: st.depth, p: 1 }] : rooted(st, rule)) {
      const { cands } = deadAndCands(M, va); if (cands.some(([b, f]) => b === M.bloodR && f === M.fairyR)) z += st.w * va.p / cands.length; } }
  z /= NP;
  // the share of free runs the game keeps
  let keep = 0; for (let i = 0; i < FREE; i++) { const st = startTree(owner, rooms, ent); st.rnd = rnd; seedTree(st, rule); while (growStep(st, rule, null));
    for (const va of rooted(st, rule)) { const { dead, cands } = deadAndCands(M, va); if (dead.length - 1 === M.D && cands.length) keep += va.p; } }
  return Math.log(Math.max(z, 1e-300)) - Math.log(Math.max(keep, 0.5) / FREE);
}
// GEN=level/pick/p/q: each map's tree grown again by that rule on its own layout (kept as the game keeps them:
// 4-8 dead ends besides blood, a blood spot; blood one of them alike) - the test on trees whose rule is known
if (process.env.GEN) {
  const g = parseRule(process.env.GEN);
  for (const M of maps) for (let tr = 0; tr < 2000; tr++) {
    const st0 = startTree(M.owner, M.rooms, M.ent); st0.rnd = rnd; seedTree(st0, g); while (growStep(st0, g, null));
    const vs = rooted(st0, g); let r = rnd(), st = vs[0]; for (const va of vs) { st = va; if ((r -= va.p) <= 0) break; }
    const { dead, cands } = deadAndCands(M, st); if (!cands.length || dead.length - 1 < 4 || dead.length - 1 > 8) continue;
    const [b, f] = cands[Math.floor(rnd() * cands.length)];
    M.door = new Set(); for (let v = 0; v < N; v++) { const u = st.parent[v]; if (u >= 0 && M.owner[u] !== M.owner[v]) M.door.add(key(u, v)); }
    M.bloodR = b; M.fairyR = f; M.D = dead.length - 1; break;
  }
}
// STATS=1: door statistics of the maps (after GEN, the regrown ones), then stop
if (process.env.STATS) {
  const c = {}; const add = (k, v = 1) => { c[k] = (c[k] || 0) + v; };
  for (const M of maps) {
    const R = M.rooms.length; const deg = new Int32Array(R); const adj = Array.from({ length: R }, () => []);
    for (const k of M.door) { const a = M.owner[(k / N) | 0], b = M.owner[k % N]; deg[a]++; deg[b]++; adj[a].push(b); adj[b].push(a); }
    const dist = new Int32Array(R).fill(-1); dist[M.entR] = 0; const q = [M.entR]; for (let i = 0; i < q.length; i++) for (const b of adj[q[i]]) if (dist[b] < 0) { dist[b] = dist[q[i]] + 1; q.push(b); }
    for (let r = 0; r < R; r++) { if (r === M.entR) continue; const kind = M.rooms[r].big ? (M.rooms[r].cells.length === 2 ? 'big2' : M.rooms[r].cells.length === 3 ? 'big3' : 'big4') : '1x1';
      add(`${kind} doors ${Math.min(deg[r], 4)}`); add(`${kind} n`); add(`rooms at room-step ${Math.min(dist[r], 9)}`); if (deg[r] === 1) add(`leaves at room-step ${Math.min(dist[r], 9)}`); }
    add('deepest room-step', Math.max(...dist)); add('maps');
    add('blood room-step', dist[M.bloodR]);
    // children of the room behind the entrance
    add('room behind entrance: doors', deg[M.owner[inwardOf(M.ent)]]);
    // which of the sides a room shares with its parent has the door: the one nearer where the parent was
    // entered (steps over its cells), against the share of the sides that are nearest
    const entry = new Int32Array(R).fill(-1), pdoor = []; entry[M.entR] = M.ent; const qq = [M.entR];
    const cdoors = Array.from({ length: R }, () => []); for (const k of M.door) { const a = (k / N) | 0, b = k % N; cdoors[M.owner[a]].push([a, b]); cdoors[M.owner[b]].push([b, a]); }
    for (let i = 0; i < qq.length; i++) for (const [a, b] of cdoors[qq[i]]) if (entry[M.owner[b]] < 0) { entry[M.owner[b]] = b; pdoor[M.owner[b]] = [a, b, qq[i]]; qq.push(M.owner[b]); }
    for (let S = 0; S < R; S++) { if (S === M.entR) continue; const [du, dv, P] = pdoor[S];
      const shared = []; for (const u of M.rooms[P].cells) for (const v of NB[u]) if (M.owner[v] === S) shared.push([u, v]); if (shared.length < 2) continue;
      const d = new Map([[entry[P], 0]]), q2 = [entry[P]]; for (let i = 0; i < q2.length; i++) for (const y of NB[q2[i]]) if (M.owner[y] === P && !d.has(y)) { d.set(y, d.get(q2[i]) + 1); q2.push(y); }
      const ds = shared.map(([u]) => d.get(u)), mn = Math.min(...ds); if (mn === Math.max(...ds)) continue;
      const kk = `door into a ${M.rooms[S].big ? 'big room' : '1x1'}, ${Math.min(shared.length, 3)} sides: nearest`; add(`${kk} n`); add(`${kk} unif`, ds.filter(x => x === mn).length / shared.length);
      if (d.get(du) === mn && shared.some(([u, v]) => u === du && v === dv)) add(kk); }
  }
  for (const k of Object.keys(c).sort()) { if (k.endsWith(' unif')) continue; if (k.startsWith('door into')) { if (!k.endsWith(' n')) console.log(`  ${k.padEnd(44)} ${(c[k] / c[k + ' n']).toFixed(3)} (alike ${(c[k + ' unif'] / c[k + ' n']).toFixed(3)}, ${c[k + ' n']} doors)`); continue; }
    const m = /^(\w+) doors/.exec(k); const v = m && c[`${m[1]} n`] ? c[k] / c[`${m[1]} n`] : c[k] / c.maps; if (!k.endsWith(' n') && k !== 'maps') console.log(`  ${k.padEnd(28)} ${v.toFixed(3)}`); }
  process.exit(0);
}
const RULES = (process.env.RULES || 'cell/side/0.15/0,cell/side/0/0,cell/node/0/0,cell/node/0.15/0,room/side/0/0,room/node/0/0,room/node/0.15/0,cell/side/0.3/0,cell/node/0.3/0,cell/side/0/0.1').split(',').map(parseRule);
const res = {};
for (const M of maps) for (const r of RULES) (res[r.name] ||= []).push(logLik(M, r));
const ref = res[RULES[0].name];
console.log(`${maps.length} maps; mean log chance of the real doors and blood given the layout (higher = the rule makes them likelier), against ${RULES[0].name}`);
for (const [k, v] of Object.entries(res).sort((a, b) => b[1].reduce((x, y) => x + y) - a[1].reduce((x, y) => x + y))) {
  const fin = v.map((x, i) => [x, ref[i]]).filter(([x, y]) => isFinite(x) && isFinite(y)); const d = fin.map(([x, y]) => x - y); const md = d.reduce((a, b) => a + b, 0) / d.length; const se = Math.sqrt(d.reduce((a, b) => a + (b - md) ** 2, 0) / d.length / d.length);
  console.log(`  ${k.padEnd(18)} ${(v.filter(isFinite).reduce((a, b) => a + b, 0) / v.filter(isFinite).length).toFixed(3).padStart(8)}   ${md >= 0 ? '+' : ''}${md.toFixed(3)} (s.e. ${se.toFixed(3)})   ${v.filter(x => !isFinite(x)).length} maps it can't make`);
}
