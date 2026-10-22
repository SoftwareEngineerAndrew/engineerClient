// Secret spawn events from extracted recordings: every first sight of a secret item or a bat (fresh or not), and every head
// placed by a block update or first seen standing, with each visible player's position around the moment.
import { placedRooms, roomAt, tracks, at } from './common.mjs';

/** Pack secret on (or within [tol] of) canonical cell c/y of a placed design, of one of [types]. */
export function siteOf(design, c, y, types, tol = 2) {
  let best = null, bd = Infinity;
  for (const s of design.secrets || []) {
    if (!types.includes(s.type)) continue;
    const d = Math.max(Math.abs(c[0] - s.x), Math.abs(y - s.y), Math.abs(c[1] - s.z));
    if (d <= tol && d < bd) { bd = d; best = s; }
  }
  return best;
}

/** What Hypixel puts on an item secret (anything else on a site is a mob drop or a journal). */
export const SECRET_ITEMS = new Set(['Training Weights', 'Healing VIII Splash Potion', 'Inflatable Jerry', 'Decoy', 'Trap', 'Spirit Leap', 'Revive Stone', 'Candycomb', 'Defuse Kit', 'Treasure Talisman', 'Dungeon Chest Key', "Architect's First Draft"]);

/**
 * First sights of one recording: { kind: item|bat|skull, t, x,y,z, fresh (seen being created), pl (its room's placement), design, sid
 * (the pack secret, null when none is near), cell (canonical), pos(k) (every visible player k ticks before), recBox, self }.
 */
export function events(pack, rec, opts = {}) {
  const placed = placedRooms(pack, rec);
  const tr = tracks(rec);
  const self = tr.get(rec.self);
  const out = [];
  const seen = new Set();
  // door bats: many bats spawned on one tick close together
  const batTicks = new Map();
  for (const [t, id, bat, x, y, z] of rec.sp) if (bat) { const k = t; if (!batTicks.has(k)) batTicks.set(k, []); batTicks.get(k).push([x, y, z]); }
  const crowded = (t, x, y, z) => (batTicks.get(t) || []).filter(([a, b, c]) => Math.abs(a - x) <= 3 && Math.abs(b - y) <= 4 && Math.abs(c - z) <= 3).length >= 3;
  const push = (kind, t, x, y, z, fresh, hit, s, extra = {}) => {
    const ev = { kind, t, x, y, z, fresh, pl: hit.r.pl, design: hit.r.design.id, sid: s?.id ?? null, cell: [hit.c[0], Math.floor(y), hit.c[1]], rot: hit.r.pl.rot, group: rec.group, rec: rec.id, ...extra };
    ev.pos = k => { // every visible player's [x,y,z] k ticks before first sight
      const ps = [];
      for (const [name, p] of tr) { const q = at(p, t - k); if (q) ps.push([name, q]); }
      return ps;
    };
    const s0 = self && at(self, t);
    ev.recBox = s0 ? Math.max(Math.abs(s0[0] - x), Math.abs(s0[2] - z)) : Infinity;
    ev.self = rec.self;
    // only the first of a site in a recording (a second one later is a respawn, not a first approach)
    const sk = kind + ' ' + ev.design + ' ' + (ev.sid ?? ev.cell.join(','));
    if (firstOf.has(sk)) return;
    firstOf.add(sk);
    out.push(ev);
  };
  const firstOf = new Set();
  for (const [t, id, bat, x, y, z, item] of rec.sp) {
    if (seen.has(id)) continue; seen.add(id);
    const hit = roomAt(placed, x, z); if (!hit?.r.design) continue;
    if (bat) {
      if (crowded(t, x, y, z)) continue;
      const s = siteOf(hit.r.design, hit.c, Math.floor(y), ['bat'], 3);
      if (!s && !opts.all) continue;
      // a bat is new when it is at its block's centre (secret bats hang still until someone comes within ~4)
      const fresh = Math.abs(x - Math.floor(x) - 0.5) < 0.01 && Math.abs(z - Math.floor(z) - 0.5) < 0.01;
      push('bat', t, x, y, z, fresh, hit, s, { item: '' });
    } else {
      if (!SECRET_ITEMS.has(item)) continue;
      const s = siteOf(hit.r.design, hit.c, Math.floor(y), ['item'], 2);
      if (!s && !opts.all) continue;
      push('item', t, x, y, z, y - Math.floor(y) !== 0, hit, s, { item });
    }
  }
  // skulls: a head block placed by a block update (hb) at an essence/key site
  const placedHead = new Map();
  for (const [t, x, y, z, s] of rec.hb) {
    const key = x + ',' + y + ',' + z;
    if (/head|skull/.test(s) && !placedHead.has(key)) placedHead.set(key, [t, x, y, z, s]);
  }
  const skullFirst = new Map();
  for (const [t, x, y, z, tex] of rec.sk) { const key = x + ',' + y + ',' + z; if (!skullFirst.has(key)) skullFirst.set(key, [t, tex]); }
  for (const [key, [t, x, y, z, s]] of placedHead) {
    const hit = roomAt(placed, x + 0.5, z + 0.5); if (!hit?.r.design) continue;
    const site = siteOf(hit.r.design, hit.c, y, ['essence', 'redstoneKey'], 1);
    if (!site && !opts.all) continue;
    push('skull', t, x + 0.5, y + 0.5, z + 0.5, true, hit, site, { tex: skullFirst.get(key)?.[1] ?? null, state: s });
  }
  // skulls already standing when first scanned (never placed by an update): not fresh
  for (const [key, [t, tex]] of skullFirst) {
    if (placedHead.has(key)) continue;
    const [x, y, z] = key.split(',').map(Number);
    const hit = roomAt(placed, x + 0.5, z + 0.5); if (!hit?.r.design) continue;
    const site = siteOf(hit.r.design, hit.c, y, ['essence', 'redstoneKey'], 1);
    if (!site) continue;
    push('skull', t, x + 0.5, y + 0.5, z + 0.5, false, hit, site, { tex });
  }
  return out;
}
