# Compatibility and test evidence

## Supported design

- Android app written in Java and Rust, package io.freemytv. Version 2.1 / code 3, minSdk 28 (Android 9), targetSdk 34.
- The Rust blur library is bundled for arm64-v8a, armeabi-v7a, x86, and x86_64. Java handles the blur if the library cannot load or the input is outside its supported range. The bundled Windows x64 build tools run on the computer.
- Android 9–11 retain the upstream Ui.LEGACY drawing path: avoid expensive hardware layers, use cached shadows/bitmap rounding, and use the legacy Recording fallback rather than unguarded RenderNode calls.
- Android 9–12 retain non-AGSL backgrounds. Android 11 keeps its separate screensaver process variant. Thai fonts and symbols are stored at normalized forward-slash asset paths.
- Stock Home/setup/saved-Wi-Fi handling, remote shortcuts and ADB permissions vary by firmware. The dedicated shortcut interception inherited from upstream is TCL-specific; do not promise it remaps Xiaomi shortcut buttons.
- Streaming boxes have HDMI output, not necessarily HDMI input or a tuner. Input/Live TV availability depends on installed TV services.

## Hardware status

| Device | Status |
| --- | --- |
| Xiaomi TV Box S 3rd Gen, MiTV-AFMU0, Android 14 | Main target. A pre-release build of the 2.1 feature set was installed as the active Home. Thai settings, a seven-app shelf, the C24S H.264 camera stream, and Rust image processing were checked on this model. |
| Xiaomi Mi Box S 2nd Gen, Android 11 | Upstream-style legacy/screensaver paths retained; not hardware-tested here with 2.1. |
| Xiaomi Mi Box S / Mi Box 3 with Android 9 | API floor and older GPU fallbacks retained; not hardware-tested here with 2.1. |
| Mi Box 3 still running Android 6/8 | Unsupported by minSdk 28. Do not bypass Android's minimum version check or flash unofficial firmware as part of this installer. |
| Other Android TV / Google TV 9+ | Source may run; Xiaomi-specific cleanup is gated off on other manufacturers. No blanket compatibility claim. |
| Samsung Tizen / LG webOS / Fire TV | Not supported by this installer. |

The device checks above used the feature build before the version was bumped to 2.1. The final 2.1 build was checked with the bundled tools. A controlled reboot and the full hardware checklist remain unverified.

## Checks shipped with this repository

setup.cmd --self-test exercises config escaping/validation, no-key defaults, installed-app selection/order/count, ambiguous/offline devices, device-bound recovery, exact enabled-state restoration and partial failures, raw APK ZIP paths, Thai/English/version/credit configuration, and pure Java shelf/ad/recovery policies. setup.cmd --build-only compiles against API 34 and verifies the APK signature using the included tools.

## Hardware acceptance checklist

For each exact model/firmware, record the Android API, selected language, and before/after results privately. Test: first-run selection; 1/7/8/15-app page navigation; back/up/down and accessibility; Thai marks; all app launches; day/night styling; weather and no-key mode; own-key recommendations; icons; screen saver start/exit; Wi-Fi; Bluetooth pairing; Assistant/mic; phone remote; casting; audio/HDR/video playback; standby/wake; authorized full reboot; and Original recovery preserving pre-disabled packages. Do not publish credentials or unique device IDs with a report.

Some older Mi Box S firmware changes the USB port into debugging/device mode while USB debugging is enabled, which can cut power to attached drives/speakers. Disabling debugging may also remove the app's local ADB ability to switch/restore modes. Plan recovery first and test the specific firmware rather than assuming all Mi Box generations behave the same.

## Local camera preview

Discovery uses ONVIF Media on the same private IPv4 subnet and supports
ONVIF UsernameToken authentication. A manual RTSP address is available
for cameras with different discovery or authentication requirements.
VStarcam TCP stream URLs use the bundled Media3 player with a camera-bound
socket and a compatibility fix for trailing whitespace in SDP. Other streams
use the Android firmware player, which controls RTSP redirects and its own
logging. Build and policy checks do not establish camera playback on any
particular firmware.
