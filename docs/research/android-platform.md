# Research notes: Android platform (targetSdk 36), checked 2026-09-30

Sources: developer.android.com reference and guides, AOSP `main` where docs are silent (marked [AOSP]).

## Surprises

1. `ActivityManager.isActivityStartAllowedOnDisplay` is API 29 (not 26). `ActivityOptions.setLaunchDisplayId` is API 26.
2. `ActivityOptions.makeCustomAnimation` is ignored for launches into another app's task (system default animation is used
   cross-task). `makeScaleUpAnimation` and `makeClipRevealAnimation` are honored cross-task [AOSP DefaultTransitionHandler].
3. `Presentation` is not deprecated; Android 16 allows it on built-in internal displays. Rules can throw
   `WindowManager.InvalidDisplayException`. For dual-screen handhelds (mostly Android 13-15) prefer a separate Activity
   launched with `setLaunchDisplayId`, and use `Presentation` only on displays with `FLAG_PRESENTATION`.
4. `androidx.security:security-crypto` is deprecated (1.1.0, 2025-07-30). Use Android Keystore AES-256-GCM directly.
5. A HOME activity without LAUNCHER gets finished by default Back; Android 16 with targetSdk 36 uses predictive back and
   no longer calls `onBackPressed`. Always register an enabled root `OnBackPressedCallback` in the home UI.
6. Being HOME gives no package visibility: `LauncherApps` results and callbacks are filtered [AOSP]. Declare `<queries>`.
7. SAF cannot grant the internal storage root, SD roots, `Download/` or `Android/data|obb` (Android 11+).
8. Android 15+ refuses APKs with targetSdk < 24.
9. Android developer verification rollout started 2026-09-30 (regional), global on certified devices in 2027.
10. targetSdk 36 on sw >= 600dp ignores orientation/resizability limits except for `android:appCategory="game"`.

## HOME role

- `RoleManager.ROLE_HOME` (API 29): `isRoleAvailable`, `isRoleHeld`, `createRequestRoleIntent`. Needs an enabled
  MAIN+HOME activity; a disabled alias auto-cancels. After a denial, the next dialog shows "Don't ask again". Never loop.
  Fallbacks: `Settings.ACTION_HOME_SETTINGS`, `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (wrap in try/catch).
- Manifest: `singleTask`, `clearTaskOnLaunch`, `stateNotNeeded`, `resumeWhilePausing`, `taskAffinity=""` (Launcher3).
  Keep a separate `<activity-alias>` with MAIN+HOME+DEFAULT, `enabled="false"`, toggled with
  `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)`. The alias class name uses the manifest namespace.
- Don't set `excludeFromRecents` (it would hide Fuse in normal-app mode).
- Home press while home arrives in `onNewIntent` with MAIN+HOME.

## Package visibility

- No launcher exception. Use `<queries>` with MAIN/LAUNCHER intent (covers emulators) plus explicit `<package>` entries.
- `LauncherApps.getActivityList(null, user)`, `LauncherApps.Callback` (5 abstract methods), `startMainActivity`,
  `startAppDetailsActivity`. Manifest receivers for PACKAGE_ADDED don't fire; re-scan on cold start
  (`getChangedPackages(seq)`).
- `QUERY_ALL_PACKAGES`: Play restricts it; not needed with `<queries>`.

## Storage

- SAF: `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`. List with one `DocumentsContract` query per
  directory (never `DocumentFile.listFiles()` + getters).
- All files access: `MANAGE_EXTERNAL_STORAGE`, `Environment.isExternalStorageManager()`,
  `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` with `package:`. Needed to hand emulators FileProvider URIs
  (ES-DE: "you can't provide access to files you don't own"). Fine for GitHub/F-Droid distribution.
- `FileObserver(File)` (API 29) needs path access, not recursive, hold a strong reference, treat as a hint.
- `file://` URIs throw `FileUriExposedException`; raw path String extras are fine.
- Android 16 "Safer Intents": when launching by explicit component, always set an action and data matching the filter.

## Secondary displays

- `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)`, `registerDisplayListener`, `Display.isInternal()` (36.1).
- `ActivityOptions.makeBasic().setLaunchDisplayId(id)`; check `isActivityStartAllowedOnDisplay` (29).
- Removed display: its activities move to the primary display; presentations are dismissed.
- Compose inside a Presentation needs ViewTree lifecycle and saved-state owners set before `show()`.

## Status

- Battery: sticky `ACTION_BATTERY_CHANGED`, `EXTRA_LEVEL/SCALE/STATUS/PLUGGED/TEMPERATURE`.
- Wi-Fi: `ConnectivityManager.registerNetworkCallback` (ACCESS_NETWORK_STATE); signal via `WifiManager.calculateSignalLevel`.
- Bluetooth: `BluetoothAdapter.isEnabled/getState` and `ACTION_STATE_CHANGED` need no runtime permission at targetSdk 36
  (legacy `BLUETOOTH` with maxSdkVersion 30). Register with `RECEIVER_EXPORTED`.
- Panels: `Settings.Panel.ACTION_WIFI`, `ACTION_INTERNET_CONNECTIVITY`, `ACTION_VOLUME` (not deprecated; OEM varies).
- Brightness: `WindowManager.LayoutParams.screenBrightness` (own window only). Global needs `WRITE_SETTINGS`.
- Volume: `AudioManager.adjustStreamVolume` needs no permission.

## Controllers

- Keycodes BUTTON_A 96, B 97, X 99, Y 100, L1 102, R1 103, L2 104, R2 105, THUMBL 106, THUMBR 107, START 108,
  SELECT 109, MODE 110, DPAD 19-23. BUTTON_A is always the south button by position.
- Axes: X/Y, Z/RZ, HAT_X/HAT_Y, LTRIGGER/RTRIGGER (+BRAKE/GAS). Treat hat and D-pad keys as the same input.
- `InputManager.registerInputDeviceListener`, `InputDevice.isExternal()` (29), rumble via `InputDevice.vibratorManager`.

## Performance info

- `ActivityManager.getMemoryInfo`, `isLowRamDevice`; `PowerManager.getCurrentThermalStatus` (29),
  `getThermalHeadroom` (30, <= 1/s). `/proc/stat` is denied since Android 8. `HardwarePropertiesManager` is
  effectively unavailable. There is no public API for another app's FPS.
- `Build.VERSION.MEDIA_PERFORMANCE_CLASS` (31), often 0 on handhelds.

## Installing APKs

- `REQUEST_INSTALL_PACKAGES`, `canRequestPackageInstalls()`, `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
- Prefer a `PackageInstaller` session; handle `STATUS_PENDING_USER_ACTION` by starting `EXTRA_INTENT`.

## Transitions into another app

- `ActivityOptions.makeClipRevealAnimation(view, x, y, w, h)` or `makeScaleUpAnimation` from the tile's bounds.
- `overrideActivityTransition` (34) only within Fuse's own task.
