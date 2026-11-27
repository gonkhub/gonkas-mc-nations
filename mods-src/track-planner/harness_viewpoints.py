"""Writes run/viewpoints.txt for ./gradlew runCamera from the harness's line CSVs.

usage (from mods-src/track-planner): python3 harness_viewpoints.py
Each pick: case, fraction along the line, side (1 or -1), distance back, height above the track, name.
"""
import csv, math, re

report = open("run/harness-out/report.txt").read()
starts = {}
for name, x, z in re.findall(r"=== Case\[name=(\w+).*?\]\nstart (-?\d+), -?\d+, (-?\d+)", report):
    starts[name] = (int(x), int(z))

PICKS = [
    ("c1_autotrack_case1", 0.30, 1, 16, 9, "c1_curve_around_hill"),
    ("c1_autotrack_case1", 0.62, -1, 26, 22, "c1_sea_bridge_high"),
    ("c2_600", 0.12, 1, 22, 10, "c2_bay_bridge_start"),
    ("c2_600", 0.45, 1, 30, 24, "c2_bay_bridge_mid"),
    ("c2_600", 0.45, 1, 10, 4, "c2_bay_bridge_close"),
    ("c2_600", 0.90, -1, 26, 26, "c2_far_shore_high"),
    ("c3_autotrack_case6", 0.88, 1, 22, 12, "c3_valley_and_cutting"),
    ("c3_autotrack_case6", 0.97, -1, 24, 14, "c3_climb_to_goal"),
    ("c4_3km", 0.93, 1, 26, 14, "c4_hill_section"),
]

with open("run/viewpoints.txt", "w") as out:
    for case, f, side, back, up, name in PICKS:
        if case not in starts:
            continue
        r = list(csv.DictReader(open(f"run/harness-out/{case}_line.csv")))
        i = int(f * (len(r) - 1)); j = max(0, i - 6); k = min(len(r) - 1, i + 6)
        sx, sz = starts[case]
        P = (int(r[i]["x"]) + sx, float(r[i]["track_y"]), int(r[i]["z"]) + sz)
        dx = int(r[k]["x"]) - int(r[j]["x"]); dz = int(r[k]["z"]) - int(r[j]["z"])
        L = math.hypot(dx, dz) or 1; dx /= L; dz /= L
        px, pz = -dz * side, dx * side
        spread = 0.45 if up > 15 else 0.7
        C = (P[0] - dx * back + px * back * spread, P[1] + up, P[2] - dz * back + pz * back * spread)
        vx, vy, vz = P[0] - C[0] + dx * 6, P[1] - C[1], P[2] - C[2] + dz * 6
        yaw = math.degrees(math.atan2(-vx, vz)); pitch = -math.degrees(math.atan2(vy, math.hypot(vx, vz)))
        out.write(f"{name} {C[0]:.1f} {C[1]:.1f} {C[2]:.1f} {yaw:.1f} {pitch:.1f}\n")
        print(name, [round(c) for c in C], round(yaw), round(pitch))
