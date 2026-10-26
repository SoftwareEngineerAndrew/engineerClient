// Scores saved per-snapshot predictions of the Kotlin predictor on the three things the Map Prediction is read for -
// which tile is the blood room, which dead ends are puzzles, which tiles are big rooms (all on the tiles not yet
// seen) - per stage (rooms known) and pooled, each candidate PAIRED against a reference on the same snapshots, with
// standard errors clustered by map. Made to settle whether different predictors are best for different targets (the
// game-process sampler against the classic one).
//
//   node targets.mjs [options] --set NAME key=value ... [--set NAME2 key=value ...]
//
// A --set is one file of snapshots (export-snaps.mjs's snaps.txt) with the Kotlin answers to it (MapSnapshotsCheck's
// <snaps>.out, run as the in-game worker runs it, ADS_MAP_MODEL=classic for the classic path). Its keys:
//   snaps=FILE     the snaps.txt the answers are for (the rebuilt snapshots must print as its lines, or it stops)
//   set=test|train|fresh   which maps the snapshots were made from (default test): test/train are split() of LAYOUTS
//                  (the held-out file), fresh the maps in LAYOUTS_ALL that are in neither
//   puz=5 | 2-4    only maps with that many puzzles (default all)     offset=k nmaps=n   a slice of that pool
//   slicefirst=1   slice before the puzzle filter                     stages=2,4,6,9,12 (default)
//   any other key=FILE   a candidate: its name and its .out (the first is the reference, --ref NAME to change)
// Options: --layouts FILE      the held-out layouts (env LAYOUTS)      --layouts-all FILE   all layouts (env LAYOUTS_ALL; fresh)
//          --ref NAME  --thr 0.7 (a tile is "called big" from this chance)  --floor 1e-4  --clip 1e-3  --json FILE
//          --ties share|index   a tie in a top-K shares the places (default) or the lower tile goes first (evalcore score()'s hit)
//          --only blood,puzzles,big,all   --no-baseline (drop the count-aware uniform row)   --no-all (no pooling of the sets)
//   e.g. node targets.mjs --layouts layouts-snap.jsonl --layouts-all layouts.jsonl --ref classic \
//          --set test5 set=test puz=5 snaps=run/test5/snaps.txt game=run/test5/snaps.game.txt.out classic=w5/kt-test5/snaps.classic.txt.out
//
// What is scored (tiles not yet seen only; every candidate on the same snapshots; the predictors get the number of
// puzzles but, as the recordings have no tab list, not their names or order - the same for every candidate):
//   all-class  mean log chance of the true class per tile, floored at --floor (score-kt's and pairs.mjs's ll/tile)
//   BLOOD (snapshots where blood is not yet seen; the blood room is one 1x1)
//     log chance at blood's true tile with p(B) normalised over the unseen tiles (p floored at --floor); top-1 / top-3:
//     blood's tile among the 1 / 3 tiles of highest p(B); per-tile binary log loss of B vs not-B on those snapshots
//   PUZZLES: per-tile binary log loss of class P vs not (every snapshot); and, per snapshot with r >= 1 unseen puzzles,
//     the share of the true puzzle tiles among the r unseen tiles of highest p(P) (precision at r)
//   BIG ROOMS: per-tile binary log loss of class n (a tile of a multi-tile room) vs not; the tiles called big (p(n) >=
//     --thr): how many a snapshot, how many right, the share right, and the share of the unseen big tiles called (recall)
// Binary losses clip the chance to [--clip, 1 - --clip]. Ties in a top-K share the places equally. A tile or snapshot
// with no prediction counts as 1/9 a class (reported). "Count-aware uniform" gives p(B) = 1/u, p(P) = r/u,
// p(n) = (unseen big tiles)/u over the u unseen tiles, the numbers of each kind a prediction has to beat.
//
// Estimates are ratios of sums over snapshots (a metric's tiles, snapshots or called tiles). The s.e. of a candidate's
// difference from the reference is the linearised ratio estimator over the maps (a map's snapshots are one cluster, so
// the pooled s.e. allows for the five stages of one map being alike), times sqrt(G / (G - 1)) for G maps. Every
// difference is signed so that + means the candidate is better (a loss: lower); it counts only if it is over 2 s.e. (*).
// The last table sets the three targets side by side: the pooled difference of each target's main metric in s.e.
// (z), and whether the candidate's advantage differs between two targets (the difference of their z, with its own
// clustered s.e. from the same maps).
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';

const die = m => { console.error('targets.mjs: ' + m); process.exit(1); };
const NC = 9;
const KIND = { NORMAL: 'NORMAL', ENTRANCE: 'ENTRANCE', PUZZLE: 'PUZZLE', TRAP: 'TRAP', CHAMPION: 'YELLOW', BLOOD: 'BLOOD', FAIRY: 'FAIRY', RARE: 'RARE' };
const EK = { none: 1, n: 2, w: 3, b: 4, same: 5 };
const SET_KEYS = new Set(['snaps', 'set', 'puz', 'offset', 'nmaps', 'slicefirst', 'stages']);

// ---- command line
const opt = { ref: null, json: null, thr: 0.7, floor: 1e-4, clip: 1e-3, layouts: process.env.LAYOUTS || null, layoutsAll: process.env.LAYOUTS_ALL || null,
  only: ['all', 'blood', 'puzzles', 'big'], baseline: true, pool: true, ties: 'share' };
const setSpecs = [];
{
  const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i++) {
    const s = a[i];
    if (s === '--set') setSpecs.push({ name: a[++i], kv: [] });
    else if (s === '--ref') opt.ref = a[++i]; else if (s === '--json') opt.json = a[++i]; else if (s === '--thr') opt.thr = +a[++i];
    else if (s === '--floor') opt.floor = +a[++i]; else if (s === '--clip') opt.clip = +a[++i];
    else if (s === '--ties') opt.ties = a[++i]; else if (s === '--layouts') opt.layouts = a[++i]; else if (s === '--layouts-all') opt.layoutsAll = a[++i];
    else if (s === '--only') opt.only = a[++i].split(','); else if (s === '--no-baseline') opt.baseline = false; else if (s === '--no-all') opt.pool = false;
    else if (s === '-h' || s === '--help') { console.log(readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n')); process.exit(0); }
    else if (s.startsWith('--')) die('unknown option ' + s);
    else if (!setSpecs.length) die(`${s}: a candidate belongs to a --set`);
    else setSpecs[setSpecs.length - 1].kv.push(s);
  }
  if (!setSpecs.length) die('no --set (--help)');
}

// ---- reading
const readLines = f => { if (!existsSync(f)) die(`${f}: no such file`); return readFileSync(f, 'utf8').split('\n').filter(Boolean); };
const median = a => { const b = [...a].sort((x, y) => x - y); return b[b.length >> 1]; };
/** export-snaps.mjs's line for a snapshot (what MapSnapshotsCheck was given) */
function snapLine(s) {
  const rooms = [...s.obs.rooms.values()].map(r => KIND[r.type] + ':' + r.tiles.map(t => t.z * 6 + t.x).join(','));
  const edges = [];
  for (const [id, k] of s.obs.edge) { if (k === 'unk') continue; const [x, z] = id.slice(1).split(',').map(Number); const a = z * 6 + x, b = id[0] === 'h' ? a + 1 : a + 6; edges.push(`${a},${b},${EK[k]}`); }
  return `6 6 ${s.obs.puzzles} ${s.L.floor[1]}|${rooms.join(';')}|${edges.join(';')}`;
}
let lib = null;  // evalcore, common, sim: loaded once, and only when a layouts file is wanted
async function needLib() {
  if (!lib) {
    if (!opt.layouts) die('--layouts (or env LAYOUTS) is needed to rebuild the snapshots\' true classes');
    process.env.LAYOUTS = opt.layouts;
    const [ec, cm, sm] = await Promise.all(['evalcore', 'common', 'sim'].map(m => import(new URL(`./${m}.mjs`, import.meta.url).href)));
    const want = ['n', 'o', 'P', 'T', 'Y', 'B', 'F', 'R', 'x'];
    if (want.some((c, i) => sm.CLS[c] !== i)) die('sim.mjs CLASSES are not n o P T Y B F R x');
    lib = { ...ec, complete: cm.complete };
  }
  return lib;
}
/** the snapshots as export-snaps / the ev5 scripts made them for a --set's selection, checked line by line against its snaps.txt */
async function buildTruth(S) {
  const { split, snapshots, complete } = await needLib();
  const np = L => L.rooms.filter(r => r.type === 'PUZZLE').length;
  const { train, test } = split();
  let pool;
  if (S.set === 'train') pool = train; else if (S.set === 'test') pool = test;
  else if (S.set === 'fresh') {
    if (!opt.layoutsAll) die('--layouts-all (or env LAYOUTS_ALL) is needed for set=fresh');
    const ids = new Set([...train, ...test].map(L => L.id)); pool = complete(opt.layoutsAll).filter(L => !ids.has(L.id));
  } else die(`${S.name}: set=${S.set}?`);
  const [pa, pb] = !S.puz ? [-1, 99] : S.puz.includes('-') ? S.puz.split('-').map(Number) : [+S.puz, +S.puz];
  if (S.slicefirst) pool = pool.slice(S.offset, S.offset + S.nmaps);
  else if (S.puz) pool = pool.filter(L => np(L) >= pa && np(L) <= pb);
  const maps = S.slicefirst ? pool : pool.slice(S.offset, S.offset + S.nmaps);
  let snaps = snapshots(maps, 99, 1, S.stages);
  if (S.slicefirst && S.puz) snaps = snaps.filter(s => s.obs.puzzles >= pa && s.obs.puzzles <= pb);
  const lines = readLines(S.snapsFile);
  const where = `set=${S.set} puz=${S.puz || 'any'} offset=${S.offset} nmaps=${S.nmaps === 1e9 ? 'all' : S.nmaps} stages=${S.stages} layouts=${opt.layouts}`;
  if (lines.length !== snaps.length) die(`${S.snapsFile} has ${lines.length} snapshots, the layouts give ${snaps.length} (${where})`);
  snaps.forEach((s, i) => { if (snapLine(s) !== lines[i]) die(`snapshot ${i} is not line ${i + 1} of ${S.snapsFile} (${where}): the maps must be chosen as the snapshots were exported`); });
  const metaFile = join(dirname(S.snapsFile), 'snaps.meta.json');
  if (basename(S.snapsFile) === 'snaps.txt' && existsSync(metaFile)) {
    const meta = JSON.parse(readFileSync(metaFile, 'utf8'));
    if (meta.length !== snaps.length || meta.some((m, i) => m.id !== snaps[i].L.id || m.k !== snaps[i].k || m.puz !== snaps[i].obs.puzzles)) die(`${metaFile} does not match the rebuilt snapshots`);
  }
  return snaps.map(s => ({ id: s.L.id, k: s.k, t: s.A.unknown.map(([x, z]) => z * 6 + x), y: s.Y }));
}
/** a Kotlin .out against the rebuilt snapshots: per snapshot the 9 class chances of every unseen tile (n o P T Y B F R and the rest as x) */
function readOut(file, truth) {
  const lines = readLines(file);
  if (lines.length > truth.length) die(`${file} has ${lines.length} lines for ${truth.length} snapshots`);
  const rows = new Map(), gc = {}, samples = [], ms = [];
  lines.forEach((l, i) => {
    const T = truth[i], [lvl, probsS, , info] = l.split('|'), P = new Float64Array(T.y.length * NC).fill(1 / NC); let missing = 0;
    if (lvl === 'none') missing = T.y.length;
    else {
      const per = probsS.split(';').map(x => x ? x.split(',').map(Number) : null);
      const unseen = new Set(T.t);  // an answer gives chances to the unseen tiles only: any other tile means it is not this snapshot's
      per.forEach((p, t) => { if (p && !unseen.has(t)) die(`${file} line ${i + 1}: chances for tile ${t}, which snapshot ${i} shows: the answers are not for these snapshots`); });
      T.t.forEach((t, j) => {
        const p = per[t]; if (!p) { missing++; return; }
        if (p.length !== 8) die(`${file} line ${i + 1}: ${p.length} chances for tile ${t}`);
        let sum = 0; for (let q = 0; q < 8; q++) { P[j * NC + q] = p[q]; sum += p[q]; }
        P[j * NC + 8] = Math.max(0, 1 - sum);
      });
    }
    if (info) { const [m, s, g] = info.split(','); gc[g] = (gc[g] || 0) + 1; ms.push(+m); samples.push(+s); }
    rows.set(i, { P, missing });
  });
  const notes = `${lines.length} snapshots` + (ms.length ? `: ${Object.entries(gc).map(([g, n]) => `${n} ${g === 'g' ? 'game' : g === 'c' ? 'classic' : g}`).join(' / ')}, samples median ${median(samples)}, ms mean ${(ms.reduce((a, b) => a + b, 0) / ms.length).toFixed(0)}` : '');
  return { rows, notes };
}

// ---- the sets
const sets = [];
for (const sp of setSpecs) {
  const S = { name: sp.name, snapsFile: null, set: 'test', puz: '', offset: 0, nmaps: 1e9, slicefirst: false, stages: [2, 4, 6, 9, 12], cands: [] };
  for (const kv of sp.kv) {
    const e = kv.indexOf('='); if (e < 1) die(`${kv}: expected key=value`);
    const k = kv.slice(0, e), v = kv.slice(e + 1);
    if (!SET_KEYS.has(k)) { if (S.cands.some(c => c.name === k)) die(`${sp.name}: candidate ${k} twice`); S.cands.push({ name: k, file: v }); continue; }
    if (k === 'snaps') S.snapsFile = v; else if (k === 'set') S.set = v; else if (k === 'puz') S.puz = v; else if (k === 'offset') S.offset = +v; else if (k === 'nmaps') S.nmaps = +v;
    else if (k === 'slicefirst') S.slicefirst = !!+v; else if (k === 'stages') S.stages = v.split(',').map(Number);
  }
  if (!S.snapsFile) die(`${sp.name}: snaps=FILE`);
  if (S.cands.length < 2) die(`${sp.name}: at least two candidates`);
  S.truth = await buildTruth(S);
  for (const c of S.cands) Object.assign(c, readOut(c.file, S.truth));
  const names = S.cands.map(c => c.name).join();
  if (sets.length && sets[0].cands.map(c => c.name).join() !== names) die(`every --set needs the same candidates (${sets[0].cands.map(c => c.name)} against ${names})`);
  sets.push(S);
}
const candNames = sets[0].cands.map(c => c.name);
const refName = opt.ref || candNames[0];
if (!candNames.includes(refName)) die(`--ref ${refName}: not a candidate`);

// ---- per snapshot statistics: metric -> [numerator, denominator]
const C = { n: 0, o: 1, P: 2, T: 3, Y: 4, B: 5, F: 6, R: 7, x: 8 };
const clip = p => Math.min(Math.max(p, opt.clip), 1 - opt.clip);
/** the expected number of true tiles among the K of highest score (ties share the places equally) */
function topCredit(s, tr, K) {
  const n = s.length; if (K >= n) return tr.filter(Boolean).length;
  if (opt.ties === 'index') return s.map((_, j) => j).sort((a, b) => s[b] - s[a]).slice(0, K).filter(j => tr[j]).length;  // evalcore score()'s: the lower tile first
  const v = [...s].sort((a, b) => b - a)[K - 1];
  let above = 0, aboveTrue = 0, tie = 0, tieTrue = 0;
  for (let j = 0; j < n; j++) if (s[j] > v) { above++; if (tr[j]) aboveTrue++; } else if (s[j] === v) { tie++; if (tr[j]) tieTrue++; }
  return aboveTrue + (K - above) * (tieTrue / tie);
}
function statsOf(sn, P) {
  const u = sn.y.length, y = sn.y, S = {};
  const col = q => Array.from({ length: u }, (_, j) => P[j * NC + q]);
  let ll = 0; for (let j = 0; j < u; j++) ll += Math.log(Math.max(P[j * NC + y[j]], opt.floor));
  S.ll = [ll, u];
  const bin = q => { let s = 0; for (let j = 0; j < u; j++) { const p = clip(P[j * NC + q]); s += y[j] === q ? Math.log(p) : Math.log(1 - p); } return [s, u]; };
  const pB = col(C.B), bj = y.indexOf(C.B);
  if (bj >= 0) {
    let Z = 0; for (const p of pB) Z += Math.max(p, opt.floor);
    S.b_lq = [Math.log(Math.max(pB[bj], opt.floor) / Z), 1];
    const isB = y.map(v => v === C.B);
    S.b_top1 = [topCredit(pB, isB, 1), 1]; S.b_top3 = [topCredit(pB, isB, 3), 1];
    S.b_bin = bin(C.B);
  }
  S.p_bin = bin(C.P);
  const r = y.filter(v => v === C.P).length;
  if (r) S.p_prec = [topCredit(col(C.P), y.map(v => v === C.P), r) / r, 1];
  S.n_bin = bin(C.n);
  let called = 0, right = 0; const tn = y.filter(v => v === C.n).length;
  for (let j = 0; j < u; j++) if (P[j * NC + C.n] >= opt.thr) { called++; if (y[j] === C.n) right++; }
  S.n_called = [called, 1]; S.n_right = [right, 1]; S.n_acc = [right, called]; S.n_recall = [right, tn];
  return S;
}
// per set: the snapshots every candidate has, the count-aware uniform baseline, every candidate's statistics
for (const S of sets) {
  const common = [...S.cands[0].rows.keys()].filter(i => S.cands.every(c => c.rows.has(i)));
  for (const c of S.cands) if (c.rows.size > common.length) console.error(`${S.name}: ${c.name} has ${c.rows.size} snapshots, scoring the ${common.length} every candidate has`);
  S.snaps = common.map(i => ({ i, ...S.truth[i], cl: S.name + ':' + S.truth[i].id }));
  if (opt.baseline) {
    const rows = new Map();
    for (const sn of S.snaps) {
      const u = sn.y.length, P = new Float64Array(u * NC), pB = sn.y.includes(C.B) ? 1 / u : 0;
      const pP = sn.y.filter(y => y === C.P).length / u, pn = sn.y.filter(y => y === C.n).length / u, rest = Math.max(0, 1 - pB - pP - pn) / 6;
      for (let j = 0; j < u; j++) for (let q = 0; q < NC; q++) P[j * NC + q] = q === C.B ? pB : q === C.P ? pP : q === C.n ? pn : rest;
      rows.set(sn.i, { P, missing: 0 });
    }
    S.base = { name: 'uniform', rows };
  }
  for (const c of [...S.cands, ...(S.base ? [S.base] : [])]) {
    c.st = S.snaps.map(sn => statsOf(sn, c.rows.get(sn.i).P));
    c.missing = S.snaps.reduce((a, sn) => a + c.rows.get(sn.i).missing, 0);
    c.missingSnaps = S.snaps.filter(sn => c.rows.get(sn.i).missing === sn.y.length).length;
  }
}
// the pooled "all sets" group: the sets' snapshots one after the other
const POOL = { name: 'all sets', snaps: sets.flatMap(S => S.snaps), cands: candNames.map(n => ({ name: n, notes: `${sets.reduce((a, S) => a + S.snaps.length, 0)} snapshots of the sets together`, st: sets.flatMap(S => S.cands.find(c => c.name === n).st) })) };
if (opt.baseline) POOL.base = { name: 'uniform', st: sets.flatMap(S => S.base.st) };
const groupsOf = D => [...[...new Set(D.snaps.map(s => s.k))].sort((a, b) => a - b).map(k => ({ key: String(k), k, sel: s => s.k === k })), { key: 'pooled', sel: () => true }];

// ---- estimates, paired differences, clustered s.e.
// every metric is a "higher is better" quantity; the binary losses are shown as losses (shown = -mean log chance)
const METRICS = {
  ll:       { short: 'all-class ll', title: 'all-class log chance of the true class, per tile (score-kt / pairs.mjs ll/tile)', show: 1, dec: 4 },
  b_lq:     { short: 'log chance at blood', title: 'log chance at blood\'s true tile, p(B) normalised over the unseen tiles', show: 1, dec: 3 },
  b_top1:   { title: 'blood\'s tile is the top-1 by p(B) (%)', show: 100, dec: 1 },
  b_top3:   { title: 'blood\'s tile is in the top-3 by p(B) (%)', show: 100, dec: 1 },
  b_bin:    { title: 'per-tile binary log loss, blood vs not (lower is better)', show: -1, dec: 4 },
  p_bin:    { short: 'puzzle binary loss', title: 'per-tile binary log loss, puzzle vs not (lower is better)', show: -1, dec: 4 },
  p_prec:   { title: 'puzzle precision at r: true puzzle tiles among the r unseen tiles of highest p(P) (%)', show: 100, dec: 1 },
  n_bin:    { short: 'big binary loss', title: 'per-tile binary log loss, big-room tile vs not (lower is better)', show: -1, dec: 4 },
  n_called: { title: `tiles called big (p >= ${opt.thr}) a snapshot`, show: 1, dec: 2, neutral: true },
  n_right:  { title: 'of them right, a snapshot', show: 1, dec: 2 },
  n_acc:    { title: 'share of the tiles called big that are big (%)', show: 100, dec: 1 },
  n_recall: { title: 'share of the unseen big tiles called big (%)', show: 100, dec: 1 },
};
const TARGETS = [
  { key: 'all', title: 'ALL CLASSES (validation against score-kt / pairs.mjs)', metrics: ['ll'], primary: 'll' },
  { key: 'blood', title: 'BLOOD (snapshots where blood is not yet seen)', metrics: ['b_lq', 'b_top1', 'b_top3', 'b_bin'], primary: 'b_lq' },
  { key: 'puzzles', title: 'PUZZLES', metrics: ['p_bin', 'p_prec'], primary: 'p_bin' },
  { key: 'big', title: 'BIG ROOMS', metrics: ['n_bin', 'n_acc', 'n_right', 'n_called', 'n_recall'], primary: 'n_bin' },
];
/** per cluster (map) [N, D] sums of a candidate's metric over a group's snapshots, and their totals */
function sums(c, m, D, sel) {
  const per = new Map(); let N = 0, Dn = 0, n = 0;
  D.snaps.forEach((s, j) => { if (!sel(s)) return; const v = c.st[j][m]; if (!v) return; n++; N += v[0]; Dn += v[1]; const e = per.get(s.cl) || [0, 0]; e[0] += v[0]; e[1] += v[1]; per.set(s.cl, e); });
  return { per, N, D: Dn, n, m: N / Dn };
}
const finish = infl => { const G = infl.size; let q = 0; for (const x of infl.values()) q += x * x; return G > 1 ? Math.sqrt(G / (G - 1) * q) : NaN; };
function estimate(c, m, D, sel) {
  const a = sums(c, m, D, sel);
  const infl = new Map([...a.per].map(([k, [N, Dd]]) => [k, (N - a.m * Dd) / a.D]));
  return { value: a.m, n: a.n, clusters: a.per.size, se: finish(infl) };
}
/** candidate minus reference, paired: the difference, its s.e. and each map's influence on it */
function paired(c, ref, m, D, sel) {
  const a = sums(c, m, D, sel), b = sums(ref, m, D, sel), infl = new Map();
  for (const k of new Set([...a.per.keys(), ...b.per.keys()])) { const [N1, D1] = a.per.get(k) || [0, 0], [N0, D0] = b.per.get(k) || [0, 0]; infl.set(k, (N1 - a.m * D1) / a.D - (N0 - b.m * D0) / b.D); }
  return { diff: a.m - b.m, se: finish(infl), infl };
}
const results = {};  // group name -> { metric -> { stage|pooled -> { cand -> { value, se, n, clusters, diff, diffSe, z } } }, summary }
function analyse(D) {
  const cands = [...D.cands, ...(D.base ? [D.base] : [])], ref = D.cands.find(c => c.name === refName), groups = groupsOf(D), R = { groups: groups.map(g => g.key), metrics: {}, infl: {} };
  for (const T of TARGETS) for (const m of T.metrics) {
    R.metrics[m] = {};
    for (const g of groups) {
      R.metrics[m][g.key] = {};
      for (const c of cands) {
        const e = estimate(c, m, D, g.sel), r = R.metrics[m][g.key][c.name] = { value: e.value, se: e.se, n: e.n, clusters: e.clusters };
        if (c !== ref && c !== D.base) { const p = paired(c, ref, m, D, g.sel); r.diff = p.diff; r.diffSe = p.se; r.z = p.diff / p.se; if (g.key === 'pooled') (R.infl[c.name] ||= {})[m] = p.infl; }
      }
    }
  }
  return R;
}
for (const S of sets) results[S.name] = analyse(S);
if (opt.pool && sets.length > 1) results[POOL.name] = analyse(POOL);

// ---- printing
const W0 = 14, W = 17;
const sgn = (v, dec) => (v >= 0 ? '+' : '-') + Math.abs(v).toFixed(dec);
const star = (d, se) => d !== 0 && (se === 0 || Math.abs(d) > 2 * se) ? '*' : ' ';
const pad = (s, w) => String(s).padStart(w);
const gname = k => k === 'pooled' ? 'pooled' : k + ' rooms';
const mark = (q) => !Number.isFinite(q.z) || Math.abs(q.diff) <= 2 * q.diffSe ? '.' : q.diff > 0 ? '+' : '-';
const scaleOf = M => Math.abs(M.show) === 100 ? 100 : 1;

function printGroup(D, R, header) {
  const groups = groupsOf(D), ref = D.cands.find(c => c.name === refName), cands = [ref, ...D.cands.filter(c => c !== ref), ...(D.base ? [D.base] : [])];
  console.log(`\n${'#'.repeat(100)}\n# ${header}\n${'#'.repeat(100)}`);
  console.log(`${D.snaps.length} snapshots of ${new Set(D.snaps.map(s => s.cl)).size} maps, stages ${groups.filter(g => g.k).map(g => g.k).join(' ')}; reference ${refName}; a tile is called big from p >= ${opt.thr}; floor ${opt.floor}, clip ${opt.clip}`);
  for (const c of D.cands) console.log(`  ${c.name.padEnd(12)} ${c.notes || ''}${c.missing === undefined ? '' : `  [${c.missing} tiles with no prediction${c.missingSnaps ? `, ${c.missingSnaps} snapshots with none` : ''}]`}`);
  console.log('  differences: candidate minus reference, + = the candidate is better (a loss: lower); value±s.e. clustered by map; * = more than 2 s.e.\n');
  console.log('data'.padEnd(W0 + 4) + groups.map(g => pad(gname(g.key), 11)).join(''));
  const dataRow = (label, f) => console.log(label.padEnd(W0 + 4) + groups.map(g => pad(f(D.snaps.filter(g.sel)), 11)).join(''));
  dataRow('snapshots', a => a.length);
  dataRow('unseen tiles', a => a.reduce((x, s) => x + s.y.length, 0));
  dataRow('blood unseen', a => a.filter(s => s.y.includes(C.B)).length);
  dataRow('puzzle tiles', a => a.reduce((x, s) => x + s.y.filter(y => y === C.P).length, 0));
  dataRow('snaps w/ puzzle', a => a.filter(s => s.y.includes(C.P)).length);
  dataRow('big tiles', a => a.reduce((x, s) => x + s.y.filter(y => y === C.n).length, 0));
  for (const T of TARGETS) {
    if (!opt.only.includes(T.key)) continue;
    console.log(`\n=== ${T.title}`);
    for (const m of T.metrics) {
      const M = METRICS[m];
      console.log(`\n-- ${M.title}${m === T.primary && T.key !== 'all' ? '   [main]' : ''}`);
      console.log(''.padEnd(W0) + groups.map(g => pad(gname(g.key), W)).join(''));
      for (const c of cands) {
        const base = c === D.base;
        if (base && !['b_lq', 'b_top1', 'b_top3', 'b_bin', 'p_bin', 'p_prec', 'n_bin'].includes(m)) continue;
        console.log((base ? '(' + c.name + ')' : c.name).padEnd(W0) + groups.map(g => { const r = R.metrics[m][g.key][c.name]; return pad(Number.isFinite(r.value) ? (M.show * r.value).toFixed(M.dec) : '-', W); }).join(''));
        if (!base && c !== ref) console.log(('  vs ' + refName).padEnd(W0) + groups.map(g => {
          const r = R.metrics[m][g.key][c.name]; if (!Number.isFinite(r.diff)) return pad('-', W);
          const sc = scaleOf(M);
          return pad(`${sgn(sc * r.diff, M.dec)}±${(sc * r.diffSe).toFixed(M.dec)}${star(r.diff, r.diffSe)}`, W);
        }).join(''));
      }
    }
  }
}
const SUMMARY = [['blood', 'b_lq'], ['blood', 'b_top1'], ['blood', 'b_top3'], ['blood', 'b_bin'], ['puzzles', 'p_bin'], ['puzzles', 'p_prec'], ['big', 'n_bin'], ['big', 'n_acc'], ['big', 'n_right'], ['all', 'll']];
const verdict = r => !Number.isFinite(r.z) || Math.abs(r.diff) <= 2 * r.diffSe ? 0 : r.diff > 0 ? 1 : -1;
const VWORD = ['worse', 'tie', 'better'];
const decisions = {};
function printSummary(D, R, header) {
  const groups = groupsOf(D), stages = groups.filter(g => g.k).map(g => g.k);
  decisions[header] = {};
  console.log(`\n=== SUMMARY ${header}: candidate minus ${refName}; + = candidate better; * = more than 2 s.e.; stage marks (${stages.join(' ')}): + better / - worse / . within 2 s.e.`);
  for (const c of D.cands) {
    if (c.name === refName) continue;
    console.log(`\n${c.name} against ${refName}`);
    console.log('  target   metric'.padEnd(22) + 'pooled'.padStart(18) + '  z'.padStart(7) + '   by stage');
    decisions[header][c.name] = {};
    for (const [tk, m] of SUMMARY) {
      if (!opt.only.includes(tk)) continue;
      const M = METRICS[m], r = R.metrics[m].pooled[c.name], sc = scaleOf(M);
      const marks = stages.map(k => mark(R.metrics[m][String(k)][c.name]));
      console.log(`  ${tk.padEnd(8)} ${m.padEnd(10)}${(sgn(sc * r.diff, M.dec) + '±' + (sc * r.diffSe).toFixed(M.dec) + star(r.diff, r.diffSe)).padStart(18)}${r.z.toFixed(1).padStart(7)}   ${marks.join(' ')}`);
    }
    // the three targets side by side on their main metric, and whether the candidate's advantage differs between two targets
    const main = TARGETS.filter(T => T.key !== 'all' && opt.only.includes(T.key));
    if (main.length >= 2) {
      const row = main.map(T => { const r = R.metrics[T.primary].pooled[c.name]; decisions[header][c.name][T.key] = { metric: T.primary, diff: r.diff, se: r.diffSe, z: r.z, verdict: VWORD[verdict(r) + 1] }; return { T, r }; });
      console.log(`  main metric of each target (pooled): ` + row.map(({ T, r }) => `${T.key} (${METRICS[T.primary].short}) z ${r.z.toFixed(1)} ${VWORD[verdict(r) + 1]}`).join('; '));
      decisions[header][c.name].contrasts = [];
      for (let a = 0; a < row.length; a++) for (let b = a + 1; b < row.length; b++) {
        const A = row[a], B = row[b], ia = R.infl[c.name][A.T.primary], ib = R.infl[c.name][B.T.primary], infl = new Map();
        for (const k of new Set([...ia.keys(), ...ib.keys()])) infl.set(k, (ia.get(k) || 0) / A.r.diffSe - (ib.get(k) || 0) / B.r.diffSe);
        const d = A.r.z - B.r.z, se = finish(infl);
        decisions[header][c.name].contrasts.push({ a: A.T.key, b: B.T.key, diff: d, se, z: d / se });
        console.log(`  ${A.T.key} vs ${B.T.key}: the advantage in s.e. differs by ${sgn(d, 2)}±${se.toFixed(2)}${star(d, se)} (z ${A.r.z.toFixed(1)} against ${B.r.z.toFixed(1)}; each target's s.e. taken as fixed)`);
      }
      const better = row.filter(({ r }) => verdict(r) > 0).map(x => x.T.key), worse = row.filter(({ r }) => verdict(r) < 0).map(x => x.T.key);
      const pick = better.length && worse.length ? `DIFFERENT predictors are best: ${c.name} on ${better.join(', ')}, ${refName} on ${worse.join(', ')}`
        : better.length ? `${c.name} is best or tied everywhere (better on ${better.join(', ')}, tied elsewhere)` : worse.length ? `${refName} is best or tied everywhere (better on ${worse.join(', ')}, tied elsewhere)` : 'tied on every target';
      console.log(`  => ${pick}`);
      decisions[header][c.name].pick = pick;
    }
  }
}
for (const S of sets) printGroup(S, results[S.name], `${S.name}: ${S.set}${S.puz ? ' maps with ' + S.puz + ' puzzles' : ''}`);
if (results[POOL.name]) printGroup(POOL, results[POOL.name], `ALL SETS (${sets.map(S => S.name).join(' + ')}), pooled over the sets' snapshots`);
console.log('\n' + '='.repeat(100));
for (const S of sets) printSummary(S, results[S.name], S.name);
if (results[POOL.name]) printSummary(POOL, results[POOL.name], POOL.name);

if (opt.json) {
  const strip = R => ({ groups: R.groups, metrics: Object.fromEntries(Object.entries(R.metrics).map(([m, g]) => [m, { show: METRICS[m].show, groups: g }])) });
  writeFileSync(opt.json, JSON.stringify({ reference: refName, thr: opt.thr, floor: opt.floor, clip: opt.clip, decisions,
    sets: Object.fromEntries(Object.entries(results).map(([k, R]) => [k, strip(R)])) }, null, 1));
  console.log('\nwrote ' + opt.json);
}
