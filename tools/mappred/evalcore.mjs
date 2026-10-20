// Shared test harness: held-out dungeons, the same simulated partial maps for every method, and the scores.
import { complete } from './common.mjs';
import { CLASSES, CLS, classOf, discoveryOrder, observe } from './sim.mjs';
import { analyse } from './features.mjs';
import { entries, subtrees } from './branch.mjs';
import { makeRng } from './mlp.mjs';

export const STAGES = [2, 3, 4, 5, 6, 8, 10, 12, 15];

export function split(seed = 7, testFrac = 0.2) {
  const rnd = makeRng(seed);
  const Ls = complete();
  // a fixed order by id, then shuffled, so the split doesn't depend on download order
  Ls.sort((a, b) => a.id < b.id ? -1 : 1);
  for (let i = Ls.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [Ls[i], Ls[j]] = [Ls[j], Ls[i]]; }
  const n = Math.floor(Ls.length * testFrac);
  return { test: Ls.slice(0, n), train: Ls.slice(n), all: Ls };
}

export function snapshots(Ls, seed = 99, reps = 1, stages = STAGES) {
  const rnd = makeRng(seed);
  const out = [];
  for (const L of Ls) for (let r = 0; r < reps; r++) {
    const order = discoveryOrder(L, rnd);
    for (const k of stages) {
      if (k >= L.rooms.length) continue;
      const known = new Set(order.slice(0, k));
      const obs = observe(L, known);
      const A = analyse(obs);
      const Y = A.unknown.map(([x, z]) => CLS[classOf(L.rooms[L.grid[z][x]])]);
      const ents = entries(obs);
      // one entry per tile (a tile has one door from the known side in a tree)
      const truth = subtrees(L, known, ents).map(t => { const e = new Float64Array(CLASSES.length + 2); for (const r of t) { e[CLS[classOf(L.rooms[r])]]++; e[CLASSES.length] += L.rooms[r].tiles.length; e[CLASSES.length + 1]++; } return e; });
      out.push({ L, k, known, obs, A, Y, ents, truth });
    }
  }
  return out;
}

/** method(s) -> { probs: [per unknown tile class probs in CLASSES order], branch: [per entry {c: class expectations, tiles}] } */
export function score(name, snaps, method) {
  const by = {};
  for (const s of snaps) {
    const out = method(s);
    const P = out.probs;
    const st = by[s.k] ??= { n: 0, ll: 0, tiles: 0, hit: {}, cnt: {}, mass: {}, spec: 0, specN: 0, bn: 0, bT: 0, bTn: 0, bY: 0, bYn: 0, bB: 0, bBn: 0, pzErr: 0, pzErr0: 0, tErr: 0, tErr0: 0, ents: 0, llT: 0, llY: 0 };
    st.n++;
    s.Y.forEach((y, i) => { st.ll += Math.log(Math.max(P[i][y], 1e-4)); st.tiles++; });
    for (const cName of ['P', 'T', 'Y', 'B', 'F']) {
      const c = CLS[cName];
      const truth = s.Y.map((y, i) => y === c ? i : -1).filter(i => i >= 0);
      if (!truth.length) continue;
      const top = s.Y.map((_, i) => i).sort((a, b) => P[b][c] - P[a][c]).slice(0, truth.length);
      st.hit[cName] = (st.hit[cName] || 0) + top.filter(i => s.Y[i] === c).length / truth.length;
      st.cnt[cName] = (st.cnt[cName] || 0) + 1;
      st.mass[cName] = (st.mass[cName] || 0) + truth.reduce((a, i) => a + P[i][c], 0) / truth.length;
    }
    const sp = i => P[i][CLS.P] + P[i][CLS.T] + P[i][CLS.Y] + P[i][CLS.R];
    const isSp = y => y === CLS.P || y === CLS.T || y === CLS.Y || y === CLS.R;
    const nSp = s.Y.filter(isSp).length;
    if (nSp) { const top = s.Y.map((_, i) => i).sort((a, b) => sp(b) - sp(a)).slice(0, nSp); st.spec += top.filter(i => isSp(s.Y[i])).length / nSp; st.specN++; }
    if (s.ents.length >= 2 && out.branch) {
      st.bn++; st.ents += s.ents.length;
      const B = out.branch;
      for (const [c, h, n] of [['T', 'bT', 'bTn'], ['Y', 'bY', 'bYn'], ['B', 'bB', 'bBn']]) {
        const tj = s.truth.findIndex(e => e[CLS[c]] > 0); if (tj < 0) continue;
        let b = 0; B.forEach((e, j) => { if (e.c[CLS[c]] > B[b].c[CLS[c]]) b = j; });
        st[h] += b === tj ? 1 : 0; st[n]++;
        if (c !== 'B') { const tot = B.reduce((a, e) => a + e.c[CLS[c]], 0) || 1; st['ll' + c] += Math.log(Math.max(B[tj].c[CLS[c]] / tot, 1e-3)); }
      }
      const totP = s.truth.reduce((a, e) => a + e[CLS.P], 0), totT = s.truth.reduce((a, e) => a + e[CLASSES.length], 0);
      B.forEach((e, j) => { st.pzErr += Math.abs(e.c[CLS.P] - s.truth[j][CLS.P]); st.pzErr0 += Math.abs(totP / s.ents.length - s.truth[j][CLS.P]); st.tErr += Math.abs(e.tiles - s.truth[j][CLASSES.length]); st.tErr0 += Math.abs(totT / s.ents.length - s.truth[j][CLASSES.length]); });
    }
  }
  console.log(`\n== ${name}`);
  console.log('known | ll/tile | spec@K | tile hit P T Y B F       | split: n  trap  yel  blood  pz err (even)  tiles err (even)');
  for (const [k, st] of Object.entries(by)) {
    const hs = ['P', 'T', 'Y', 'B', 'F'].map(c => st.cnt[c] ? (st.hit[c] / st.cnt[c]).toFixed(2) : ' -  ').join(' ');
    const br = st.bn ? `${(st.ents / st.bn).toFixed(1)}  ${(st.bT / st.bTn).toFixed(2)}  ${(st.bY / st.bYn).toFixed(2)}  ${(st.bB / st.bBn).toFixed(2)}   ${(st.pzErr / st.ents).toFixed(2)} (${(st.pzErr0 / st.ents).toFixed(2)})    ${(st.tErr / st.ents).toFixed(2)} (${(st.tErr0 / st.ents).toFixed(2)})` : '';
    console.log(`${String(k).padStart(4)}  ${(st.ll / st.tiles).toFixed(3)}   ${(st.spec / st.specN).toFixed(2)}    ${hs}   | ${br}`);
  }
  return by;
}
