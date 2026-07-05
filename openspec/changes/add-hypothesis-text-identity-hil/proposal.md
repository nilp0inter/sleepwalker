## Why

The current high-level text smoke proves one fixed string (`aA1`) produces expected evdev key events, but it does not prove the product invariant users need: generated supported text sent through the real Android -> BLE -> ESP32-S3 -> USB HID path renders as the same text on the target computer.

Now that the bench has autonomous ADB, BLE, firmware, native USB HID, SSH, and structured artifacts, Sleepwalker can use property-based E2E testing to find keyboard-layout and text-path bugs before the keyboard library grows beyond the seed Linux US profile.

## What Changes

- Add a Hypothesis-driven HIL text identity scenario for the direct ADB `type-text` path.
- Configure the observer host as a Linux console text target for the first identity backend: active VT, Linux console keymap, raw-mode capture sink, and SSH-readable output.
- Start with `linux:us` and printable ASCII supported by the current seed keymap; exclude control keys such as ESC from the text identity alphabet.
- Run the evdev observer without exclusive grab during text identity tests so the Linux console receives the injected HID input, while evdev logs remain available as diagnostics.
- Add a lossless encoded text input path for ADB-driven text commands so generated strings containing punctuation, spaces, quotes, and shell metacharacters are not corrupted before reaching Android.
- Add structured property-test artifacts containing generated input, Android-received input, captured target output, Hypothesis seed/example data, evdev diagnostics, Android logs, ESP UART logs, and failure classification.
- Add quick and deep execution profiles: a bounded quick profile suitable for end-of-iteration verification and a larger sweep for manual or longer-running validation.
- Preserve existing keyboard, mouse, text, and composite smokes as fixed regression checks.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `agent-operated-hil`: Add a property-based text identity HIL scenario that verifies generated supported strings render identically on the target observer host.
- `hid-observer-nixos-iso`: Add a raw-mode Linux console text sink usable as the target output oracle for identity tests while keeping evdev observation available as a non-grabbing diagnostic side channel.
- `android-ble-companion`: Add a lossless encoded ADB text command input path so property-generated text reaches `sleepwalker-core` unchanged.

## Impact

- Affects HIL orchestration under `nix/`, adding a Hypothesis-based text identity runner and summary/artifact schema.
- Affects observer-host tooling/configuration under `nix/observer-host.nix` and observer helper/tool packaging for a raw-mode console text sink.
- Affects Android ADB command intake under `android/sleepwalker-app/src/main/kotlin/io/sleepwalker/app/adb/AdbCommandReceiver.kt` and the corresponding Nix ADB adapter.
- Adds a test dependency on Hypothesis to the HIL/property-test environment; the production Kotlin library and firmware do not depend on Hypothesis.
- Does not change the protocol frame layout, firmware text/layout awareness boundary, public `sleepwalker-core` API, or existing fixed smoke scenarios.
