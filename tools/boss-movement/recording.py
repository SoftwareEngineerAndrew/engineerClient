"""Reading Better PF recordings (tools/betterpf-viewer/FORMAT.md), standard library only.

Files named <id>.gz may be gzip or xz (told apart by their magic bytes).
"""
import gzip
import json
import lzma
import os

# Chat lines that mark the phases (formatting stripped, as in the "m" field).
MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
MAXOR_INTRO_END = "[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE."
MAXOR_STUN = ("[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!")
MAXOR_ENRAGED = "⚠ Maxor is enraged! ⚠"
MAXOR_DEAD = "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!"
STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
STORM_LIGHTNING = ("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
STORM_CRUSHED = ("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
STORM_ENRAGED = "⚠ Storm is enraged! ⚠"
STORM_PILLAR = "[BOSS] Storm: BEGONE PILLAR!"
STORM_FREE = "[BOSS] Storm: Slowing me down will be your greatest accomplishment!"
STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"


def read_lines(path):
    raw = open(path, 'rb').read()
    data = lzma.decompress(raw) if raw[:3] == b'\xfd7z' else gzip.decompress(raw)
    out = []
    for l in data.decode('utf-8').splitlines():
        if l.strip():
            out.append(json.loads(l))
    return out


def run_path(data_dir, run_id):
    return os.path.join(data_dir, 'runs', run_id + '.gz')


def run_list(data_dir):
    return json.load(open(os.path.join(data_dir, 'runs.json')))


def wanted_ids(data_dir):
    p = os.path.join(data_dir, 'ids.txt')
    if os.path.exists(p):
        return [x for x in open(p).read().split() if x]
    return [r['id'] for r in run_list(data_dir)]
