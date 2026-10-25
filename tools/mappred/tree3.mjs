// The door tree grown the game's way over a placed layout, one step at a time, for both the predictor
// (sampler3.mjs) and the rule search (treelik.mjs). A rule:
//   level 'cell': the tree grows cell by cell; a big room is entered once, its other cells only from its own
//         'room':  entering a room puts all its cells in the tree at once
//   pick  'side': every free side off the tree alike (randomised Prim on the sides)
//         'node':  a node of the tree with a free side alike (a cell, or a room with level 'room'), then one of
//                  its free sides - the "growing tree" maze algorithm
//         'nbr':   a node alike, then one of the rooms it could open into alike, then one of the sides they share
//   p     the newest node's free sides with this chance (if it has any), q the oldest's (q first)
// Optional terms (all off by default; README "The door tree's rule" for what each did on the real maps):
//   a free side's weight, used within its node (and by side / alpha across them):
//     beta   exp(-beta x its cell's steps from where its room was entered, over the room's cells)
//     bb, b1 the same only for sides into a big room / into a 1x1 (added to beta)
//     mu     exp(-mu x the new cell's grid steps from the entrance)
//     kappa  exp(-kappa x its cell's walk from the entrance) (the walk through a 2x2 is its drawn inside's)
//     ag, ae (1 + ag x the steps since its cell joined) / exp(ae x that): sides waiting longer
//     lam    (level cell) a side inside a big room weighs lam
//   within a node: pw  the room it opens into by its count of shared sides, then a side of them by weight
//                  wc  one of the node's cells with a free side, then one of that cell's sides
//   a node's weight (pick node; replaces alike): alpha (its free sides' weight)^alpha, times
//     gamma exp(-gamma x its room steps from the entrance), delta exp(-delta x doors it has opened), eta
//     (its cells)^eta, cw its cells with a free side, f1 (the room behind the entrance) x f1
//   start  'room' / 'cell': the growth begins in a random room / at a random cell instead (seedTree, rooted)
// Written level/pick/p/q[/name=value...] (parseRule), e.g. room/node/0/0/alpha=0.5/beta=0.5/mu=0.2 (the best
// on the real maps). The entrance has one door, inward, and nothing else grows off it.
export const W = 6, N = 36;
export const NB = Array.from({ length: N }, (_, t) => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < W) o.push(t + W); if (z > 0) o.push(t - W); return o; });
export const inwardOf = ent => ent < W ? ent + W : ent >= N - W ? ent - W : ent % W === 0 ? ent + 1 : ent - 1;
/** 'room/node/0.15/0.1/beta=0.5' -> { level, pick, p, q, beta } */
export function parseRule(s) {
  const [level, pick, p, q, ...rest] = s.split('/'); const r = { name: s, level, pick, p: +p, q: +q };
  for (const kv of rest) { const [k, v] = kv.split('='); r[k] = isNaN(+v) ? v : +v; }
  return r;
}

/** A fresh tree over owner (cell -> room), rooms ({cells}), from the entrance cell: its first door taken. */
export function startTree(owner, rooms, ent) {
  const st = { owner, rooms, inT: new Uint8Array(N), parent: new Int32Array(N).fill(-1), depth: new Int32Array(N), entered: new Uint8Array(rooms.length), rentry: new Int32Array(rooms.length).fill(-1), kids: new Int32Array(N), rdepth: new Int32Array(rooms.length), rdist: new Int8Array(N), born: new Int32Array(N), nodes: [], head: 0, w: 1, done: false };
  st.inT[ent] = 1; st.entered[owner[ent]] = 1; st.rentry[owner[ent]] = ent; st.ent = ent;
  return st;
}
/** The tree's first step: the entrance's door inward; or (start=room / start=cell) the growth begins in a
 *  random room / at a random cell, the entrance kept out of it and joined inward at the end (rooted()). */
export function seedTree(st, rule) {
  if (!rule.start) { addCell(st, rule, st.ent, inwardOf(st.ent)); return; }
  const entR = st.owner[st.ent]; let c0;
  if (rule.start === 'cell') { do c0 = Math.floor(st.rnd() * N); while (st.owner[c0] === entR); }
  else { let R0; do R0 = Math.floor(st.rnd() * st.rooms.length); while (R0 === entR); const cs = st.rooms[R0].cells; c0 = cs[Math.floor(st.rnd() * cs.length)]; }
  st.root = c0; addCell(st, rule, -1, c0);
}
/** Put cell v in the tree off u (and, at room level, the rest of v's room off it inside). */
export function addCell(st, rule, u, v) {
  const { owner, inT, parent, depth } = st;
  inT[v] = 1; parent[v] = u; depth[v] = u < 0 ? 0 : depth[u] + 1; st.born[v] = st.nodes.length;
  if (!st.entered[owner[v]]) { st.entered[owner[v]] = 1; st.rentry[owner[v]] = v; st.rdepth[owner[v]] = u < 0 ? 0 : st.rdepth[owner[u]] + 1;
    // each of the room's cells' steps from v over the room's own cells (the grid's, not the tree's)
    const R = owner[v], cs = st.rooms[R].cells; if (cs.length > 1) { st.rdist[v] = 0; const qq = [v], seen = [v]; for (let i = 0; i < qq.length; i++) for (const y of NB[qq[i]]) if (owner[y] === R && !seen.includes(y)) { seen.push(y); st.rdist[y] = st.rdist[qq[i]] + 1; qq.push(y); } } }
  // doors opened so far off each node (room, or cell at level cell)
  if (u >= 0 && (owner[u] !== owner[v] || rule.level !== 'room')) st.kids[rule.level === 'room' ? owner[u] : u]++;
  if (rule.level === 'room') {
    // the room's other cells joined inside by a random tree grown from v (a random free side each time)
    const R = owner[v], inR = [v];
    for (;;) { const fr = []; for (const a of inR) for (const b of NB[a]) if (owner[b] === R && !inT[b]) fr.push([a, b]); if (!fr.length) break;
      const [a, b] = fr[Math.floor(st.rnd() * fr.length)]; inT[b] = 1; parent[b] = a; depth[b] = depth[a] + 1; st.born[b] = st.nodes.length; inR.push(b); }
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
/** a free side's weight under the rule (1 with none of the side terms) */
function sideW(st, rule, u, v) {
  // lam: a side inside a big room (level cell) weighs this much
  if (rule.lam && st.owner[u] === st.owner[v]) return rule.lam;
  if (!rule.beta && !rule.bb && !rule.b1 && !rule.mu && !rule.kappa && !rule.ag && !rule.ae) return 1;
  // ag / ae: a side waiting longer (steps since its cell joined) weighs (1 + ag * that) / exp(ae * that)
  const age = st.nodes.length - st.born[u], aw = (rule.ag ? 1 + rule.ag * age : 1) * (rule.ae ? Math.exp(rule.ae * age) : 1);
  // bb / b1: beta only for sides into a big room / a 1x1
  const b = (rule.beta || 0) + (st.rooms[st.owner[v]].cells.length > 1 ? rule.bb || 0 : rule.b1 || 0);
  // mu: exp(-mu * the new cell's grid steps from the entrance); kappa: exp(-kappa * the side's cell's walk from it)
  const m = rule.mu ? rule.mu * (Math.abs(v % W - st.ent % W) + Math.abs(((v / W) | 0) - ((st.ent / W) | 0))) : 0;
  return aw * Math.exp(-b * st.rdist[u] - m - (rule.kappa ? rule.kappa * st.depth[u] : 0));
}
/** a node's weight for the pick (node: 1, side: its sides' weight), times the optional terms (header):
 *  alpha: (its free sides' weight)^alpha in place of node/side; gamma: exp(-gamma * its room steps from the
 *  entrance); delta: exp(-delta * doors it has opened); eta: (its room's cells)^eta */
const hasNodeW = rule => rule.alpha !== undefined || rule.gamma || rule.delta || rule.eta || rule.cw || rule.f1;
function nodeW(st, rule, nd, sw) {
  const room = rule.level === 'room' ? nd : st.owner[nd];
  let w = rule.alpha !== undefined ? Math.pow(sw, rule.alpha) : rule.pick === 'side' ? sw : 1;
  // cw: times its cells with a free side (a tree cell with one alike, then its room)
  if (rule.cw) { const cells = rule.level === 'room' ? st.rooms[nd].cells : [nd]; let n = 0; for (const u of cells) { if (u === st.ent) continue; if (NB[u].some(v => !st.inT[v] && (st.owner[v] === st.owner[u] || !st.entered[st.owner[v]]))) n++; } w *= n; }
  if (rule.gamma) w *= Math.exp(-rule.gamma * st.rdepth[room]);
  if (rule.delta) w *= Math.exp(-rule.delta * st.kids[nd]);
  if (rule.eta) w *= Math.pow(st.rooms[room].cells.length, rule.eta);
  // f1: the first node (the room behind the entrance) weighs this much more
  if (rule.f1 && nd === st.nodes[0]) w *= rule.f1;
  return w;
}
/**
 * One step: the game's chance of each free side; allowed(u, v) says which the map permits (null: all). The
 * tree's weight times the allowed share; a side drawn among them by the game's chances. Returns false when
 * nothing is left to grow (done) or nothing allowed (st.w = 0).
 */
export function growStep(st, rule, allowed) {
  const nodes = st.nodes; const per = new Map(); let total = 0;
  for (const nd of nodes) { const s = sidesOf(st, rule, nd); if (s.length) { const ws = s.map(([u, v]) => sideW(st, rule, u, v)); let sw = 0; for (const x of ws) sw += x; per.set(nd, { s, ws, sw }); total += sw; } }
  if (!per.size) { st.done = true; return false; }
  while (st.head < nodes.length && !per.has(nodes[st.head])) st.head++;
  const old = nodes[st.head], last = nodes[nodes.length - 1];
  const q = rule.q || 0, p = rule.p || 0, nNodes = per.size;
  const nw = hasNodeW(rule); let NZ = 0; if (nw) for (const [nd, o] of per) { o.nw = nodeW(st, rule, nd, rule.pw ? o.s.length : o.sw); NZ += o.nw; }
  const cand = [], ps = []; let C = 0;
  for (const [nd, { s, ws, sw, nw: nwt }] of per) {
    // within the node: each side by its weight, or (nbr) each room it opens into alike, then its sides
    let inNode;
    if (rule.pick === 'nbr') { const cnt = new Map(); s.forEach(([, v], i) => cnt.set(st.owner[v], (cnt.get(st.owner[v]) || 0) + ws[i])); inNode = (i) => ws[i] / cnt.size / cnt.get(st.owner[s[i][1]]); }
    else if (rule.pw) {
      // pw: the room it opens into by its shared sides' count (as with no weights), the side among them by weight
      const cnt = new Map(), wt = new Map(); s.forEach(([, v], i) => { const R = st.owner[v]; cnt.set(R, (cnt.get(R) || 0) + 1); wt.set(R, (wt.get(R) || 0) + ws[i]); });
      inNode = (i) => { const R = st.owner[s[i][1]]; return cnt.get(R) / s.length * ws[i] / wt.get(R); };
    } else if (rule.wc) {
      // wc: a cell of the node with free sides alike (by their weight with beta etc.), then one of its sides alike
      const cw = new Map(), cn = new Map(); s.forEach(([u], i) => { cw.set(u, (cw.get(u) || 0) + ws[i]); cn.set(u, (cn.get(u) || 0) + 1); });
      let cz = 0; for (const [u, x] of cw) cz += x / cn.get(u);
      inNode = (i) => { const u = s[i][0]; return cw.get(u) / cn.get(u) / cz / cn.get(u); };
    } else inNode = (i) => ws[i] / sw;
    // the chance the game picks this side: oldest with q, newest with p, else by the pick rule
    const rest = 1 - q, hasLast = per.has(last), restN = hasLast ? 1 - p : 1;
    for (let i = 0; i < s.length; i++) {
      const e = s[i];
      const wi = inNode(i), base = nw ? wi * nwt / NZ : rule.pick === 'side' ? ws[i] / total : wi / nNodes;
      const pr = (nd === old ? q * wi : 0) + rest * ((hasLast && nd === last ? p * wi : 0) + restN * base);
      if (st.onStep) (st.all ||= []).push([e, pr, nd]);
      if (allowed && !allowed(st, e[0], e[1])) continue; cand.push(e); ps.push(pr); C += pr;
    }
  }
  if (C <= 0) { st.w = 0; st.all = null; return false; }
  st.w *= C; let r = st.rnd() * C, k = 0; while (r > ps[k] && k < ps.length - 1) r -= ps[k++];
  // onStep(st, every free side as [side, chance, node], the one taken): for diagnostics
  if (st.onStep) { st.onStep(st, st.all, cand[k]); st.all = null; }
  addCell(st, rule, cand[k][0], cand[k][1]);
  return true;
}
export function cloneTree(st, w) {
  return { ...st, inT: Uint8Array.from(st.inT), parent: Int32Array.from(st.parent), depth: Int32Array.from(st.depth), entered: Uint8Array.from(st.entered), rentry: Int32Array.from(st.rentry), kids: Int32Array.from(st.kids), rdepth: Int32Array.from(st.rdepth), rdist: Int8Array.from(st.rdist), born: Int32Array.from(st.born), nodes: [...st.nodes], w };
}

/**
 * At level room the growth never looks inside a room, so each 2x2's inside (a tree on its 4 cells, grown from
 * where it was entered: v joined to both its neighbours 3/8 + 3/8, a path round from v 1/8 + 1/8) can be summed
 * over at the end instead of drawn: every combination of the entered 2x2s' insides as { parent, depth, p }.
 * Level cell grows the insides itself: just the tree's own.
 */
export function insideVariants(st, rule) {
  const own = [{ parent: st.parent, depth: st.depth, p: 1 }];
  if (rule.level !== 'room') return own;
  const { owner, rooms } = st; let out = [{ set: [], p: 1 }];
  for (let R = 0; R < rooms.length; R++) {
    const cs = rooms[R].cells; if (cs.length !== 4 || !st.entered[R]) continue;
    const xs = cs.map(c => c % W), zs = cs.map(c => (c / W) | 0); if (Math.max(...xs) - Math.min(...xs) !== 1 || Math.max(...zs) - Math.min(...zs) !== 1) continue;
    const v = st.rentry[R]; const [a, b] = NB[v].filter(y => owner[y] === R); const d = cs.find(c => c !== v && c !== a && c !== b);
    const opts = [[[a, v], [b, v], [d, a], 3 / 8], [[a, v], [b, v], [d, b], 3 / 8], [[a, v], [d, a], [b, d], 1 / 8], [[b, v], [d, b], [a, d], 1 / 8]];
    const nx = []; for (const o of out) for (const op of opts) nx.push({ set: [...o.set, op.slice(0, 3)], p: o.p * op[3] }); out = nx;
  }
  if (out.length === 1) return own;
  return out.map(({ set, p }) => {
    const parent = Int32Array.from(st.parent); for (const g of set) for (const [c, pa] of g) parent[c] = pa;
    const depth = new Int32Array(N).fill(-1); depth[st.root ?? st.ent] = 0;
    const dep = c => { if (depth[c] >= 0) return depth[c]; const r = dep(parent[c]) + 1; depth[c] = r; return r; };
    for (let c = 0; c < N; c++) if (parent[c] >= 0 || c === (st.root ?? st.ent)) dep(c);
    return { parent, depth, p };
  });
}
/** The finished tree's cells rooted at the entrance, over its 2x2 insides (insideVariants): [{ parent, depth, p }] */
export function rooted(st, rule) {
  const vs = insideVariants(st, rule);
  if (st.root === undefined) return vs;
  const inward = inwardOf(st.ent);
  return vs.map(({ parent: pa, p }) => {
    const adj = Array.from({ length: N }, () => []);
    for (let c = 0; c < N; c++) if (pa[c] >= 0) { adj[c].push(pa[c]); adj[pa[c]].push(c); }
    adj[st.ent].push(inward); adj[inward].push(st.ent);
    const parent = new Int32Array(N).fill(-1), depth = new Int32Array(N).fill(-1); depth[st.ent] = 0; const q = [st.ent];
    for (let i = 0; i < q.length; i++) for (const y of adj[q[i]]) if (depth[y] < 0) { depth[y] = depth[q[i]] + 1; parent[y] = q[i]; q.push(y); }
    return { parent, depth, p };
  });
}
