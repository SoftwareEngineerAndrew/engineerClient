// Downloads F7/M7 runs from ADR into runs/<id>. By default only uploads that ended early
// (not cleared, under 2000 ticks): where restarts after an instaclear land.
//   node fetch.mjs [max runs] [all]
import { writeFileSync, existsSync, mkdirSync } from 'node:fs';
const [max = '1000', which = 'early'] = process.argv.slice(2);
mkdirSync('runs', { recursive: true });
const list = await (await fetch('https://undonecoffee.com/adr/api/runs')).json();
const rows = list.filter(r => /^[FM]7$/.test(r.floor || '') && (which === 'all' || (!r.cleared && r.ticks > 300 && r.ticks < 2000))).slice(0, +max);
console.error(`${rows.length} runs`);
let next = 0, failed = 0;
async function worker() {
  while (next < rows.length) {
    const r = rows[next++], f = `runs/${r.id}`;
    if (existsSync(f)) continue;
    try {
      const res = await fetch(`https://undonecoffee.com/adr/api/runs/${r.id}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`); // older than a week: 429 after 30 a day
      writeFileSync(f, Buffer.from(await res.arrayBuffer()));
    } catch (e) { failed++; console.error(r.id, e.message); }
  }
}
await Promise.all(Array.from({ length: 8 }, worker));
console.error(`done, ${failed} failed`);
