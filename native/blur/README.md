# Rust background image processing

The JNI entry point processes both passes on the two Java arrays. It uses an
integer moving average with repeated edge pixels, matching the Java result
for radii 2 and 3. A second JNI entry point builds the day and night versions
of the background together. It computes a 256-entry color table once per
image and can reuse the source buffer for the night version when the caller
does not need the original pixels. The app uses Java when native loading or
input checks fail.

The APK build consumes the checked-in, SHA-256-verified bundle. End users do
not need Rust, Cargo, or the Android NDK. To rebuild it, install the Rust
targets aarch64-linux-android, armv7-linux-androideabi, i686-linux-android, and
x86_64-linux-android, then run python tools/build_rust_blur.py. The script
uses rust-lld supplied by the Rust toolchain. The bundle manifest records
the compiler version and source digest.
