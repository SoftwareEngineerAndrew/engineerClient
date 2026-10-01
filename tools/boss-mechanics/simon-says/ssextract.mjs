// Every run: the Simon Says events, as a compact list, to ss.json.
import fs from 'fs';
const dir = 'runs';
const out = [];
for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.jsonl'))) {
  let text; try { text = fs.readFileSync(`${dir}/${f}`, 'utf8'); } catch { continue; }
  if (!text.includes('Goldor: Who dares')) continue;
  const pal = []; const ev = []; let meta = null, party = null, p3 = null; const pos = [];
  for (const l of text.split('\n')) {
    if (!l || l[0] !== '{') continue;
    const k = l.slice(6, 12);
    if (!(k.startsWith('pal') || k.startsWith('block') || k.startsWith('use') || k.startsWith('ssclic') || k.startsWith('sssoun') || k.startsWith('chat') || k.startsWith('meta') || k.startsWith('party') || k.startsWith('p"'))) continue;
    let o; try { o = JSON.parse(l); } catch { continue; }
    if (o.k === 'pal') pal[o.i] = o.s;
    else if (o.k === 'meta') meta = o;
    else if (o.k === 'party') party = o.m;
    else if (o.k === 'chat') {
      if (/Goldor: Who dares/.test(o.m)) p3 = o.t;
      if (/(completed a device|activated a (terminal|lever)|The gate has been destroyed|The Core entrance)/.test(o.m) && !/Party >|Guild >/.test(o.m)) ev.push([o.t, 'chat', o.m.trim()]);
    }
    else if (o.k === 'block') {
      const b = pal[o.s] || '';
      if (o.x === 111 && o.y >= 120 && o.y <= 123 && o.z >= 92 && o.z <= 95) ev.push([o.t, b.includes('sea_lantern') ? 'on' : b.includes('obsidian') ? 'off' : 'x111:' + b, o.y, o.z]);
      else if (o.x === 110 && o.y >= 120 && o.y <= 123 && o.z >= 92 && o.z <= 95) ev.push([o.t, b.includes('powered=true') ? 'down' : b.includes('button') ? 'up' : b === 'minecraft:air' ? 'air' : 'x110:' + b, o.y, o.z]);
      else if (o.x === 110 && o.y === 121 && o.z === 91) ev.push([o.t, b.includes('powered=true') ? 'start' : 'startup']);
    }
    // Newer recorders: your own clicks once each (blocked by a mod or not, and whether the
    // button already read as pressed), and every sound at the device.
    else if (o.k === 'ssclick') ev.push([o.t, o.z === 91 ? 'startclick' : 'click', o.y, o.z, o.blocked, o.powered]);
    else if (o.k === 'sssound') ev.push([o.t, 'sound', o.id, o.x, o.y, o.z, o.v, o.p]);
    else if (o.k === 'use' && o.x >= 110 && o.x <= 111 && o.y >= 120 && o.y <= 123 && o.z >= 91 && o.z <= 95) ev.push([o.t, 'use', o.x, o.y, o.z]);
    else if (o.k === 'p' && p3 != null && o.t >= p3 && o.t < p3 + 1200 && meta) {
      for (const q of o.d) if (q[0] === meta.self && Math.abs(q[1] - 108.5) < 4 && Math.abs(q[3] - 93.5) < 5 && q[2] > 118 && q[2] < 124) pos.push([o.t, q[1], q[2], q[3], q[4], q[5]]);
    }
  }
  out.push({ id: f.replace('.jsonl', ''), self: meta?.self, startMs: meta?.startMs, party, p3, ev, pos: pos.filter((_, i) => i % 5 === 0) });
}
fs.writeFileSync('ss.json', JSON.stringify(out));
console.log('runs with P3:', out.length);
