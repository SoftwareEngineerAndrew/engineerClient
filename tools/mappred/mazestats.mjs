// How bushy the door trees are: the real maps' against trees a rule grows again on each real layout (kept as the
// game keeps them: the map's own count of 1x1 dead ends besides blood, a blood spot). The cell graph is the doors
// plus every room's inside (a 2x2 is a 4-cycle), the same for both, so only the doors differ.
//   LAYOUTS=layouts.jsonl node mazestats.mjs [maps] [rule,rule,...]   (tree3 rules; default the GameSampler's)
import { complete } from './common.mjs';
import { makeRng } from './mlp.mjs';
import { N, NB, inwardOf, startTree, seedTree, growStep, parseRule, rooted } from './tree3.mjs';
const W = 6;
const rnd = makeRng(+(process.env.SEED || 7));
const RULES = (process.argv[3] || 'room/node/0/0/alpha=0.5/beta=0.5/mu=0.2').split(',').map(parseRule);
const key = (a, b) => a < b ? a * N + b : b * N + a;
const maps = complete().filter(L => L.rooms.filter(r => r.shape !== 'OneByOne').length === 7).slice(0, +(process.argv[2] || 600)).map(L => {
  const owner = new Int32Array(N).fill(-1); const rooms = L.rooms.map(r => ({ cells: r.tiles.map(t => t.z * W + t.x), big: r.shape !== 'OneByOne', type: r.type }));
  rooms.forEach((r, i) => { for (const c of r.cells) owner[c] = i; });
  const door = new Set(); for (const d of L.doors) if (d.what !== '-' && d.a >= 0 && d.b >= 0 && d.a !== d.b) door.add(key(d.az * W + d.ax, d.bz * W + d.bx));
  const ent = rooms.find(r => r.type === 'ENTRANCE').cells[0], entR = owner[ent], bloodR = rooms.findIndex(r => r.type === 'BLOOD');
  const deg = new Int32Array(rooms.length); for (const k of door) { deg[owner[(k / N) | 0]]++; deg[owner[k % N]]++; }
  const D = rooms.filter((r, i) => !r.big && i !== entR && i !== bloodR && deg[i] === 1).length;
  return { owner, rooms, door, ent, entR, D };
});
/** door tree -> 1x1 dead ends (rooms, not the entrance) and whether a blood spot exists (cell walk 9/10, a 1x1 4 before) */
function deadAndSpot(M, parent, depth) {
  const { owner, rooms } = M; const deg = new Int32Array(rooms.length);
  for (let v = 0; v < N; v++) { const u = parent[v]; if (u >= 0 && owner[u] !== owner[v]) { deg[owner[u]]++; deg[owner[v]]++; } }
  let dead = 0, spot = false;
  for (let r = 0; r < rooms.length; r++) if (r !== M.entR && !rooms[r].big && deg[r] === 1) { dead++;
    const t = rooms[r].cells[0]; if (depth[t] === 9 || depth[t] === 10) { let f = t; for (let k = 0; k < 4; k++) f = parent[f]; if (owner[f] !== M.entR && !rooms[owner[f]].big) spot = true; } }
  return { dead, spot };
}
/** the maze figures of one map's doors */
function figures(M, door) {
  const { owner, ent } = M; const adj = Array.from({ length: N }, () => []);
  for (let c = 0; c < N; c++) for (const y of NB[c]) if (y > c && (owner[y] === owner[c] || door.has(key(c, y)))) { adj[c].push(y); adj[y].push(c); }
  const f = { stub1: 0, stub2: 0, stub3: 0, straight: 0, turn: 0, junctions: 0, leafDepth: 0, leaves: 0, maxDepth: 0, bigDead: 0, bigLeafCells: 0, bigDoors: 0 };
  { const deg = new Map(); for (const k of door) { const a = owner[(k / N) | 0], b = owner[k % N]; deg.set(a, (deg.get(a) || 0) + 1); deg.set(b, (deg.get(b) || 0) + 1); }
    M.rooms.forEach((r, i) => { if (r.big) { f.bigDoors += deg.get(i) || 0; if (deg.get(i) === 1) f.bigDead++; } }); }
  const depth = new Int32Array(N).fill(-1); depth[ent] = 0; const q = [ent]; for (let i = 0; i < q.length; i++) for (const y of adj[q[i]]) if (depth[y] < 0) { depth[y] = depth[q[i]] + 1; q.push(y); }
  for (let c = 0; c < N; c++) {
    const d = adj[c].length; if (d >= 3) f.junctions++;
    if (d === 2) { const [a, b] = adj[c]; if (Math.abs(a - b) === 2 || Math.abs(a - b) === 2 * W) f.straight++; else f.turn++; }
    f.maxDepth = Math.max(f.maxDepth, depth[c]);
    if (d === 1 && c !== ent) { f.leaves++; if (M.rooms[owner[c]].big) f.bigLeafCells++; f.leafDepth += depth[c];
      // the stub: cells from the leaf back to the first junction (or the entrance)
      let len = 1, prev = c, cur = adj[c][0]; while (adj[cur].length === 2 && cur !== ent) { const nx = adj[cur][0] === prev ? adj[cur][1] : adj[cur][0]; prev = cur; cur = nx; len++; }
      if (len === 1) f.stub1++; else if (len === 2) f.stub2++; else f.stub3++; }
  }
  return f;
}
const sum = {}, sq = {}; const add = (k, v) => { sum[k] = (sum[k] || 0) + v; sq[k] = (sq[k] || 0) + v * v; };
const row = (tag, f) => { for (const [k, v] of Object.entries(f)) add(`${tag}|${k}`, v); add(`${tag}|maps`, 1); add(`${tag}|straight%`, f.straight / (f.straight + f.turn)); add(`${tag}|leafDepth/leaf`, f.leafDepth / f.leaves); };
for (const M of maps) row('real', figures(M, M.door));
for (const rule of RULES) for (const M of maps) {
  for (let tr = 0; tr < 5000; tr++) {
    const st = startTree(M.owner, M.rooms, M.ent); st.rnd = rnd; seedTree(st, rule); while (growStep(st, rule, null));
    const vs = rooted(st, rule); let r = rnd(), va = vs[0]; for (const x of vs) { va = x; if ((r -= x.p) <= 0) break; }
    const { dead, spot } = deadAndSpot(M, va.parent, va.depth);
    if (tr < 20) add(`${rule.name}|free dead`, dead), add(`${rule.name}|free n`, 1);
    if (!spot || dead - 1 !== M.D) continue;
    const door = new Set(); for (let v = 0; v < N; v++) { const u = va.parent[v]; if (u >= 0 && M.owner[u] !== M.owner[v]) door.add(key(u, v)); }
    row(rule.name, figures(M, door)); break;
  }
}
const tags = ['real', ...RULES.map(r => r.name)];
const ks = ['bigDead', 'bigDoors', 'bigLeafCells', 'stub1', 'stub2', 'stub3', 'leaves', 'junctions', 'straight%', 'leafDepth/leaf', 'maxDepth'];
console.log(`${maps.length} maps; per map (s.e.)`.padEnd(22) + tags.map(t => t.slice(-40).padStart(42)).join(''));
for (const k of ks) console.log(k.padEnd(22) + tags.map(t => { const n = sum[`${t}|maps`], m = sum[`${t}|${k}`] / n, se = Math.sqrt((sq[`${t}|${k}`] / n - m * m) / n); return `${m.toFixed(3)} (${se.toFixed(3)})`.padStart(42); }).join(''));
console.log('kept/maps'.padEnd(22) + tags.map(t => String(sum[`${t}|maps`]).padStart(42)).join(''));
console.log('free-tree dead sd'.padEnd(22) + tags.map(t => { const n = sum[`${t}|free n`]; if (!n) return '-'.padStart(42); const m = sum[`${t}|free dead`] / n; return `${m.toFixed(2)} sd ${Math.sqrt(sq[`${t}|free dead`] / n - m * m).toFixed(2)}`.padStart(42); }).join(''));
