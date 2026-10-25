// Held-out F7/M7 snapshots: per-tile log-likelihood (all classes), the big-room tile log loss, and how often the
// big rooms the prediction is sure of (>= 70%) are right.   LAYOUTS=... node evalbig.mjs nTest N variant...
const T0 = './';
const { split, snapshots } = await import(T0 + 'evalcore.mjs');
const { sample: sample2, combosFor, OUT } = await import(T0 + 'sampler2.mjs');
const { sample: sample3 } = await import(T0 + 'sampler3.mjs');
// variants with game: true run sampler3 (the game's own steps); ZMIX=file (placelik MIXRATE) for its mix weights
const zmix = process.env.ZMIX ? JSON.parse((await import('node:fs')).readFileSync(process.env.ZMIX, 'utf8')) : {};
// PARTICLES=n: sampler3's batch size
// BATCHES=n: sampler3 goes on until n batches' worth of weight too (a rule's own np= / batches= win)
const sample = (obs, c, o) => o.game ? sample3(obs, c, { ...o, zmix, particles: process.env.PARTICLES ? +process.env.PARTICLES : undefined, batches: process.env.BATCHES ? +process.env.BATCHES : undefined }) : sample2(obs, c, o);
const { makeRng } = await import(T0 + 'mlp.mjs');
const { CLS } = await import(T0 + 'sim.mjs');
const { readFileSync } = await import('node:fs');
const [nTest = '150', N = '300', ...variants] = process.argv.slice(2);
const tables = JSON.parse(readFileSync(process.env.TABLES || 'tables-current.json', 'utf8'));
const { train, test } = split();
const combos = combosFor(train);
let snaps;
if (process.env.REAL) {
  // REAL=<timelines.jsonl>: the order rooms showed on the map in real runs (tools/mappred tl.mjs), held-out maps only
  const { loadTimelines } = await import(T0 + 'tl.mjs');
  const { observe, CLS: C2, classOf } = await import(T0 + 'sim.mjs');
  const { analyse } = await import(T0 + 'features.mjs');
  const pool = process.env.TLALL ? [...train, ...test] : test;  // TLALL=1: every timeline with a complete layout (train ones too)
  const held = new Map(pool.map(L => [L.id, L])); const byKey = new Map();
  const keyOf = L => L.rooms.map(r => r.name + r.tiles.map(t => t.x + '' + t.z).join('')).sort().join('|');
  for (const L of pool) byKey.set(keyOf(L), L);
  snaps = [];
  for (const t of loadTimelines(process.env.REAL)) {
    if (t.start === null) continue;
    const L = held.get(t.id) || byKey.get(keyOf(t)); if (!L) continue;
    // the timeline's rooms by their tiles onto the held-out layout's rooms
    const tileRoom = (i) => { const r = t.rooms[i]; const tt = r.tiles[0]; return L.grid[tt.z]?.[tt.x]; };
    const order = [...t.seen.entries()].sort((a, b) => a[1] - b[1]).map(([i]) => tileRoom(i)).filter(i => i !== undefined && i >= 0);
    for (const k of [2, 4, 6, 9, 12]) {
      if (k >= order.length) continue;
      const known = new Set(order.slice(0, k));
      const obs = observe(L, known); const A = analyse(obs);
      snaps.push({ L, k, known, obs, A, Y: A.unknown.map(([x, z]) => C2[classOf(L.rooms[L.grid[z][x]])]) });
    }
  }
} else snaps = snapshots((process.env.SET === 'train' ? train : test).slice(+(process.env.OFFSET || 0), +(process.env.OFFSET || 0) + +nTest), 99, 1, (process.env.STAGES || '2,4,6,9,12').split(',').map(Number));  // STAGES=9,12: only those  // SET=train: training maps (to fit a correction on)  // OFFSET=k: the maps from the k-th on
console.log(`${snaps.length} snapshots from ${process.env.REAL ? 'real discovery order on' : Math.min(+nTest, test.length)} held-out maps (${train.length} train)`);
const VAR = { old: { fourL: true }, lfix: {}, ...Object.fromEntries([0.15, 0.25, 0.35].map(b => [`lfix+bunch${b}`, { bunch: b }])) };
Object.assign(VAR, { prim: { grow: 0 }, 'oldest0.3': { grow: 0, oldest: 0.3 }, 'oldest0.5': { grow: 0, oldest: 0.5 }, 'oldest0.3+res5': { grow: 0, oldest: 0.3, reserve: 5 }, 'res5': { reserve: 5 }, 'res3': { reserve: 3 }, 'res7': { reserve: 7 } });
const PLACE = [0.23, 0.14, 0.60, -0.066];
Object.assign(VAR, { 'res5+detour0.1': { reserve: 5, detour: 0.1 }, 'res5+detour0.25': { reserve: 5, detour: 0.25 }, 'res5+detour0.4': { reserve: 5, detour: 0.4 } });
for (const pn of [0, 0.15, 0.3]) for (const qn of [0, 0.1]) VAR[`game-p${pn}-q${qn}`] = { game: true, rule: { level: 'cell', pick: 'side', p: pn, q: qn } };
// game-<level>-<pick>-<p>-<q>: sampler3 with that door-tree rule (tree3.mjs)
for (const v of process.argv.slice(4)) { const m = /^game-(cell|room)-(side|node)-([\d.]+)-([\d.]+)$/.exec(v); if (m) VAR[v] = { game: true, rule: { level: m[1], pick: m[2], p: +m[3], q: +m[4] } }; }
// game:<rule>: sampler3 with any tree3.mjs rule as level/pick/p/q[/name=value...], e.g. game:room/node/0/0/alpha=0.5/beta=0.5
const { parseRule } = await import(T0 + 'tree3.mjs');
for (const v of process.argv.slice(4)) if (v.startsWith('game:')) VAR[v] = { game: true, rule: parseRule(v.slice(5)) };
Object.assign(VAR, { 'lfix-noBW': { noBW: true }, 'res5-noBW': { reserve: 5, noBW: true }, 'try1-noBW': { tries: 1, noBW: true }, 'try2-noBW': { tries: 2, noBW: true }, 'rnd+res5-noBW': { order: 'rnd', reserve: 5, noBW: true }, 'rnd+try2-noBW': { order: 'rnd', tries: 2, noBW: true }, 'rnd+try2': { order: 'rnd', tries: 2 } });
Object.assign(VAR, { try1: { tries: 1 }, try2: { tries: 2 }, try4: { tries: 4 }, 'try1+res5': { tries: 1, reserve: 5 }, 'try1+res3': { tries: 1, reserve: 3 } });
Object.assign(VAR, { rnd: { order: 'rnd' }, 'rnd+res5': { order: 'rnd', reserve: 5 }, special: { special: true, maxTries: 1e6 }, 'special+rnd': { special: true, order: 'rnd', maxTries: 1e6 }, 'special+res2': { special: true, reserve: 2, maxTries: 1e6 } });
Object.assign(VAR, { place: { place: PLACE }, 'place-noBW': { place: PLACE, noBW: true }, 'place+res3': { place: PLACE, reserve: 3 } });
const dumpRows = [];  // DUMPP=<file>: per tile [rooms known, true class, class chances...]
const perSnap = [];   // each variant's log-likelihood per snapshot, for the paired differences at the end
for (const v of variants.length ? variants : Object.keys(VAR)) {
  const rnd = makeRng(+(process.env.SEED || 5));
  const by = {}; const mine = []; perSnap.push([v, mine]);
  for (const s of snaps) {
    const r = sample(s.obs, combos['7'], { samples: +N, rnd, tables: VAR[v].noBW ? { ...tables, BW: new Array(36).fill(1) } : tables, ...VAR[v] });
    const W = s.obs.W, byTile = new Map(r.unknown.map((t, i) => [t, r.probs[i]]));
    const st = by[s.k] ||= { ll: 0, bigLL: 0, tiles: 0, sure: 0, sureRight: 0, snaps: 0, empty: 0 };
    st.snaps++; if (!r.accepted) st.empty++; const ll0 = st.ll;
    s.A.unknown.forEach(([x, z], i) => {
      const p = byTile.get(z * W + x); const y = s.Y[i];
      const pb = p ? Math.min(Math.max(p[CLS.n], 1e-3), 1 - 1e-3) : 0.6;
      st.ll += Math.log(Math.max(p ? p[y] : 1 / OUT.length, 1e-4));
      st.bigLL += y === CLS.n ? Math.log(pb) : Math.log(1 - pb);
      st.tiles++;
      if (process.env.DUMPP && p) dumpRows.push([s.k, y, ...Array.from(p, v => +v.toFixed(4))]);
      if (p && p[CLS.n] >= 0.7) { st.sure++; if (y === CLS.n) st.sureRight++; }
    });
    mine.push([st.ll - ll0, s.A.unknown.length]);
  }
  console.log(`\n== ${v}`);
  console.log('known  ll/tile  big-tile ll  sure-big tiles (right)');
  let tot = { ll: 0, bigLL: 0, tiles: 0 };
  for (const [k, st] of Object.entries(by)) {
    console.log(`${String(k).padStart(4)}   ${(st.ll / st.tiles).toFixed(4)}   ${(st.bigLL / st.tiles).toFixed(4)}     ${(st.sure / st.snaps).toFixed(2)} (${(st.sureRight / Math.max(st.sure, 1) * 100).toFixed(1)}%)${st.empty ? `  ${st.empty} empty` : ''}`);
    tot.ll += st.ll; tot.bigLL += st.bigLL; tot.tiles += st.tiles;
  }
  console.log(` all   ${(tot.ll / tot.tiles).toFixed(4)}   ${(tot.bigLL / tot.tiles).toFixed(4)}`);
}
// paired against the first variant: the difference in ll/tile, with its s.e. over snapshots
if (perSnap.length > 1) { console.log(`\npaired against ${perSnap[0][0]} (ll/tile difference, s.e. over ${snaps.length} snapshots)`);
  const [, ref] = perSnap[0]; const T = ref.reduce((a, [, t]) => a + t, 0);
  for (const [v, mine] of perSnap.slice(1)) { const d = mine.map(([l, t], i) => l - ref[i][0]); const m = d.reduce((a, b) => a + b, 0) / T;
    // ratio estimator's s.e.: residuals d_i - m t_i
    const se = Math.sqrt(d.reduce((a, di, i) => a + (di - m * ref[i][1]) ** 2, 0)) / T;
    console.log(`  ${v.padEnd(40)} ${m >= 0 ? '+' : ''}${m.toFixed(4)} (s.e. ${se.toFixed(4)})`); } }
// DUMPLL=<file>: each variant's [ll, tiles] per snapshot (to pair two runs on the same maps and seed)
if (process.env.DUMPLL) (await import('node:fs')).writeFileSync(process.env.DUMPLL, JSON.stringify(Object.fromEntries(perSnap)));
if (process.env.DUMPP) (await import('node:fs')).writeFileSync(process.env.DUMPP, JSON.stringify(dumpRows));
