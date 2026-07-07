## 1. UI Layout Modification

- [x] 1.1 Add required imports (`android.widget.Spinner`, `android.widget.ArrayAdapter`, `android.view.View`, `android.widget.AdapterView`) to `MainActivity.kt`
- [x] 1.2 Declare a private member variable `selectedProfile: HostProfile` in `MainActivity` initialized to `HostProfile.LINUX_US` (or default layout)
- [x] 1.3 Replace the static "Profile: US QWERTY seed" `TextView` with a label and a programmatic `Spinner` in `MainActivity.onCreate`
- [x] 1.4 Populate the `Spinner` dynamically using `GeneratedKeymapDatabase.profiles.map { it.key }` and set an item selection listener to update `selectedProfile`

## 2. Text Streaming Integration

- [x] 2.1 Refactor the `streamText` function in `MainActivity` to plan text using `selectedProfile` instead of hardcoded `HostProfile.LINUX_US`

## 3. Verification

- [x] 3.1 Run Gradle unit tests to ensure no regressions or compilation errors in the app module
- [x] 3.2 Build and install the updated APK on the connected Android device
