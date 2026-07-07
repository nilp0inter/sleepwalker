#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
from pathlib import Path

# Add the script parent directory to sys.path so we can import sleepwalker_protocol
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

try:
    from sleepwalker_protocol.usages import USAGE_REGISTRY
except ImportError:
    # Fallback if run standalone without parent sys.path set
    from usages import USAGE_REGISTRY

# Map of modifier names to bit flags
MODIFIERS_MAP = {
    "LEFT_CTRL": 0x01, "CTRL": 0x01, "LCTRL": 0x01,
    "LEFT_SHIFT": 0x02, "SHIFT": 0x02, "LSHIFT": 0x02,
    "LEFT_ALT": 0x04, "ALT": 0x04, "LALT": 0x04,
    "LEFT_GUI": 0x08, "GUI": 0x08, "META": 0x08, "SUPER": 0x08, "LMETA": 0x08,
    "RIGHT_CTRL": 0x10, "RCTRL": 0x10,
    "RIGHT_SHIFT": 0x20, "RSHIFT": 0x20,
    "RIGHT_ALT": 0x40, "ALT_GR": 0x40, "ALTGR": 0x40, "RALT": 0x40,
    "RIGHT_GUI": 0x80, "RMETA": 0x80
}

def parse_modifiers(mods_list) -> int:
    mask = 0
    if not mods_list:
        return mask
    for mod in mods_list:
        # Map X11 modifier names to MODIFIERS_MAP keys
        mod_upper = mod.upper()
        if mod_upper in ("SHIFT", "SHIFTLEFT", "SHIFTRIGHT"):
            mask |= MODIFIERS_MAP.get("SHIFT", 0)
        elif mod_upper in ("CONTROL", "CTRL", "CONTROLLEFT", "CONTROLRIGHT"):
            mask |= MODIFIERS_MAP.get("CTRL", 0)
        elif mod_upper in ("ALT", "ALTLEFT", "ALTRIGHT"):
            mask |= MODIFIERS_MAP.get("ALT", 0)
        elif mod_upper in ("META", "SUPER", "GUI", "METALEFT", "METARIGHT"):
            mask |= MODIFIERS_MAP.get("GUI", 0)
        else:
            mask |= MODIFIERS_MAP.get(mod_upper, 0)
    return mask

# X11/Web key name to USB HID usage name mapping
X11_TO_USB = {
    # Letters
    **{f"Key{chr(c)}": f"USB_KEY_{chr(c)}" for c in range(ord('A'), ord('Z')+1)},
    # Digits
    **{f"Digit{d}": f"USB_KEY_{d}" for d in range(10)},
    # Punctuation and symbols
    "BracketLeft": "USB_KEY_LEFTBRACE",
    "BracketRight": "USB_KEY_RIGHTBRACE",
    "Equal": "USB_KEY_EQUAL",
    "Minus": "USB_KEY_MINUS",
    "Semicolon": "USB_KEY_SEMICOLON",
    "Quote": "USB_KEY_APOSTROPHE",
    "Backquote": "USB_KEY_GRAVE",
    "Backslash": "USB_KEY_BACKSLASH",
    "Comma": "USB_KEY_COMMA",
    "Period": "USB_KEY_DOT",
    "Slash": "USB_KEY_SLASH",
    "Space": "USB_KEY_SPACE",
    "Enter": "USB_KEY_ENTER",
    "Escape": "USB_KEY_ESCAPE",
    # International keys
    "IntlBackslash": "USB_KEY_NONUSBACKSLASH",
    "IntlRo": "USB_KEY_RO",
    "IntlYen": "USB_KEY_RO",
    # Modifiers
    "ShiftLeft": "LEFT_SHIFT",
    "ShiftRight": "RIGHT_SHIFT",
    "ControlLeft": "LEFT_CTRL",
    "ControlRight": "RIGHT_CTRL",
    "AltLeft": "LEFT_ALT",
    "AltRight": "RIGHT_ALT",
    "MetaLeft": "LEFT_GUI",
    "MetaRight": "RIGHT_GUI",
}

def parse_usage(usage_val) -> int:
    if isinstance(usage_val, int):
        return usage_val
    if isinstance(usage_val, str):
        if usage_val.startswith("0x") or usage_val.startswith("0X"):
            return int(usage_val, 16)
        if usage_val.isdigit():
            return int(usage_val)
        # Try X11/Web name mapping first
        if usage_val in X11_TO_USB:
            usage_val = X11_TO_USB[usage_val]
        # Search in USAGE_REGISTRY
        if usage_val in USAGE_REGISTRY:
            return USAGE_REGISTRY[usage_val].usb_usage
        if f"USB_{usage_val}" in USAGE_REGISTRY:
            return USAGE_REGISTRY[f"USB_{usage_val}"].usb_usage
        if f"USB_KEY_{usage_val}" in USAGE_REGISTRY:
            return USAGE_REGISTRY[f"USB_KEY_{usage_val}"].usb_usage
    raise ValueError(f"Unknown USB usage: {usage_val}")

def escape_char(char: str) -> str:
    if char == "'":
        return "'\\''"
    elif char == "\\":
        return "'\\\\'"
    elif char == "\n":
        return "'\\n'"
    elif char == "\r":
        return "'\\r'"
    elif char == "\t":
        return "'\\t'"
    elif ord(char) < 32 or ord(char) > 126:
        return f"'\\u{ord(char):04x}'"
    else:
        return f"'{char}'"

def generate_keymap_data(layouts: list) -> str:
    lines = []
    lines.append("// Generated keymap database. DO NOT EDIT DIRECTLY.")
    lines.append("package io.sleepwalker.core.keymap.generated")
    lines.append("")
    lines.append("import io.sleepwalker.core.keymap.HostProfile")
    lines.append("import io.sleepwalker.core.keymap.KeymapDatabase")
    lines.append("import io.sleepwalker.core.keymap.KeymapEntry")
    lines.append("import io.sleepwalker.core.keymap.KeymapTap")
    lines.append("")
    lines.append("object GeneratedKeymapDatabase : KeymapDatabase {")
    lines.append("")
    lines.append("    private val keymaps: Map<HostProfile, List<KeymapEntry>> = mapOf(")

    for layout in layouts:
        os_name = layout["os"]
        layout_name = layout["layout"]
        variant = layout.get("variant")
        mappings = layout["mappings"]

        if variant is None:
            profile_str = f'HostProfile("{os_name}", "{layout_name}")'
        else:
            profile_str = f'HostProfile("{os_name}", "{layout_name}", "{variant}")'

        lines.append(f"        {profile_str} to listOf(")

        for m in mappings:
            char_lit = escape_char(m["char"])
            taps_str = []
            for tap in m["taps"]:
                usage = parse_usage(tap["key"])
                mods = parse_modifiers(tap.get("modifiers", []))
                taps_str.append(f"KeymapTap(0x{usage:02x}, 0x{mods:02x})")
            taps_list = ", ".join(taps_str)
            lines.append(f"            KeymapEntry({char_lit}, listOf({taps_list})),")

        lines.append("        ),")

    lines.append("    )")
    lines.append("")
    lines.append("    override fun lookup(profile: HostProfile): List<KeymapEntry>? {")
    lines.append("        return keymaps[profile]")
    lines.append("    }")
    lines.append("")
    lines.append("    override val profiles: Collection<HostProfile>")
    lines.append("        get() = keymaps.keys")
    lines.append("}")
    return "\n".join(lines)

def generate_layout_class(os_name: str, layout: str, variant: str | None, mappings: list) -> str:
    class_name = clean_class_name(os_name, layout, variant)
    lines = []
    lines.append("// Generated keymap layout. DO NOT EDIT DIRECTLY.")
    lines.append("package io.sleepwalker.core.keymap.generated")
    lines.append("")
    lines.append("import io.sleepwalker.core.keymap.KeymapEntry")
    lines.append("import io.sleepwalker.core.keymap.KeymapTap")
    lines.append("")
    lines.append(f"object {class_name} {{")
    lines.append("    val entries: List<KeymapEntry> = listOf(")

    for m in mappings:
        char = m["char"]
        # Escape the character for a Kotlin Char literal
        if char == "'":
            char_lit = "'\\''"
        elif char == "\\":
            char_lit = "'\\\\'"
        elif char == "\n":
            char_lit = "'\\n'"
        elif char == "\r":
            char_lit = "'\\r'"
        elif char == "\t":
            char_lit = "'\\t'"
        elif ord(char) < 32 or ord(char) > 126:
            char_lit = f"'\\u{ord(char):04x}'"
        else:
            char_lit = f"'{char}'"

        taps_str = []
        for tap in m["taps"]:
            usage = parse_usage(tap["key"])
            mods = parse_modifiers(tap.get("modifiers", []))
            taps_str.append(f"KeymapTap(0x{usage:02x}, 0x{mods:02x})")
        
        taps_list = ", ".join(taps_str)
        lines.append(f"        KeymapEntry({char_lit}, listOf({taps_list})),")

    lines.append("    )")
    lines.append("}")
    return "\n".join(lines)

def generate_registry(layouts: list) -> str:
    lines = []
    lines.append("// Generated keymap database registry. DO NOT EDIT DIRECTLY.")
    lines.append("package io.sleepwalker.core.keymap.generated")
    lines.append("")
    lines.append("import io.sleepwalker.core.keymap.HostProfile")
    lines.append("import io.sleepwalker.core.keymap.KeymapDatabase")
    lines.append("import io.sleepwalker.core.keymap.KeymapEntry")
    lines.append("")
    lines.append("object GeneratedKeymapDatabase : KeymapDatabase {")
    lines.append("    override fun lookup(profile: HostProfile): List<KeymapEntry>? {")
    lines.append("        val os = profile.hostOs.lowercase()")
    lines.append("        val layout = profile.layout.lowercase()")
    lines.append("        val variant = profile.variant?.lowercase()")
    lines.append("")
    lines.append("        return when (os) {")

    # Group layouts by OS, then by layout
    by_os = {}
    for l in layouts:
        by_os.setdefault(l["os"].lower(), {}).setdefault(l["layout"].lower(), []).append(l)

    for os_name, layouts_by_name in by_os.items():
        lines.append(f'            "{os_name}" -> when (layout) {{')
        for layout_name, variants in layouts_by_name.items():
            lines.append(f'                "{layout_name}" -> when (variant) {{')
            # Sort null/empty first, then others
            variants = sorted(variants, key=lambda x: x.get("variant") or "")
            for var_layout in variants:
                var_val = var_layout.get("variant")
                class_name = clean_class_name(var_layout["os"], var_layout["layout"], var_val)
                if var_val is None:
                    lines.append(f"                    null -> {class_name}.entries")
                else:
                    lines.append(f'                    "{var_val.lower()}" -> {class_name}.entries')
            lines.append("                    else -> null")
            lines.append("                }")
        lines.append("                else -> null")
        lines.append("            }")

    lines.append("            else -> null")
    lines.append("        }")
    lines.append("    }")
    lines.append("")
    lines.append("    override val profiles: Collection<HostProfile> = listOf(")
    for l in layouts:
        var_val = l.get("variant")
        if var_val is None:
            lines.append(f'        HostProfile("{l["os"]}", "{l["layout"]}"),')
        else:
            lines.append(f'        HostProfile("{l["os"]}", "{l["layout"]}", "{var_val}"),')
    lines.append("    )")
    lines.append("}")
    return "\n".join(lines)

def main():
    parser = argparse.ArgumentParser(description="Compile OmniKeymap JSON database to Kotlin objects")
    parser.add_argument("--db-path", type=str, required=True, help="Path to the OmniKeymap database directory")
    parser.add_argument("--out-dir", type=str, required=True, help="Output directory for generated Kotlin files")
    args = parser.parse_args()

    db_path = Path(args.db_path)
    out_dir = Path(args.out_dir)

    if not db_path.exists() or not db_path.is_dir():
        print(f"Warning: OmniKeymap database path '{db_path}' not found or is not a directory. Skipping keymap generation.", file=sys.stderr)
        sys.exit(0)

    json_files = list(db_path.glob("**/*.json"))
    if not json_files:
        print(f"Warning: No keymap JSON files found in '{db_path}'. Skipping keymap generation.", file=sys.stderr)
        sys.exit(0)

    out_dir.mkdir(parents=True, exist_ok=True)
    layouts = []

    for path in json_files:
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)

            # OmniKeymap stores os/layout in metadata object
            metadata = data.get("metadata", {})
            os_name = metadata.get("platform") or data.get("os") or path.parent.name
            layout = metadata.get("layout_name") or data.get("layout") or path.stem.split("-")[0]
            # Extract variant from filename: <layout>+<variant>.json
            variant = None
            if "+" in path.stem:
                variant = path.stem.split("+", 1)[1]

            raw_mappings = data.get("mappings", {})
            mappings = []

            # Handle dict mappings: { "a": [{ "sequence": [{ "key": "USB_KEY_A", "modifiers": [] }] }] }
            if isinstance(raw_mappings, dict):
                for char, val in raw_mappings.items():
                    if isinstance(val, list):
                        # OmniKeymap format: list of tap sequences
                        taps = []
                        for tap_seq in val:
                            if "sequence" in tap_seq:
                                # Each sequence is an array of key presses
                                for key_press in tap_seq["sequence"]:
                                    taps.append({
                                        "key": key_press.get("key"),
                                        "modifiers": key_press.get("modifiers", [])
                                    })
                            elif "key" in tap_seq:
                                # Direct tap format
                                taps.append(tap_seq)
                        if taps and len(char) == 1:
                            mappings.append({"char": char, "taps": taps})
                    elif isinstance(val, dict):
                        taps = val.get("taps") or [val]
                        mappings.append({"char": char, "taps": taps})
                    else:
                        continue
            # Handle list mappings: [ { "char": "a", "taps": [...] } ]
            elif isinstance(raw_mappings, list):
                for item in raw_mappings:
                    char = item.get("char")
                    if char is None:
                        continue
                    taps = item.get("taps")
                    if taps is None:
                        # Fallback for flat list mapping
                        taps = [item]
                    mappings.append({"char": char, "taps": taps})

            if not mappings:
                continue

            layout_info = {
                "os": os_name,
                "layout": layout,
                "variant": variant,
                "mappings": mappings
            }
            layouts.append(layout_info)

        except Exception as e:
            print(f"Error processing '{path}': {e}", file=sys.stderr)
            # Skip invalid files to keep build robust

    if layouts:
        kotlin_code = generate_keymap_data(layouts)
        with open(out_dir / "GeneratedKeymaps.kt", "w", encoding="utf-8") as f:
            f.write(kotlin_code)
        print(f"Successfully generated {len(layouts)} keymaps in single file '{out_dir}/GeneratedKeymaps.kt'.")
    else:
        print("Warning: No valid layouts processed.", file=sys.stderr)

if __name__ == "__main__":
    main()
