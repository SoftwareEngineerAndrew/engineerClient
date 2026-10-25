// Candidate Hypixel F7 generators, run end to end on the 6x6 grid, scored against the recorded maps on
// statistics none of them were fitted to (the README's "How the maps are made" has what they showed).
//   LAYOUTS=layouts.jsonl node generator.mjs [variant...]     (MAPS=n real maps, QUIET=1 scores only)
//   ACCEPT=<variant> ATTEMPTS=1 TRIES=n node generator.mjs     how often one attempt passes, per entrance cell
//   DUMP=file.json: the generated layouts, for placefit.mjs GEN=file.json
//
// A variant is kind:tree:order[:res<k>[:blood]]:
//   kind  pgrow: as grow, but the wither path (9 or 10 steps, each off the cell just added) grown first, blood
//               its end: ruled out (blood 55% on an edge, 9% in a corner, real 86%, 33%; the tree too deep)
//         grow: the tree grown cell by cell, a new free cell starting a big room with chance c * shapes left /
//               free cells (order field c<k>[p][d][u]: p shape and placement picked together, d the room's
//               cells grown at once in a chain, u its cells joined by a random spanning tree inside, f the room
//               behind the entrance finished at once, then a door from it to every free neighbouring cell)
//         cells: a door tree over all 36 single cells first (the entrance one door), then the big rooms merged
//               onto it by their top-left corner, only where the tree already joins their cells
//         rooms: the big rooms first, then the tree over the rooms, each big room's cells joined by a random
//               tree inside it
//   tree  prim (a random door off the tree), bfs<q> (with chance q off the oldest room with a free neighbour,
//         else prim), grow<p> (the newest room with chance p), dfs, ust (every spanning tree alike), kruskal
//   order rnd or big (the big rooms in random order, or biggest first)
//   res<k> k random cells kept free of big rooms while they're placed; try<k> (cells) instead: up to k passes
//         over the cells in random order, one try at each (a shape left, a placement over the cell inside the
//         map, kept if free and joined by the tree); a tree rule ending in f: the first cell gets a door to
//         every free neighbour at once
//   blood uni (any candidate alike), far, w<a> (weight tiles from the entrance ^ a)
// Every variant: blood a 1x1 dead end 9 or 10 tree steps out with a 1x1 four steps back (the fairy), and the
// map tried again unless 4-7 1x1 dead ends besides entrance and blood (2-5 puzzles with trap and yellow).
//   u:mode=grow|post/q=/p=/f=/c=/k=/m=/r= one generator with the open questions as parameters: the rooms made
//         while the tree grows (grow, chance c * shapes left / free cells) or after it (post, the cells in the
//         growth order shuffled by m, each tried with chance c); k tries at a random placement over the cell
//         (0 = any that fits); q, p oldest-/newest-first; f the first room's doors to all its neighbours; r held cells;
//         i=1 the entrance's door inward (every real map's is)
// Closest so far: grow:bfs0.1:c3f (nothing held back); cells:bfs0.2:rnd:res4 before it.
const T0 = './';
const { complete } = await import(T0 + 'common.mjs');
const { prep } = await import(T0 + 'sim.mjs');
const { makeRng } = await import(T0 + 'mlp.mjs');
const { walkFrom } = await import(T0 + 'walk.mjs');
const W = 6, N = 36;
const rnd = makeRng(+(process.env.SEED || 31));
const ri = n => Math.floor(rnd() * n);
const nb = t => { const x = t % W, z = (t / W) | 0, o = []; if (x + 1 < W) o.push(t + 1); if (x > 0) o.push(t - 1); if (z + 1 < W) o.push(t + W); if (z > 0) o.push(t - W); return o; };
const NB = Array.from({ length: N }, (_, t) => nb(t));
const SHAPES = ['TwoByOne', 'ThreeByOne', 'FourByOne', 'TwoByTwo', 'L'];
const SIZE = [2, 3, 4, 4, 3];
const ORIENT = {
  TwoByOne: [[[0, 0], [1, 0]], [[0, 0], [0, 1]]],
  ThreeByOne: [[[0, 0], [1, 0], [2, 0]], [[0, 0], [0, 1], [0, 2]]],
  FourByOne: [[[0, 0], [1, 0], [2, 0], [3, 0]], [[0, 0], [0, 1], [0, 2], [0, 3]]],
  TwoByTwo: [[[0, 0], [1, 0], [0, 1], [1, 1]]],
  L: [[[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 0], [1, 1]], [[0, 0], [0, 1], [1, 1]]],
};
const key = (a, b) => a < b ? a * N + b : b * N + a;

// ---------------------------------------------------------------- spanning trees over a graph
/** A spanning tree over nodes 0..n-1 with adjacency adj (arrays), rooted at r with exactly one child: parent[]. */
function tree(rule, n, adj, r) {
  // a trailing f: the first cell gets a door to every free neighbour at once
  const flood = rule.endsWith('f'); if (flood) rule = rule.slice(0, -1);
  const parent = new Int32Array(n).fill(-2); parent[r] = -1;
  const first = adj[r][ri(adj[r].length)]; parent[first] = r;
  const inT = [first]; let count = 2;
  if (flood) for (const w of adj[first]) if (parent[w] === -2) { parent[w] = first; inT.push(w); count++; }
  if (rule === 'ust' || rule === 'kruskal') {
    // over the graph without r, then r hung on 'first'
    if (rule === 'kruskal') {
      const uf = Int32Array.from({ length: n }, (_, i) => i); const find = a => { while (uf[a] !== a) { uf[a] = uf[uf[a]]; a = uf[a]; } return a; };
      const edges = []; for (let a = 0; a < n; a++) if (a !== r) for (const b of adj[a]) if (b > a && b !== r) edges.push([a, b]);
      for (let i = edges.length - 1; i > 0; i--) { const j = ri(i + 1); [edges[i], edges[j]] = [edges[j], edges[i]]; }
      const tadj = Array.from({ length: n }, () => []);
      for (const [a, b] of edges) { const fa = find(a), fb = find(b); if (fa !== fb) { uf[fa] = fb; tadj[a].push(b); tadj[b].push(a); } }
      const q = [first]; const seen = new Uint8Array(n); seen[first] = 1; seen[r] = 1;
      while (q.length) { const u = q.pop(); for (const v of tadj[u]) if (!seen[v]) { seen[v] = 1; parent[v] = u; q.push(v); } }
      return parent;
    }
    // Wilson's algorithm on the graph without r, rooted at first
    const inTree = new Uint8Array(n); inTree[first] = 1; inTree[r] = 1; const next = new Int32Array(n).fill(-1);
    for (let s = 0; s < n; s++) {
      if (inTree[s]) continue;
      let u = s; while (!inTree[u]) { const opts = adj[u].filter(v => v !== r); next[u] = opts[ri(opts.length)]; u = next[u]; }
      u = s; while (!inTree[u]) { inTree[u] = 1; u = next[u]; }
    }
    // next[] points towards the root along the tree: that is the parent
    for (let s = 0; s < n; s++) if (s !== r && s !== first) parent[s] = next[s];
    return parent;
  }
  if (rule.startsWith('bfs')) {
    // with chance q a door off the oldest room that still has a free neighbour, else a random door off the tree
    const q = rule === 'bfs' ? 1 : +rule.slice(3); let head = 0;
    while (count < n) {
      let u = -1, v = -1;
      if (rnd() < q) {
        while (head < inT.length) { const a = inT[head]; const fr = adj[a].filter(w => parent[w] === -2); if (fr.length) { u = a; v = fr[ri(fr.length)]; break; } head++; }
      }
      if (u < 0) { let c = 0; for (const a of inT) for (const b of adj[a]) if (parent[b] === -2) { c++; if (rnd() * c < 1) { u = a; v = b; } } }
      if (u < 0) return null;
      parent[v] = u; inT.push(v); count++;
    }
    return parent;
  }
  const p = rule.startsWith('grow') ? +rule.slice(4) : rule === 'dfs' ? 1 : 0;
  const stack = [first];
  while (count < n) {
    let u = -1, v = -1;
    if (p > 0 && rnd() < p) {
      while (stack.length) { const top = stack[stack.length - 1]; const fr = adj[top].filter(w => parent[w] === -2); if (fr.length) { u = top; v = fr[ri(fr.length)]; break; } if (rule === 'dfs') stack.pop(); else break; }
    }
    if (u < 0) { let c = 0; for (const a of inT) for (const b of adj[a]) if (parent[b] === -2) { c++; if (rnd() * c < 1) { u = a; v = b; } } }
    if (u < 0) return null;
    parent[v] = u; inT.push(v); stack.push(v); count++;
  }
  return parent;
}

const shuffleArr = a => { for (let i = a.length - 1; i > 0; i--) { const j = ri(i + 1); [a[i], a[j]] = [a[j], a[i]]; } return a; };

// ---------------------------------------------------------------- big rooms
function placements(shape, free) {
  const out = [];
  for (const o of ORIENT[shape]) for (let t = 0; t < N; t++) { const x0 = t % W, z0 = (t / W) | 0; const ts = []; let ok = true;
    for (const [dx, dz] of o) { const x = x0 + dx, z = z0 + dz; if (x >= W || z >= W || !free[z * W + x]) { ok = false; break; } ts.push(z * W + x); }
    if (ok) out.push(ts); }
  return out;
}
const internal = ts => { const e = []; for (let i = 0; i < ts.length; i++) for (let j = i + 1; j < ts.length; j++) if (NB[ts[i]].includes(ts[j])) e.push(key(ts[i], ts[j])); return e; };

/** One generated map as rooms over cells: owner[], room shapes, the cell tree (parent, rooted at the entrance). */
function generate(variant, ent, shapes) {
  const [kind, rule, order, res, bloodRule = 'uni'] = variant.split(':');
  const reserve = res?.startsWith('res') ? +res.slice(3) : 0;
  for (let attempt = 0; attempt < (+process.env.ATTEMPTS || 400); attempt++) {
    const owner = new Int32Array(N).fill(-1); owner[ent] = 0; const shape = ['OneByOne']; let next = 1;
    let parent;
    // MIX=random7: the big rooms not the real map's but 7 drawn at random each attempt (at most two of a shape)
    if (process.env.MIX === 'random7') { const c = [0, 0, 0, 0, 0]; shapes = []; for (let k = 0; k < 7; k++) { const okS = [0, 1, 2, 3, 4].filter(i => c[i] < 2); const i = okS[ri(okS.length)]; c[i]++; shapes.push(SHAPES[i]); } }
    const todo = [...shapes]; for (let i = todo.length - 1; i > 0; i--) { const j = ri(i + 1); [todo[i], todo[j]] = [todo[j], todo[i]]; }
    if (order === 'big') todo.sort((a, b) => ORIENT[b][0].length - ORIENT[a][0].length);
    let ok = true, fixed = null;
    if (kind === 'u') {
      // the unified generator: u:mode=grow|post/q=/p=/f=0|1/c=/k=/m=/r= (see the header)
      const P = Object.fromEntries(rule.split('/').map(kv => kv.split('=')).filter(([k]) => k !== 'w').map(([k, v]) => [k, k === 'mode' ? v : +v]));
      // b=<tiles>: the shapes not given but picked as the rooms start, at most two of one, each keeping the budget
      // (7 rooms making exactly b tiles) reachable; al: the start chance c * rooms left^al / free cells
      const budget = P.b || 0, al = P.al ?? 1;
      // w (comma list, in SHAPES order) and s: a shape's weight in the budget's picks, s times that for a second copy
      const SW = rule.match(/(?:^|\/)w=([\d.,]+)/)?.[1].split(',').map(Number) || [1, 1, 1, 1, 1], S2 = P.s ?? 1;
      const pickW = list => { const ws = list.map(nm => { const i = SHAPES.indexOf(nm); return SW[i] * (cnt[i] ? S2 : 1); }); let r = rnd() * ws.reduce((a, b) => a + b, 0), j = 0; while (r > ws[j] && j < ws.length - 1) r -= ws[j++]; return j; };
      const cnt = [0, 0, 0, 0, 0]; let tilesSoFar = 0;
      const feas = (roomsLeft, need) => { if (roomsLeft === 0) return need === 0; for (let i = 0; i < 5; i++) if (cnt[i] < 2 && SIZE[i] <= need) { cnt[i]++; const f = feas(roomsLeft - 1, need - SIZE[i]); cnt[i]--; if (f) return true; } return false; };
      const K = P.k || 0, held = new Set();
      // held cells: r, plus rp per puzzle of the map being matched (CUR.puzzles); re = an edge cell's weight to be one
      // (corners re too), interior cells 1
      const nHeld = Math.round((P.r || 0) + (P.rp || 0) * CUR.puzzles);
      if (nHeld) { const cs = [...Array(N).keys()].filter(t => t !== ent); const isEdge = t => { const x = t % W, z = (t / W) | 0; return x === 0 || z === 0 || x === 5 || z === 5; };
        for (let i = 0; i < nHeld && cs.length; i++) { const ws = cs.map(t => isEdge(t) ? (P.re ?? 1) : 1); let r = rnd() * ws.reduce((a, b) => a + b, 0), j = 0; while (r > ws[j] && j < ws.length - 1) r -= ws[j++]; held.add(cs[j]); cs.splice(j, 1); } }
      parent = new Int32Array(N).fill(-2); parent[ent] = -1;
      const grown = [ent], left = [...todo]; let te = null;
      const freeOk = (t, v) => t !== ent && owner[t] === -1 && !held.has(t) && (P.mode === 'post' || t === v || parent[t] === -2);
      const joined = ts => !te || internal(ts).filter(k => te.has(k)).length === ts.length - 1;
      const placed = () => cnt.reduce((a, b) => a + b, 0);
      if (budget) { left.length = 0; const refill = () => { left.length = 0; const rl = 7 - placed(); if (rl <= 0) return; for (let i = 0; i < 5; i++) if (cnt[i] < 2) { cnt[i]++; if (feas(rl - 1, budget - tilesSoFar - SIZE[i])) left.push(SHAPES[i]); cnt[i]--; } }; refill(); left.refill = refill; }
      const place = (k, ts) => { for (const t of ts) owner[t] = next; shape.push(left[k]); next++; const si = SHAPES.indexOf(left[k]); cnt[si]++; tilesSoFar += SIZE[si]; if (left.refill) left.refill(); else left.splice(k, 1); };
      const tryStart = v => {
        if (!left.length) return;
        if (K === 0) {
          // any fitting placement over the cell, a shape among those that have one
          const opts = left.map(s2 => placements(s2, Array.from({ length: N }, (_, t) => freeOk(t, v))).filter(ts => ts.includes(v) && joined(ts)));
          const can = left.map((_, i) => i).filter(i => opts[i].length); if (!can.length) return;
          const i = can[pickW(can.map(j => left[j]))]; place(i, opts[i][ri(opts[i].length)]); return;
        }
        for (let tr = 0; tr < K; tr++) {
          const sk = pickW(left); const all = placements(left[sk], Array(N).fill(true)).filter(ts => ts.includes(v)); if (!all.length) continue;
          const ts = all[ri(all.length)]; if (ts.every(t => freeOk(t, v)) && joined(ts)) { place(sk, ts); return; }
        }
      };
      const fr = u => NB[u].filter(w => parent[w] === -2 && (owner[w] === -1 || owner[w] === owner[u]));
      const add = v => { grown.push(v); if (P.mode === 'grow' && owner[v] === -1) { let free = 0; for (let t = 0; t < N; t++) if (owner[t] === -1 && parent[t] === -2) free++; if (rnd() < Math.min(1, P.c * Math.pow(Math.max(7 - placed(), 0), al) / Math.max(free, 1))) tryStart(v); } };
      // i=1: the entrance's door inward, as on every real map (else to a random neighbour, mostly along the edge)
      const inward = ent < W ? ent + W : ent >= N - W ? ent - W : ent % W === 0 ? ent + 1 : ent - 1;
      const first = P.i ? inward : NB[ent][ri(NB[ent].length)]; parent[first] = ent; add(first);
      if (P.f === 2) {
        // f=2: only the cell behind the entrance gets a door to each free neighbour (claimed cells only from their room)
        for (const w of shuffleArr(fr(first))) if (parent[w] === -2) { parent[w] = first; add(w); }
      } else if (P.f) { const r1 = owner[first]; let more = r1 >= 0; while (more) { more = false; for (const u of [...grown]) if (owner[u] === r1) for (const w of fr(u)) if (owner[w] === r1) { parent[w] = u; grown.push(w); more = true; } }
        for (const u of grown.filter(t => t !== ent && (r1 < 0 ? t === first : owner[t] === r1))) for (const w of shuffleArr(fr(u))) if (parent[w] === -2 && owner[w] === -1) { parent[w] = u; add(w); } }
      let head = 1;
      while (grown.length < N) {
        let u = -1, v = -1;
        if (P.q && rnd() < P.q) { while (head < grown.length) { const f2 = fr(grown[head]); if (f2.length) { u = grown[head]; v = f2[ri(f2.length)]; break; } head++; } }
        if (u < 0 && P.p && rnd() < P.p) { const last = grown[grown.length - 1]; const f2 = fr(last); if (f2.length) { u = last; v = f2[ri(f2.length)]; } }
        if (u < 0) { let cnt = 0; for (let i = 1; i < grown.length; i++) for (const w of fr(grown[i])) { cnt++; if (rnd() * cnt < 1) { u = grown[i]; v = w; } } }
        if (u < 0) { ok = false; break; }
        parent[v] = u; add(v);
      }
      if (ok && P.mode === 'post') {
        // the rooms after the tree: cells visited in the growth order shuffled by m (0 = as grown, large = random),
        // each free one tried with chance c, up to 3 passes
        te = new Set(); for (let t = 0; t < N; t++) if (parent[t] >= 0) te.add(key(t, parent[t]));
        const ord = grown.map((t, i) => [t, i + (P.m || 0) * N * rnd()]).sort((a, b) => a[1] - b[1]).map(x => x[0]);
        for (let pass = 0; pass < 3 && left.length; pass++) for (const v of ord) { if (!left.length) break; if (v === ent || owner[v] !== -1 || held.has(v)) continue; if (rnd() < P.c) tryStart(v); }
      }
      if (left.length || (budget && placed() !== 7)) ok = false;
    } else if (kind === 'grow' || kind === 'pgrow') {
      // the tree grown cell by cell from the entrance; a new free cell starts a big room (one of the shapes
      // left, a placement over it whose other cells are free and not grown yet) with chance c * shapes left /
      // free cells; a claimed cell is grown only from its own room's cells
      // c<k>[p][d]: p = the shape and placement picked together (shapes with more placements over the cell more
      // often), d = the room's cells grown at once, each from the one before where it can (a chain inside)
      const mm = /^c([\d.]+)([a-z]*)$/.exec(order); const c = +mm[1], byPlace = mm[2].includes('p'), chain = mm[2].includes('d'), ust = mm[2].includes('u'), flood = mm[2].includes('f'), once = mm[2].includes('a') ? 1 : mm[2].includes('b') ? 2 : mm[2].includes('e') ? 3 : 0;
      // a/b/e = 1/2/3 tries: a shape left and one of its placements over the cell inside the map, kept if it fits
      // f = the room behind the entrance finished at once, then a door from it to every free neighbouring cell
      // u = each big room's cells joined inside by a random spanning tree, a cell grown only from its parent in it
      const intPar = new Int32Array(N).fill(-1);
      const q = rule.startsWith('bfs') ? (rule === 'bfs' ? 1 : +rule.slice(3)) : 0;
      const pNew = rule.startsWith('grow') ? +rule.slice(4) : 0;
      parent = new Int32Array(N).fill(-2); parent[ent] = -1;
      const grown = [ent], left = [...todo];
      const start = v => {
        let free = 0; for (let t = 0; t < N; t++) if (owner[t] === -1 && parent[t] === -2) free++;
        if (!left.length || rnd() >= Math.min(1, c * left.length / Math.max(free, 1))) return;
        const ok2 = Array.from({ length: N }, (_, t) => t !== ent && owner[t] === -1 && (t === v || parent[t] === -2));
        if (once) {
          const all = []; left.forEach((s, k) => { for (const ts of placements(s, Array(N).fill(true))) if (ts.includes(v)) all.push([k, ts]); });
          for (let tr = 0; tr < once; tr++) {
            const sk = ri(left.length); const mine = all.filter(([k]) => k === sk); if (!mine.length) continue;
            const [, ts] = mine[ri(mine.length)]; if (!ts.every(t => ok2[t])) continue;
            const id = next; for (const t of ts) owner[t] = id; shape.push(left[sk]); next++; left.splice(sk, 1);
            return;
          }
          return;
        }
        const opts = left.map(s => placements(s, ok2).filter(ts => ts.includes(v)));
        const can = left.map((_, i) => i).filter(i => opts[i].length);
        if (!can.length) return;
        let i, ts;
        if (byPlace) { const all = []; for (const k of can) for (const p2 of opts[k]) all.push([k, p2]); [i, ts] = all[ri(all.length)]; }
        else { i = can[ri(can.length)]; ts = opts[i][ri(opts[i].length)]; }
        const id = next; for (const t of ts) owner[t] = id; shape.push(left[i]); next++; left.splice(i, 1);
        if (ust) {
          const es = internal(ts), m = ts.length - 1, trees = [];
          (function pick(j, acc) { if (acc.length === m) { const uf = new Map(ts.map(x => [x, x])); const f = a => { while (uf.get(a) !== a) a = uf.get(a); return a; }; for (const k of acc) { const a = f((k / N) | 0), b = f(k % N); if (a === b) return; uf.set(a, b); } trees.push([...acc]); return; } if (j >= es.length) return; acc.push(es[j]); pick(j + 1, acc); acc.pop(); pick(j + 1, acc); })(0, []);
          const adj = new Map(ts.map(x => [x, []])); for (const k of trees[ri(trees.length)]) { const a = (k / N) | 0, b = k % N; adj.get(a).push(b); adj.get(b).push(a); }
          const q2 = [v], seen = new Set([v]); while (q2.length) { const a = q2.shift(); for (const b of adj.get(a)) if (!seen.has(b)) { seen.add(b); intPar[b] = a; q2.push(b); } }
        }
        if (chain) { let last = v; for (;;) { const nx = ts.filter(t => parent[t] === -2 && NB[last].includes(t)); const any = nx.length ? nx : ts.filter(t => parent[t] === -2 && NB[t].some(w => owner[w] === id && parent[w] !== -2));
          if (!any.length) break; const t = any[ri(any.length)]; const from = nx.length ? last : NB[t].filter(w => owner[w] === id && parent[w] !== -2)[0]; parent[t] = from; grown.push(t); last = t; } }
      };
      const add = (v, u, noRoom) => { parent[v] = u; grown.push(v); if (owner[v] === -1 && !noRoom) start(v); };
      let bloodCell = -1;
      const fr = u => u === bloodCell ? [] : NB[u].filter(w => parent[w] === -2 && (owner[w] === -1 || owner[w] === owner[u] && (intPar[w] < 0 || intPar[w] === u)));
      add(NB[ent][ri(NB[ent].length)], ent);
      if (flood) {
        const r1 = owner[grown[1]];
        if (r1 >= 0) { let more = true; while (more) { more = false; for (const u of [...grown]) if (owner[u] === r1) for (const w of fr(u)) if (owner[w] === r1) { add(w, u, true); more = true; } } }
        const firstCells = r1 >= 0 ? grown.filter(t => owner[t] === r1) : [grown[1]];
        for (const u of firstCells) for (const w of shuffleArr(fr(u))) if (parent[w] === -2) add(w, u);
      }
      if (kind === 'pgrow') {
        // the wither path first: 9 or 10 steps, each from the cell just added (rooms can start on it), the
        // fairy (4 before the end) and blood (the end) kept 1x1; nothing grows off blood
        const G = rnd() < 0.6 ? 9 : 10; let cur = grown[1], fairyCell = -1;
        for (let k = 2; k <= G && ok; k++) {
          const keep = k === G - 4 || k === G;
          const f = fr(cur).filter(w => !keep || owner[w] === -1);
          if (!f.length) { ok = false; break; }
          const v = f[ri(f.length)]; add(v, cur, keep); cur = v;
          if (k === G - 4) fairyCell = v;
        }
        if (ok) { bloodCell = cur; fixed = [cur, fairyCell]; }
      }
      let head = 1;
      while (ok && grown.length < N) {
        let u = -1, v = -1;
        if (q && rnd() < q) { while (head < grown.length) { const f = fr(grown[head]); if (f.length) { u = grown[head]; v = f[ri(f.length)]; break; } head++; } }
        if (u < 0 && pNew && rnd() < pNew) { const last = grown[grown.length - 1]; const f = fr(last); if (f.length) { u = last; v = f[ri(f.length)]; } }
        if (u < 0) { let cnt = 0; for (let i = 1; i < grown.length; i++) for (const w of fr(grown[i])) { cnt++; if (rnd() * cnt < 1) { u = grown[i]; v = w; } } }
        if (u < 0) { ok = false; break; }
        add(v, u);
      }
      if (left.length) ok = false;
    } else if (kind === 'cells') {
      parent = tree(rule, N, NB, ent); if (!parent) continue;
      const te = new Set(); for (let t = 0; t < N; t++) if (parent[t] >= 0) te.add(key(t, parent[t]));
      const held = new Set(); if (reserve) { const cs = [...Array(N).keys()].filter(t => t !== ent); for (let i = 0; i < reserve; i++) { const j = i + ri(cs.length - i); [cs[i], cs[j]] = [cs[j], cs[i]]; held.add(cs[i]); } }
      if (res?.startsWith('try')) {
        // try<k>: passes over the cells in random order, at each free cell one try - a shape left at one of its
        // placements over the cell inside the map, kept if its cells are free and the tree joins them; at most k
        const passes = +res.slice(3) || 1, left = [...todo];
        for (let pass = 0; pass < passes && left.length; pass++) for (const v of shuffleArr([...Array(N).keys()])) {
          if (!left.length) break; if (v === ent || owner[v] !== -1) continue;
          const sk = ri(left.length); const all = placements(left[sk], Array(N).fill(true)).filter(ts => ts.includes(v));
          if (!all.length) continue; const ts = all[ri(all.length)];
          if (!ts.every(t => t !== ent && owner[t] === -1) || internal(ts).filter(k => te.has(k)).length !== ts.length - 1) continue;
          for (const t of ts) owner[t] = next; shape.push(left[sk]); next++; left.splice(sk, 1);
        }
        if (left.length) ok = false;
      } else for (const s of todo) {
        const free = Array.from({ length: N }, (_, t) => owner[t] === -1 && !held.has(t));
        const c = placements(s, free).filter(ts => internal(ts).filter(k => te.has(k)).length === ts.length - 1);
        if (!c.length) { ok = false; break; }
        for (const t of c[ri(c.length)]) owner[t] = next; shape.push(s); next++;
      }
    } else {
      const held = new Set(); if (reserve) { const cs = [...Array(N).keys()].filter(t => t !== ent); for (let i = 0; i < reserve; i++) { const j = i + ri(cs.length - i); [cs[i], cs[j]] = [cs[j], cs[i]]; held.add(cs[i]); } }
      for (const s of todo) {
        const free = Array.from({ length: N }, (_, t) => owner[t] === -1 && !held.has(t));
        const c = placements(s, free); if (!c.length) { ok = false; break; }
        for (const t of c[ri(c.length)]) owner[t] = next; shape.push(s); next++;
      }
      if (ok) {
        // inside each big room a random tree over its cells; then a tree over the rooms by the rule, its doors
        // at random between touching cells; the cell tree is both together
        for (let t = 0; t < N; t++) if (owner[t] === -1) { owner[t] = next++; shape.push('OneByOne'); }
        const R = next, radj = Array.from({ length: R }, () => new Set());
        for (let t = 0; t < N; t++) for (const u of NB[t]) if (owner[u] !== owner[t]) radj[owner[t]].add(owner[u]);
        const rp = tree(rule, R, radj.map(s => [...s]), 0); if (!rp) continue;
        const cadj = Array.from({ length: N }, () => []);
        for (let r = 1; r < R; r++) {
          const pr = rp[r]; const pairs = []; for (let t = 0; t < N; t++) if (owner[t] === r) for (const u of NB[t]) if (owner[u] === pr) pairs.push([t, u]);
          const [a, b] = pairs[ri(pairs.length)]; cadj[a].push(b); cadj[b].push(a);
        }
        for (let r = 1; r < R; r++) { const cs = []; for (let t = 0; t < N; t++) if (owner[t] === r) cs.push(t); if (cs.length < 2) continue;
          // a uniform random spanning tree of the room's own cells (enumerate: they are tiny)
          const es = internal(cs); const trees = [];
          const m = cs.length - 1; (function pick(i, acc) { if (acc.length === m) { const uf = new Map(cs.map(c => [c, c])); const f = a => { while (uf.get(a) !== a) a = uf.get(a); return a; }; let good = true; for (const k of acc) { const a = (k / N) | 0, b = k % N, fa = f(a), fb = f(b); if (fa === fb) { good = false; break; } uf.set(fa, fb); } if (good) trees.push([...acc]); return; } if (i >= es.length) return; acc.push(es[i]); pick(i + 1, acc); acc.pop(); pick(i + 1, acc); })(0, []);
          for (const k of trees[ri(trees.length)]) { const a = (k / N) | 0, b = k % N; cadj[a].push(b); cadj[b].push(a); } }
        parent = new Int32Array(N).fill(-2); parent[ent] = -1; const q = [ent];
        while (q.length) { const u = q.pop(); for (const v of cadj[u]) if (parent[v] === -2) { parent[v] = u; q.push(v); } }
      }
    }
    if (!ok) continue;
    for (let t = 0; t < N; t++) if (owner[t] === -1) { owner[t] = next++; shape.push('OneByOne'); }
    const R = next;
    // room doors from the cell tree
    const deg = new Int32Array(R), depth = new Int32Array(N).fill(-1);
    for (let t = 0; t < N; t++) if (parent[t] >= 0 && owner[parent[t]] !== owner[t]) { deg[owner[t]]++; deg[owner[parent[t]]]++; }
    depth[ent] = 0; const order2 = [ent]; for (let i = 0; i < order2.length; i++) for (let t = 0; t < N; t++) if (parent[t] === order2[i]) { depth[t] = depth[order2[i]] + 1; order2.push(t); }
    // blood: a 1x1 dead end 9 or 10 tree steps out, the fairy (a 1x1) 4 steps before it
    const cand = [];
    for (let t = 0; t < N; t++) { const r = owner[t]; if (r === 0 || shape[r] !== 'OneByOne' || deg[r] !== 1 || (depth[t] !== 9 && depth[t] !== 10)) continue;
      let f = t; for (let k = 0; k < 4; k++) f = parent[f]; if (shape[owner[f]] !== 'OneByOne' || owner[f] === 0) continue; cand.push([t, f]); }
    if (fixed) { const [b, f] = fixed; if (shape[owner[b]] !== 'OneByOne' || deg[owner[b]] !== 1 || shape[owner[f]] !== 'OneByOne') continue; cand.length = 0; cand.push(fixed); }
    if (!cand.length) continue;
    // blood among the candidates: uni (any alike), far (the farthest in tiles from the entrance), w<a> (weight tiles^a)
    const mdE = t => Math.abs(t % W - ent % W) + Math.abs(((t / W) | 0) - ((ent / W) | 0));
    let pick;
    if (bloodRule === 'far') { const mx = Math.max(...cand.map(c => mdE(c[0]))); const top = cand.filter(c => mdE(c[0]) === mx); pick = top[ri(top.length)]; }
    else if (bloodRule.startsWith('w')) { const a = +bloodRule.slice(1); const ws = cand.map(c => mdE(c[0]) ** a); let r = rnd() * ws.reduce((x, y) => x + y, 0), k = 0; while (r > ws[k] && k < ws.length - 1) r -= ws[k++]; pick = cand[k]; }
    else pick = cand[ri(cand.length)];
    const [blood, fairy] = pick;
    let dead = 0; for (let r = 1; r < R; r++) if (shape[r] === 'OneByOne' && deg[r] === 1 && r !== owner[blood]) dead++;
    // the game's dead-end range: 2-5 puzzles besides trap and yellow (8, a rare room, hardly ever); with EXACT the map
    // being matched's own count: its puzzles + trap + yellow (+ a rare on 5-puzzle maps)
    if (process.env.EXACT) { const want = CUR.puzzles + 2; if (!(dead === want || CUR.puzzles === 5 && dead === want + 1)) continue; }
    else if (dead < 4 || dead > 7) continue;
    // the path as cells, blood back to the entrance
    const pathCells = []; for (let t = blood; t >= 0; t = parent[t]) pathCells.unshift(t);
    const doors = []; for (let t = 0; t < N; t++) if (parent[t] >= 0 && owner[parent[t]] !== owner[t]) doors.push([t, parent[t]]);
    return { owner, shape, parent, blood, fairy, dead, R, deg, depth, pathCells, doors };
  }
  return null;
}

// ---------------------------------------------------------------- the same statistics on real and generated maps
const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
const doorTiles = (L, a, b) => { for (const d of L.doors) { if (d.what === '-') continue; if (d.a === a && d.b === b) return [[d.ax, d.az], [d.bx, d.bz]]; if (d.a === b && d.b === a) return [[d.bx, d.bz], [d.ax, d.az]]; } return null; };
function inRoomWalks(tiles, s, t) {
  const set = new Set(tiles.map(q => q.x + ',' + q.z)), out = [], seen = new Set();
  (function dfs(x, z, acc) { const k = x + ',' + z; acc.push([x, z]); if (x === t[0] && z === t[1]) { out.push([...acc]); acc.pop(); return; } seen.add(k);
    for (const [dx, dz] of DIRS) { const n = (x + dx) + ',' + (z + dz); if (set.has(n) && !seen.has(n)) dfs(x + dx, z + dz, acc); } seen.delete(k); acc.pop(); })(s[0], s[1], []);
  return out;
}
/** The real path as cells: the shortest walk inside rooms, around a 2x2 where that makes the 9-10 steps. */
function realPath(L) {
  const { E, path } = prep(L);
  let walks = [[[E.tiles[0].x, E.tiles[0].z]]], at = walks[0][0];
  for (let i = 0; i + 1 < path.length; i++) {
    const [out, inn] = doorTiles(L, path[i], path[i + 1]); const opts = inRoomWalks(L.rooms[path[i]].tiles, at, out);
    const next = []; for (const w of walks) for (const o of opts) next.push([...w, ...o.slice(1), inn]); walks = next; at = inn;
  }
  const ok = walks.filter(w => w.length - 1 === 9 || w.length - 1 === 10);
  return (ok.length ? ok : walks).sort((a, b) => a.length - b.length)[0].map(([x, z]) => z * W + x);
}
function realAsGen(L) {
  const owner = new Int32Array(N).fill(-1); const shape = [];
  const E = L.rooms.find(r => r.type === 'ENTRANCE');
  const idx = new Map(); idx.set(E.i, 0); shape[0] = 'OneByOne'; let k = 1;
  for (const r of L.rooms) if (r !== E) { idx.set(r.i, k); shape[k] = r.shape; k++; }
  for (const r of L.rooms) for (const t of r.tiles) owner[t.z * W + t.x] = idx.get(r.i);
  const deg = new Int32Array(k); for (const r of L.rooms) deg[idx.get(r.i)] = L.g.get(r.i).length;
  const B = L.rooms.find(r => r.type === 'BLOOD'), F = L.rooms.find(r => r.type === 'FAIRY');
  const dead = L.rooms.filter(r => r.shape === 'OneByOne' && r.type !== 'ENTRANCE' && r.type !== 'BLOOD' && L.g.get(r.i).length === 1).length;
  const wf = walkFrom(L, E.i); const depth = new Int32Array(N).fill(-1);
  for (const r of L.rooms) { const { dist, at } = wf.get(r.i); for (const t of r.tiles) depth[t.z * W + t.x] = dist + Math.abs(t.x - at[0]) + Math.abs(t.z - at[1]); }
  const doors = L.doors.filter(d => d.what !== '-' && d.a >= 0 && d.b >= 0 && d.a !== d.b).map(d => [d.az * W + d.ax, d.bz * W + d.bx]);
  return { owner, shape, blood: B.tiles[0].z * W + B.tiles[0].x, fairy: F.tiles[0].z * W + F.tiles[0].x, dead, R: k, deg, ent: E.tiles[0].z * W + E.tiles[0].x, L, pathCells: realPath(L), depth, doors };
}
function stats(M, ent) {
  const o = {}; const add = (k, v = 1) => { o[k] = (o[k] || 0) + v; };
  if (M.pathCells) {
    const pc = M.pathCells, n = pc.length - 1;
    add(`path ${n} steps`);
    const rooms = []; for (const t of pc) if (rooms[rooms.length - 1] !== M.owner[t]) rooms.push(M.owner[t]);
    add('path: big rooms on it', rooms.filter(r => M.shape[r] !== 'OneByOne').length);
    add('path: rooms on it', rooms.length);
    // 2x2s entered and left by neighbouring cells: does the path go around?
    let i = 0; while (i < pc.length) { const r = M.owner[pc[i]]; let j = i; while (j + 1 < pc.length && M.owner[pc[j + 1]] === r) j++;
      if (M.shape[r] === 'TwoByTwo') { const a = pc[i], b = pc[j]; const d = Math.abs(a % W - b % W) + Math.abs(((a / W) | 0) - ((b / W) | 0)); if (d === 1) { add('2x2 on path, neighbouring entry/exit'); if (j - i === 3) add('2x2 on path, goes around'); } }
      i = j + 1; }
  }
  const md = (a, b) => Math.abs(a % W - b % W) + Math.abs(((a / W) | 0) - ((b / W) | 0));
  const bx = M.blood % W, bz = (M.blood / W) | 0;
  add('blood: tiles from entrance', md(ent, M.blood));
  add('blood on the edge', +(bx === 0 || bz === 0 || bx === 5 || bz === 5));
  add('blood in a corner', +((bx === 0 || bx === 5) && (bz === 0 || bz === 5)));
  add('fairy: tiles from entrance', md(ent, M.fairy));
  add('fairy: tiles to blood', md(M.fairy, M.blood));
  add('fairy on the edge', +(M.fairy % W === 0 || M.fairy < W || M.fairy % W === 5 || M.fairy >= 30));
  add('dead ends (not entrance, blood)', M.dead);
  // doors by where they are: along the map's edge (both cells on the same border line), from inside to a border cell, inside
  if (M.doors) for (const [a, b] of M.doors) {
    const ax = a % W, az = (a / W) | 0, bx = b % W, bz = (b / W) | 0;
    const onB = (x, z) => x === 0 || z === 0 || x === 5 || z === 5;
    const along = (az === bz && (az === 0 || az === 5)) || (ax === bx && (ax === 0 || ax === 5));
    add(along ? 'doors: along the edge' : onB(ax, az) || onB(bx, bz) ? 'doors: inside to edge cell' : 'doors: inside');
    add(az === bz ? 'doors: east-west' : 'doors: north-south');
  }
  // where the big tiles are: tiles from the entrance, on the map's edge; big rooms touching
  { let dsum = 0, nb = 0, edge = 0; for (let t = 0; t < N; t++) { const r = M.owner[t]; if (M.shape[r] === 'OneByOne') continue; nb++; dsum += md(ent, t); const x = t % W, z = (t / W) | 0; if (x === 0 || z === 0 || x === 5 || z === 5) edge++; }
    add('big tiles: tiles from entrance', dsum / Math.max(nb, 1)); add('big tiles on the edge', edge);
    const tp = new Set(); for (let t = 0; t < N; t++) for (const u of NB[t]) { const a = M.owner[t], b = M.owner[u]; if (a !== b && M.shape[a] !== 'OneByOne' && M.shape[b] !== 'OneByOne') tp.add(Math.min(a, b) * 64 + Math.max(a, b)); }
    add('big rooms touching (pairs)', tp.size); }
  // the entrance's surroundings: the two cells beside it on its edge, and the room behind it
  { const ex = ent % W, ez = (ent / W) | 0; const along = ez === 0 || ez === 5 ? [[ex - 1, ez], [ex + 1, ez]] : [[ex, ez - 1], [ex, ez + 1]];
    for (const [x, z] of along) { if (x < 0 || z < 0 || x >= W || z >= W) continue; const r = M.owner[z * W + x];
      add(M.shape[r] !== 'OneByOne' ? 'beside entrance: big room' : M.deg[r] === 1 ? 'beside entrance: 1x1 dead end' : 'beside entrance: 1x1 with 2+ doors'); }
    const inward = ez === 0 ? ent + W : ez === 5 ? ent - W : ex === 0 ? ent + 1 : ent - 1;
    add(`behind entrance: ${M.shape[M.owner[inward]]}`); }
  add(`dead ends = ${M.dead}`);
  const one = t => M.shape[M.owner[t]] === 'OneByOne';
  let q = 0; for (let z = 0; z < 5; z++) for (let x = 0; x < 5; x++) { const t = z * W + x; if (one(t) && one(t + 1) && one(t + W) && one(t + W + 1)) q++; }
  add('2x2 blocks of 1x1s', q);
  { const SZ = { TwoByOne: 2, ThreeByOne: 3, FourByOne: 4, TwoByTwo: 4, L: 3 }; let tl = 0; const cnt = { TwoByOne: 0, ThreeByOne: 0, FourByOne: 0, TwoByTwo: 0, L: 0 };
    for (let r = 1; r < M.R; r++) if (M.shape[r] !== 'OneByOne') { tl += SZ[M.shape[r]]; cnt[M.shape[r]]++; }
    add(`big tiles total ${tl}`); add(`mix ${SHAPES.map(s2 => cnt[s2]).join('')}`); }
  if (M.depth) {
    // the walk to the tile each big room is entered by, and what share of the tiles at that walk are big
    for (let r = 1; r < M.R; r++) { if (M.shape[r] === 'OneByOne') continue; let d = 99; for (let t = 0; t < N; t++) if (M.owner[t] === r) d = Math.min(d, M.depth[t]); add(`big room entered at walk ${Math.min(d, 9)}`); }
    let mx = 0, sumD = 0, nD = 0, str = 0, far = 0;
    for (let t = 0; t < N; t++) mx = Math.max(mx, M.depth[t]);
    for (let r = 1; r < M.R; r++) if (M.shape[r] === 'OneByOne' && M.deg[r] === 1) { let t = 0; while (M.owner[t] !== r) t++; const d = M.depth[t]; sumD += d; nD++; if (d >= 11) far++; if (d > 0) str += md(ent, t) / d; add(`dead end walk ${Math.min(d, 14)}`); }
    add('tree: deepest tile (walk)', mx); add('tree: dead ends mean walk', sumD / Math.max(nD, 1)); add('tree: dead ends straightness (tiles/walk)', str / Math.max(nD, 1)); add('tree: dead ends at walk 11+', far);
    let all = 0; for (let t = 0; t < N; t++) all += M.depth[t]; add('tree: mean walk of all tiles', all / N);
  }
  if (M.pathCells) {
    const onP = new Set(M.pathCells.map(t => M.owner[t]));
    for (let z = 0; z < 5; z++) for (let x = 0; x < 5; x++) { const t = z * W + x; const ts = [t, t + 1, t + W, t + W + 1]; if (!ts.every(one)) continue;
      for (const u of ts) { const r = M.owner[u]; add(onP.has(r) ? 'in blocks: path 1x1' : M.deg[r] === 1 ? 'in blocks: dead end' : 'in blocks: other 1x1'); } }
    let pOne = 0; for (const r of onP) if (M.shape[r] === 'OneByOne') pOne++; add('path: 1x1 rooms on it', pOne);
  }
  add(`2x2 blocks of 1x1s | dead=${M.dead}`, q); add(`n | dead=${M.dead}`);
  for (let r = 1; r < M.R; r++) { const s = M.shape[r]; if (s === 'OneByOne') { add(`1x1 doors=${Math.min(M.deg[r], 4)}`); continue; } add(`${s} doors=${Math.min(M.deg[r], 4)}`); add(`${s} n`); }
  return o;
}

if (process.env.ACCEPT) {
  // one attempt per try: how often a map from each entrance cell passes, with a typical shape mix
  const shapes = ['TwoByOne', 'TwoByOne', 'ThreeByOne', 'ThreeByOne', 'FourByOne', 'TwoByTwo', 'L'];
  const v = process.env.ACCEPT, tries = +(process.env.TRIES || 400);
  console.log(`${v}: share of single attempts that make a valid map, by entrance cell`);
  const rate = new Float64Array(36);
  for (let t = 0; t < 36; t++) { const x = t % W, z = (t / W) | 0; const edge = x === 0 || z === 0 || x === 5 || z === 5, corner = (x === 0 || x === 5) && (z === 0 || z === 5); if (!edge || corner) continue;
    let ok = 0; for (let k = 0; k < tries; k++) if (generate(v, t, shapes)) ok++; rate[t] = ok / tries; }
  for (let z = 0; z < 6; z++) console.log([0, 1, 2, 3, 4, 5].map(x => rate[z * W + x] ? (rate[z * W + x] * 100).toFixed(1).padStart(6) : '     .').join(''));
  process.exit(0);
}
const Ls = complete();
const variants = process.argv.slice(2).length ? process.argv.slice(2) : ['cells:prim:rnd', 'cells:ust:rnd', 'cells:kruskal:rnd', 'cells:dfs:rnd', 'cells:grow0.3:rnd', 'rooms:prim:rnd', 'rooms:ust:rnd'];
const acc = {}, cnt = {};
const accum = (m, o) => { acc[m] ||= {}; cnt[m] = (cnt[m] || 0) + 1; for (const k in o) acc[m][k] = (acc[m][k] || 0) + o[k]; };
const fails = {}; const dumped = {};
/** The real map being matched: its puzzle count (the generator gets it as it gets the entrance). */
var CUR = { puzzles: 3 };
const maxMaps = +(process.env.MAPS || 600);
for (const L of Ls.slice(0, maxMaps)) {
  const shapes = L.rooms.filter(r => r.shape !== 'OneByOne').map(r => r.shape);
  if (shapes.length !== 7) continue;
  const R = realAsGen(L); accum('real', stats(R, R.ent));
  CUR = { puzzles: L.rooms.filter(r => r.type === 'PUZZLE').length };
  for (const v of variants) { const g = generate(v, R.ent, shapes); if (!g) { fails[v] = (fails[v] || 0) + 1; continue; } accum(v, stats(g, R.ent));
    if (process.env.DUMP) (dumped[v] ||= []).push({ ent: R.ent, owner: [...g.owner], shape: g.shape, depth: [...g.depth], parent: [...g.parent] }); }
}
for (const m in acc) for (const k of Object.keys(acc[m])) { const mm = /^2x2 blocks of 1x1s \| (dead=\d+)$/.exec(k); if (mm) acc[m][k] = acc[m][k] / (acc[m][`n | ${mm[1]}`] || 1) * cnt[m]; }
for (const m in acc) { const a = acc[m]['2x2 on path, neighbouring entry/exit'] || 0; acc[m]['2x2 on path, goes around (share)'] = a ? (acc[m]['2x2 on path, goes around'] || 0) / a * cnt[m] : 0; }
// per-shape door counts become shares of that shape
for (const m in acc) for (const k of Object.keys(acc[m])) { const mm = /^(\w+) doors=/.exec(k); if (mm && mm[1] !== '1x1') acc[m][k] = acc[m][k] / (acc[m][`${mm[1]} n`] || 1) * cnt[m]; }
const ms = Object.keys(acc);
const keys = Object.keys(acc.real).filter(k => !k.endsWith(' n') && !k.startsWith('n |') && !/dead=[0-38-9]/.test(k) && !/goes around$|neighbouring entry\/exit$/.test(k)).sort();
console.log(`${cnt.real} real maps; generated per variant: ` + variants.map(v => `${v} ${cnt[v] || 0} (${fails[v] || 0} gave up)`).join(', '));
console.log('stat'.padEnd(34) + ms.map(m => m.slice(0, 15).padStart(16)).join(''));
let score = Object.fromEntries(ms.map(m => [m, 0]));
for (const k of keys) {
  const vals = ms.map(m => (acc[m][k] || 0) / (cnt[m] || 1));
  if (!process.env.QUIET) console.log(k.padEnd(34) + vals.map(v => v.toFixed(3).padStart(16)).join(''));
  const r = vals[0]; ms.forEach((m, i) => { score[m] += (vals[i] - r) ** 2 / (Math.abs(r) / cnt.real + 1e-4); });
}
if (process.env.DUMP) (await import('node:fs')).writeFileSync(process.env.DUMP, JSON.stringify(dumped));
console.log('\nmismatch score (lower = closer to the real maps):');
for (const m of ms.slice(1)) console.log('  ' + m.padEnd(22) + ' ' + score[m].toFixed(0));
