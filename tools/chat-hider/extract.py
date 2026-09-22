import gzip, re, glob, os, json, collections, sys
insts = ["26.1.2", "26.1.2 BRW"]
lines = []
for inst in insts:
    d = os.path.expanduser(f"~/.local/share/PrismLauncher/instances/{inst}/minecraft/logs")
    for f in sorted(glob.glob(d + "/*.log*")):
        op = gzip.open if f.endswith(".gz") else open
        try:
            with op(f, "rt", errors="replace") as fh:
                for ln in fh:
                    if "[CHAT] " not in ln: continue
                    msg = ln.split("[CHAT] ", 1)[1].rstrip("\n")
                    lines.append((os.path.basename(f), msg))
        except Exception as e:
            print("skip", f, e, file=sys.stderr)
fmt = re.compile("§.")
def clean(s): return fmt.sub("", s).strip()
msgs = [(f, clean(m)) for f, m in lines if clean(m)]
print("total chat lines:", len(msgs), file=sys.stderr)
json.dump(msgs, open(sys.argv[1], "w"))
