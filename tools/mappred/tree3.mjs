// The door tree grown the game's way over a placed layout, one step at a time, for both the predictor
// (sampler3.mjs) and the rule search (treelik.mjs). A rule:
//   level 'cell': the tree grows cell by cell; a big room is entered once, its other cells only from its own
//         'room':  entering a room puts all its cells in the tree at once
//   pick  'side': every free side off the tree alike (randomised Prim on the sides)
//         'node':  a node of the tree with a free side alike (a cell, or a room with level 'room'), then one of
//                  its free sides - the "growing tree" maze algorithm
//   p     the newest node's free sides with this chance (if it has any), q the oldest's (q first)
// The entrance has one door, inward, and nothing else grows off it.
export const W = 6, N = 36;
export const NB = Array.from({ length: N }, (_, t) => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < W) o.push(t + W); if (z > 0) o.push(t - W); return o; });
export const inwardOf = ent => ent < W ? ent + W : ent >= N - W ? ent - W : ent % W === 0 ? ent + 1 : ent - 1;

/** A fresh tree over owner (cell -> room), rooms ({cells}), from the entrance cell: its first door taken. */
export function startTree(owner, rooms, ent) {
  const st = { owner, rooms, inT: new Uint8Array(N), parent: new Int32Array(N).fill(-1), depth: new Int32Array(N), entered: new Uint8Array(rooms.length), nodes: [], head: 0, w: 1, done: false };
  st.inT[ent] = 1; st.entered[owner[ent]] = 1; st.ent = ent;
  return st;
}
/** Put cell v in the tree off u (and, at room level, the rest of v's room off it inside). */
export function addCell(st, rule, u, v) {
  const { owner, inT, parent, depth } = st;
  inT[v] = 1; parent[v] = u; depth[v] = depth[u] + 1; st.entered[owner[v]] = 1;
  if (rule.level === 'room') {
    // the room's other cells joined inside by a random tree grown from v (a random free side each time)
    const R = owner[v], inR = [v];
    for (;;) { const fr = []; for (const a of inR) for (const b of NB[a]) if (owner[b] === R && !inT[b]) fr.push([a, b]); if (!fr.length) break;
      const [a, b] = fr[Math.floor(st.rnd() * fr.length)]; inT[b] = 1; parent[b] = a; depth[b] = depth[a] + 1; inR.push(b); }
    st.nodes.push(owner[v]);
  } else st.nodes.push(v);
}
/** The game's free sides now: [u, v] pairs, u in the tree (not the entrance), v not. */
function sidesOf(st, rule, node) {
  const { owner, inT, entered, rooms } = st; const out = [];
  const cells = rule.level === 'room' ? rooms[node].cells : [node];
  for (const u of cells) { if (u === st.ent) continue; for (const v of NB[u]) if (!inT[v] && (owner[v] === owner[u] || !entered[owner[v]])) out.push([u, v]); }
  return out;
}
/**
 * One step: the game's chance of each free side; allowed(u, v) says which the map permits (null: all). The
 * tree's weight times the allowed share; a side drawn among them by the game's chances. Returns false when
 * nothing is left to grow (done) or nothing allowed (st.w = 0).
 */
export function growStep(st, rule, allowed) {
  const nodes = st.nodes; const per = new Map(); let total = 0;
  for (const nd of nodes) { const s = sidesOf(st, rule, nd); if (s.length) { per.set(nd, s); total += s.length; } }
  if (!per.size) { st.done = true; return false; }
  while (st.head < nodes.length && !per.has(nodes[st.head])) st.head++;
  const old = nodes[st.head], last = nodes[nodes.length - 1];
  const q = rule.q || 0, p = rule.p || 0, nNodes = per.size;
  const cand = [], ps = []; let C = 0;
  for (const [nd, s] of per) {
    // the chance the game picks this node's side k: oldest with q, newest with p, else by the pick rule
    const base = rule.pick === 'node' ? 1 / nNodes / s.length : 1 / total;
    const pq = nd === old ? q / s.length : 0, rest = 1 - q;
    const pn = per.has(last) ? (nd === last ? p / s.length : 0) : 0, restN = per.has(last) ? 1 - p : 1;
    const pr = pq + rest * (pn + restN * base);
    for (const [u, v] of s) { if (allowed && !allowed(st, u, v)) continue; cand.push([u, v]); ps.push(pr); C += pr; }
  }
  if (C <= 0) { st.w = 0; return false; }
  st.w *= C; let r = st.rnd() * C, k = 0; while (r > ps[k] && k < ps.length - 1) r -= ps[k++];
  addCell(st, rule, cand[k][0], cand[k][1]);
  return true;
}
export function cloneTree(st, w) {
  return { ...st, inT: Uint8Array.from(st.inT), parent: Int32Array.from(st.parent), depth: Int32Array.from(st.depth), entered: Uint8Array.from(st.entered), nodes: [...st.nodes], w };
}
