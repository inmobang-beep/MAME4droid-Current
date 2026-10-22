#!/usr/bin/env python3
"""Beetle Game Room: the arcade game list built into the app.

Reads MAME's own machine list (`mame -listxml`, same MAME version as the engine)
and writes app/src/main/assets/gameroom/mamedb.tsv, one line per arcade game:

    name  title  year  maker  cloneof  bios  players  rotation  disk

(disk: the CHD the game needs in <rom folder>/<name>/, empty when none)

The app matches the ROM files in /sdcard/MAME against it, so people only have to
put their ROMs there. Only facts from MAME's own data; no ROMs, no artwork.

    mame -listxml > mame.xml
    python3 tools/make_gamedb.py mame.xml
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/gameroom/mamedb.tsv"
# (plain text: the build unpacks .gz assets anyway, and the apk compresses it)


def main(xml_path):
    machines = {}
    for _, el in ET.iterparse(xml_path, events=("end",)):
        if el.tag != "machine":
            continue
        a = el.attrib
        disp = el.find("display")
        inp = el.find("input")
        drv = el.find("driver")
        machines[a["name"]] = dict(
            title=(el.findtext("description") or a["name"]).strip(),
            year=(el.findtext("year") or "").strip(),
            maker=(el.findtext("manufacturer") or "").strip(),
            cloneof=a.get("cloneof", ""),
            romof=a.get("romof", ""),
            bios=a.get("isbios") == "yes",
            skip=a.get("isdevice") == "yes" or a.get("ismechanical") == "yes" or a.get("runnable") == "no",
            coins=int(inp.get("coins", "0")) if inp is not None else 0,
            players=inp.get("players", "") if inp is not None else "",
            rotation=disp.get("rotate", "") if disp is not None else "",
            status=drv.get("status", "") if drv is not None else "",
            roms=el.find("rom") is not None or el.find("disk") is not None,
            disk=next((d.get("name", "") for d in el.findall("disk")
                       if d.get("status") != "nodump" and d.get("optional") != "yes"), ""),
        )
        el.clear()

    def bios_of(name):
        seen = set()
        m = machines.get(name)
        while m and m["romof"] and m["romof"] not in seen:
            seen.add(m["romof"])
            parent = machines.get(m["romof"])
            if parent is None:
                return ""
            if parent["bios"]:
                return m["romof"]
            m = parent
        return ""

    rows = []
    for name, m in machines.items():
        # arcade games: coin slots, a screen, ROMs, and a driver that is not preliminary
        if m["bios"] or m["skip"] or not m["roms"] or m["coins"] < 1 or m["status"] == "preliminary":
            continue
        clean = lambda s: s.replace("\t", " ").replace("\n", " ")
        rows.append("\t".join([name, clean(m["title"]), m["year"], clean(m["maker"]), m["cloneof"],
                               bios_of(name), m["players"], m["rotation"], m["disk"]]))
    rows.sort()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")
    print(f"{len(rows)} games -> {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(sys.argv[1])
