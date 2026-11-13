#!/bin/bash
# Links the Water Board sources into this standalone project (run once, from anywhere).
cd "$(dirname "$0")"; mkdir -p src/main/kotlin/wb
for f in Geometry WaterSim Tracker Planner PlanTable; do ln -sf ../../../../../../../src/waterboard/$f.kt src/main/kotlin/wb/$f.kt; done
ls -l src/main/kotlin/wb
