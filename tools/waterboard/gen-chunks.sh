#!/bin/bash
# Builds the plan table one layout x rotation at a time (16 chunks, ~2 min each); a finished chunk is skipped, so it can
# be stopped and restarted. Then: python3 merge-chunks.py ../../src/resources/assets/engineerclient/waterboard/plans.json
# OUT: where chunks go (default ./chunks). Needs the standalone project set up (standalone/setup.sh) and the Odin jar
# unpacked in libs/odin (for its water-solutions.json seeds and benchmark).
here="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$here/../.." && pwd)"
OUT="${OUT:-$here/chunks}"; mkdir -p "$OUT"
cd "$here/standalone"
for L in 0 1 2 3; do for R in NORTH EAST SOUTH WEST; do
  out=$OUT/$L-$R.json
  [ -s "$out" ] && continue
  echo "$(date +%T) start $L $R" >> $OUT/progress.log
  WB_ODIN=$repo/libs/odin/assets/odin/puzzles/water-solutions.json WB_GEN=1 WB_BUDGET=${WB_BUDGET:-3000} WB_ONLY=$L,$R WB_OUT=$out.tmp \
    timeout 1800 gradle test --tests '*PlanTableGen*' --rerun-tasks > $OUT/$L-$R.log 2>&1
  if grep -q "BUILD SUCCESSFUL" $OUT/$L-$R.log && [ -s $out.tmp ]; then mv $out.tmp $out; mv $OUT/$L-$R.json.odin.json $OUT/$L-$R.odin.json 2>/dev/null; echo "$(date +%T) done $L $R" >> $OUT/progress.log
  else echo "$(date +%T) FAILED $L $R" >> $OUT/progress.log; fi
done; done
echo "$(date +%T) ALL CHUNKS FINISHED" >> $OUT/progress.log
