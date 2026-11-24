#!/usr/bin/env python3
"""Turn settings copied off a device into clean Loadout templates.

usage: make_templates.py <copied-settings-dir> <templates-out-dir>

Anything personal or device-specific is dropped or swapped for a
{{PLACEHOLDER}} that Loadout fills in on the target device.
"""
import json
import re
import shutil
import sys
from pathlib import Path

src = Path(sys.argv[1])
out = Path(sys.argv[2])
if out.exists():
    shutil.rmtree(out)
out.mkdir(parents=True)


def read(rel):
    return (src / rel).read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")


def write(rel, text):
    p = out / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text if text.endswith("\n") else text + "\n", encoding="utf-8")


def cfg_value(text, key):
    m = re.search(r'^%s = "(.*)"$' % re.escape(key), text, re.M)
    return m.group(1) if m else None


# ---------------------------------------------------------------- RetroArch
ra = read("retroarch/retroarch.cfg")

# Keys carried over exactly as they are on the device.
RA_KEEP = [
    "video_driver", "video_vsync", "video_threaded", "video_smooth",
    "video_scale_integer", "aspect_ratio_index", "video_fullscreen",
    "audio_driver", "audio_latency", "audio_sync",
    "input_enable_hotkey_btn", "input_exit_emulator_btn",
    "input_save_state_btn", "input_load_state_btn",
    "input_hold_fast_forward_btn", "input_menu_toggle_btn",
    "input_fps_toggle_btn", "input_menu_toggle_gamepad_combo",
    "input_quit_gamepad_combo", "quit_press_twice",
    "savestate_auto_save", "savestate_auto_load", "autosave_interval",
    "menu_driver", "menu_swap_ok_cancel_buttons", "fps_show",
    "input_overlay_enable", "input_autodetect_enable",
    "auto_overrides_enable", "auto_remaps_enable", "auto_shaders_enable",
    "game_specific_options", "global_core_options",
    "savefiles_in_content_dir", "savestates_in_content_dir",
    "systemfiles_in_content_dir", "rewind_enable", "pause_nonactive",
]
# Keys Loadout decides itself.
RA_SET = {
    "video_shader_enable": "false",            # shaders are off until chosen
    "system_directory": "{{BIOS_DIR}}",
    "savefile_directory": "{{RETROARCH_DIR}}/saves",
    "savestate_directory": "{{RETROARCH_DIR}}/states",
    "screenshot_directory": "{{RETROARCH_DIR}}/screenshots",
    "rgui_config_directory": "{{RETROARCH_DIR}}/config",
    "input_remapping_directory": "{{RETROARCH_DIR}}/config/remaps",
}
lines = ["# Loadout: RetroArch settings. Only these keys are changed;",
         "# everything else in retroarch.cfg is left as it is.", ""]
for k in RA_KEEP:
    v = cfg_value(ra, k)
    if v is None:
        raise SystemExit("retroarch.cfg has no " + k)
    lines.append('%s = "%s"' % (k, v))
for k, v in RA_SET.items():
    lines.append('%s = "%s"' % (k, v))
write("retroarch/retroarch.cfg.patch", "\n".join(lines))

for core in ["Nestopia", "Snes9x", "Genesis Plus GX", "Gambatte", "mGBA", "SwanStation"]:
    rel = "retroarch/config/%s/%s.opt" % (core, core)
    write(rel, read(rel))

preset = read("retroarch/config/global.slangp")
preset = re.sub(r'"/data/user/0/[^/"]+/', '"{{RETROARCH_DATA}}/', preset)
write("retroarch/shaders/crt-trinitron.slangp", preset)

# ------------------------------------------------------------------ Dolphin
def ini_sections(text):
    """[(section, [lines])] keeping order."""
    out_, cur = [], None
    for line in text.split("\n"):
        m = re.match(r"^\[(.+)\]$", line)
        if m:
            cur = [m.group(1), []]
            out_.append(cur)
        elif cur is not None and line.strip():
            cur[1].append(line)
    return out_


def ini_text(sections):
    parts = []
    for name, body in sections:
        if body:
            parts.append("[%s]\n%s" % (name, "\n".join(body)))
    return "\n".join(parts)


def drop_keys(sections, section, keys):
    for name, body in sections:
        if name == section:
            body[:] = [l for l in body if l.split("=")[0].strip() not in keys]
    return sections


d = ini_sections(read("dolphin/Config/Dolphin.ini"))
drop_keys(d, "Analytics", {"ID"})                     # install ID
drop_keys(d, "General", {"ISOPath0", "ISOPaths"})     # this card's games folder
write("dolphin/Dolphin.ini", ini_text(d))

g = ini_sections(read("dolphin/Config/GFX.ini"))
drop_keys(g, "Settings", {"WireFrame", "DriverLibName"})
write("dolphin/GFX.ini", ini_text(g))

for name in ["GCPadNew.ini", "WiimoteNew.ini"]:
    write("dolphin/" + name, read("dolphin/Config/" + name))

# --------------------------------------------------------------------- Eden
# Everything in Eden's file is default except the controller mapping, so the
# template is only that mapping. Token, serials, game list and folder paths
# never leave the device.
eden = read("eden/config/config.ini").split("\n")
keep, section = [], None
for i, line in enumerate(eden):
    m = re.match(r"^\[(.+)\]$", line)
    if m:
        section = m.group(1)
        continue
    if section != "Controls":
        continue
    m = re.match(r"^(player_[08]_[a-z_]+)\\default=false$", line)
    if m and eden[i + 1].startswith(m.group(1) + "="):
        keep += [line, eden[i + 1]]
write("eden/config.ini.patch",
      "# Loadout: Eden settings. Merged into Eden's config.ini;\n"
      "# every setting not listed here stays at its default.\n\n"
      "[Controls]\n" + "\n".join(keep) + "\n\n"
      # Docked mode on (0 = handheld, 1 = docked). Loadout's choice, not
      # copied from the device, where it was still handheld.
      "[System]\nuse_docked_mode\\default=false\nuse_docked_mode=1")

# ------------------------------------------------------------------- ARMSX2
j = json.loads(read("armsx2/armsx2-settings.json"))
glob = dict(j["global"])
for k in ["customDriverId", "biosFilename"]:
    glob.pop(k, None)
# Memory card names are the user's own; a merge leaves theirs alone.
for k in [k for k in glob if re.match(r"memoryCardSlot\d+Filename$", k)]:
    glob.pop(k)
write("armsx2/armsx2-settings.json", json.dumps({"global": glob}, indent=2))

p = read("armsx2/PCSX2-Android.ini")
p = re.sub(r"^BIOS = .*$", "BIOS = {{PS2_BIOS_FILE}}", p, flags=re.M)
# Memory card names are the user's own; a merge leaves theirs alone.
p = re.sub(r"^Slot\d+_Filename = .*\n", "", p, flags=re.M)
p = re.sub(r"\n{3,}", "\n\n", p)
write("armsx2/PCSX2-Android.ini", p)

# ----------------------------------------------------------------- manifest
RA_FILES = "{{INTERNAL}}/Android/data/{{PKG}}/files"
RA_SHARED = "{{INTERNAL}}/RetroArch"
CORES = ["Nestopia", "Snes9x", "Genesis Plus GX", "Gambatte", "mGBA", "SwanStation"]
manifest = {
    "version": 2,
    "profile": "ayn-odin2-portal",
    "profileName": "AYN Odin 2 Portal",
    "placeholders": {
        "INTERNAL": "internal storage",
        "PKG": "the emulator's Android package name",
        "BIOS_DIR": "the BIOS folder Loadout found or created",
        "RETROARCH_DIR": "RetroArch's folder on internal storage or the card",
        "RETROARCH_DATA": "RetroArch's own app data folder",
        "PS2_BIOS_FILE": "file name of the PS2 BIOS Loadout found",
    },
    # root: a folder the emulator creates itself. Loadout only writes when it exists.
    # path: the file inside root. mode: how the template is combined with what is there.
    "emulators": {
        "RetroArch": {
            "files": [
                {"template": "retroarch/retroarch.cfg.patch", "root": RA_FILES,
                 "path": "retroarch.cfg", "mode": "merge-cfg"},
                *[{"template": "retroarch/config/%s/%s.opt" % (c, c), "root": RA_SHARED,
                   "path": "config/%s/%s.opt" % (c, c), "mode": "merge-cfg"} for c in CORES],
                {"template": "retroarch/shaders/crt-trinitron.slangp", "root": RA_SHARED,
                 "path": "config/global.slangp", "mode": "replace", "optional": "shader"},
            ],
            "resolution": [{"file": "retroarch/config/SwanStation/SwanStation.opt",
                            "key": "swanstation_GPU_ResolutionScale"}],
        },
        "Dolphin": {
            "files": [{"template": "dolphin/" + n, "root": "{{INTERNAL}}/Android/data/{{PKG}}/files",
                       "path": "Config/" + n, "mode": "merge-ini"}
                      for n in ["Dolphin.ini", "GFX.ini", "GCPadNew.ini", "WiimoteNew.ini"]],
            "resolution": [{"file": "dolphin/GFX.ini", "key": "Settings/InternalResolution"}],
        },
        "Eden": {
            "files": [{"template": "eden/config.ini.patch", "root": "{{INTERNAL}}/Android/data/{{PKG}}/files",
                       "path": "config/config.ini", "mode": "merge-ini"}],
        },
        "ARMSX2": {
            "files": [
                {"template": "armsx2/armsx2-settings.json", "root": "{{INTERNAL}}/Armsx2",
                 "path": "armsx2-settings.json", "mode": "merge-json"},
                {"template": "armsx2/PCSX2-Android.ini", "root": "{{INTERNAL}}/Armsx2",
                 "path": "PCSX2-Android.ini", "mode": "merge-ini"},
            ],
            "resolution": [
                {"file": "armsx2/armsx2-settings.json", "key": "global/upscaleFloat"},
                {"file": "armsx2/PCSX2-Android.ini", "key": "EmuCore/GS/upscale_multiplier"},
            ],
        },
    },
}
write("templates.json", json.dumps(manifest, indent=2))
print("wrote", sum(1 for _ in out.rglob("*") if _.is_file()), "files to", out)
