# Pinned US console map with USB HID F24 (Linux keycode 194) mapped to the
# ESC [ 24 ~ terminal sequence consumed by the Readline fixture.
{ runCommandNoCC, gzip, kbd }:
runCommandNoCC "sleepwalker-console-keymap" {
  nativeBuildInputs = [ gzip ];
} ''
  mkdir -p "$out/share/keymaps"
  gzip -dc ${kbd}/share/keymaps/i386/qwerty/us.map.gz \
    > "$out/share/keymaps/sleepwalker.map"
  printf '\n# USB HID F24 is Linux input keycode 194.\nkeycode 194 = F12\n' \
    >> "$out/share/keymaps/sleepwalker.map"
''
