## 1. Diagnose Current Composite Evidence

- [x] 1.1 Inspect latest keyboard and mouse smoke artifacts to identify whether failures are observer-matching, evdev-decoding, command-path, or firmware-report issues
- [x] 1.2 Record matched observer devices, raw event codes, symbolic names, and sequence IDs from existing artifacts for comparison
- [x] 1.3 Confirm whether the running HIL path invokes the project-built observer helper or a stale observer ISO/helper binary

## 2. Observer Helper Stabilization

- [x] 2.1 Add or repair evdev symbolic-name decoding for `KEY_SPACE`, `BTN_LEFT`, `REL_X`, `REL_Y`, `REL_WHEEL`, and `SYN_REPORT`
- [x] 2.2 Preserve numeric type/code/value fields in observer JSONL while emitting stable symbolic names
- [x] 2.3 Update Sleepwalker device discovery to classify keyboard-capable and mouse-capable evdev nodes by descriptor/capability evidence instead of `/dev/input/eventX` assumptions
- [x] 2.4 Allow the same matched event device to satisfy both keyboard and mouse roles when the composite descriptor exposes a combined node
- [x] 2.5 Report structured device-found events containing matched device identity, role classification, and helper version/path
- [x] 2.6 Ensure exclusive grab is applied to every matched Sleepwalker event node used during observation

## 3. Composite HIL Smoke

- [x] 3.1 Add a composite HID smoke operation that starts one observer session for keyboard and mouse evidence
- [x] 3.2 Send keyboard `USB_KEY_SPACE` through the existing public app/library command path during the composite smoke
- [x] 3.3 Send relative mouse left click and movement through the existing public app/library command path during the composite smoke
- [x] 3.4 Parse observer JSONL for `EV_KEY KEY_SPACE 1`, `EV_KEY KEY_SPACE 0`, `EV_KEY BTN_LEFT 1`, `EV_KEY BTN_LEFT 0`, and `EV_REL REL_X` or `EV_REL REL_Y`
- [x] 3.5 Correlate Android diagnostics, ESP UART diagnostics, HID observer events, and summary evidence by command sequence identifiers
- [x] 3.6 Write a composite smoke summary with separate keyboard, mouse, observer-device, and correlation sections

## 4. Conditional Firmware Correction

- [x] 4.1 Determine from UART and observer evidence whether keyboard failure is caused by firmware HID descriptor/report behavior
- [x] 4.2 If firmware is implicated, adjust HID descriptor/report behavior so keyboard and relative mouse reports are both observable from the same firmware build
- [x] 4.3 If firmware is not implicated, leave firmware behavior unchanged and document the observer/HIL root cause in artifacts
- [x] 4.4 Preserve existing protocol frame, opcode, and public library API behavior unchanged

## 5. Final Verification

- [x] 5.1 Run observer helper/unit checks for keyboard, mouse button, relative-axis, and sync symbolic decoding
- [x] 5.2 Run the standalone keyboard smoke and verify it reports `KEY_SPACE` down/up with symbolic observer events
- [x] 5.3 Run the standalone mouse smoke and verify it reports `BTN_LEFT` down/up and relative movement with symbolic observer events
- [x] 5.4 Run the combined composite HID smoke and verify keyboard, mouse, observer-device, and correlation evidence all pass
- [x] 5.5 Verify all smoke artifact summaries contain enough structured evidence to identify the failing layer if a future keyboard or mouse check fails
