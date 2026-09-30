// Loads boss chunk columns into a block lookup: get(x,y,z) -> state string ('' = unknown).
import fs from 'fs';
export function loadWorld(files) {
  const vols = files.map(f => JSON.parse(fs.readFileSync(f, 'utf8')));
  const grids = vols.map(v => {
    const arr = new Uint16Array(v.w * v.h * v.d); let i = 0;
    for (let r = 0; r < v.rle.length; r += 2) { arr.fill(v.rle[r + 1], i, i + v.rle[r]); i += v.rle[r]; }
    return { v, arr };
  });
  return (x, y, z) => {
    for (const { v, arr } of grids) {
      const lx = x - v.x0, ly = y - v.y0, lz = z - v.z0;
      if (lx < 0 || ly < 0 || lz < 0 || lx >= v.w || ly >= v.h || lz >= v.d) continue;
      return v.pal[arr[(ly * v.d + lz) * v.w + lx]] || '';
    }
    return '';
  };
}
