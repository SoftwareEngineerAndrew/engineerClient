import { TYPE_CODE } from './layout.mjs';
// ASCII map with doors: room letters, '|' / '-' doors (w wither: '#', b blood: 'B', e entrance: 'e'), same-room joins as ' ' fill.
export function draw(L, w = 6, h = 6) {
  const dk = new Map();
  for (const d of L.doors) dk.set(`${d.ax},${d.az},${d.bx},${d.bz}`, d);
  const ch = (x, z) => { const i = L.grid[z][x]; if (i < 0) return '.'; const r = L.rooms[i]; const c = TYPE_CODE[r.type]; return r.type === 'NORMAL' ? (r.shape === 'OneByOne' ? 'o' : 'n') : c; };
  const out = [];
  for (let z = 0; z < h; z++) {
    let a = '', b = '';
    for (let x = 0; x < w; x++) {
      a += ch(x, z);
      if (x < w - 1) {
        const d = dk.get(`${x},${z},${x + 1},${z}`);
        const same = L.grid[z][x] >= 0 && L.grid[z][x] === L.grid[z][x + 1];
        a += same ? '=' : !d || d.what === '-' ? ' ' : d.what === 'w' ? '#' : d.what === 'b' ? 'B' : d.what === 'e' ? 'e' : '-';
      }
      if (z < h - 1) {
        const d = dk.get(`${x},${z},${x},${z + 1}`);
        const same = L.grid[z][x] >= 0 && L.grid[z][x] === L.grid[z + 1][x];
        b += (same ? '‖' : !d || d.what === '-' ? ' ' : d.what === 'w' ? '#' : d.what === 'b' ? 'B' : d.what === 'e' ? 'e' : '|') + ' ';
      }
    }
    out.push(a); if (z < h - 1) out.push(b);
  }
  return out;
}
