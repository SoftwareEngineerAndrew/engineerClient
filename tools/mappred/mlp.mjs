// A small softmax MLP (one ReLU hidden layer), trained with Adam on Float32 rows.
export function makeRng(seed) { let s = seed >>> 0; return () => { s = (s + 0x6D2B79F5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

export function createMlp(D, Hd, C, rnd) {
  const g = () => { let u = 0, v = 0; while (!u) u = rnd(); v = rnd(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); };
  const W1 = new Float32Array(D * Hd).map(() => g() * Math.sqrt(2 / D));
  const b1 = new Float32Array(Hd);
  const W2 = new Float32Array(Hd * C).map(() => g() * Math.sqrt(1 / Hd));
  const b2 = new Float32Array(C);
  return { D, Hd, C, W1, b1, W2, b2 };
}

export function forward(m, x, h, p) {
  const { D, Hd, C, W1, b1, W2, b2 } = m;
  for (let j = 0; j < Hd; j++) {
    let s = b1[j];
    for (let i = 0; i < D; i++) { const xi = x[i]; if (xi !== 0) s += xi * W1[i * Hd + j]; }
    h[j] = s > 0 ? s : 0;
  }
  let mx = -Infinity;
  for (let c = 0; c < C; c++) { let s = b2[c]; for (let j = 0; j < Hd; j++) s += h[j] * W2[j * C + c]; p[c] = s; if (s > mx) mx = s; }
  let z = 0; for (let c = 0; c < C; c++) { p[c] = Math.exp(p[c] - mx); z += p[c]; }
  for (let c = 0; c < C; c++) p[c] /= z;
}

export function predict(m, x) { const h = new Float32Array(m.Hd), p = new Float32Array(m.C); forward(m, x, h, p); return p; }

export function train(m, X, Y, { epochs = 10, batch = 128, lr = 2e-3, l2 = 1e-5, rnd, log } = {}) {
  const { D, Hd, C } = m;
  const params = [m.W1, m.b1, m.W2, m.b2];
  const grads = params.map(a => new Float32Array(a.length));
  const M = params.map(a => new Float32Array(a.length)), V = params.map(a => new Float32Array(a.length));
  let step = 0;
  const idx = Array.from(X.keys());
  const h = new Float32Array(Hd), p = new Float32Array(C), dh = new Float32Array(Hd);
  for (let ep = 0; ep < epochs; ep++) {
    for (let i = idx.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [idx[i], idx[j]] = [idx[j], idx[i]]; }
    let loss = 0;
    const rate = lr * (ep >= epochs * 0.7 ? 0.3 : 1);
    for (let s = 0; s < idx.length; s += batch) {
      for (const gr of grads) gr.fill(0);
      const end = Math.min(idx.length, s + batch);
      for (let k = s; k < end; k++) {
        const x = X[idx[k]], y = Y[idx[k]];
        forward(m, x, h, p);
        loss -= Math.log(Math.max(p[y], 1e-9));
        // output grad
        for (let c = 0; c < C; c++) {
          const d = p[c] - (c === y ? 1 : 0);
          grads[3][c] += d;
          for (let j = 0; j < Hd; j++) grads[2][j * C + c] += d * h[j];
        }
        for (let j = 0; j < Hd; j++) {
          if (h[j] <= 0) { dh[j] = 0; continue; }
          let s2 = 0; for (let c = 0; c < C; c++) s2 += (p[c] - (c === y ? 1 : 0)) * m.W2[j * C + c];
          dh[j] = s2;
        }
        for (let i = 0; i < D; i++) { const xi = x[i]; if (xi === 0) continue; for (let j = 0; j < Hd; j++) grads[0][i * Hd + j] += xi * dh[j]; }
        for (let j = 0; j < Hd; j++) grads[1][j] += dh[j];
      }
      const n = end - s; step++;
      const b1c = 1 - Math.pow(0.9, step), b2c = 1 - Math.pow(0.999, step);
      for (let q = 0; q < params.length; q++) {
        const P = params[q], G = grads[q], Mq = M[q], Vq = V[q];
        for (let i = 0; i < P.length; i++) {
          const gi = G[i] / n + l2 * P[i];
          Mq[i] = 0.9 * Mq[i] + 0.1 * gi; Vq[i] = 0.999 * Vq[i] + 0.001 * gi * gi;
          P[i] -= rate * (Mq[i] / b1c) / (Math.sqrt(Vq[i] / b2c) + 1e-8);
        }
      }
    }
    log?.(`epoch ${ep + 1}: loss ${(loss / idx.length).toFixed(4)}`);
  }
}
