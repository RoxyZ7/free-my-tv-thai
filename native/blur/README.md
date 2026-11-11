# Rust background blur

The JNI entry point processes both passes on the two Java arrays. It uses an
integer moving average with repeated edge pixels, matching the Java result
for radii 2 and 3. The app uses Java when native loading or input checks fail.

The APK build consumes the checked-in, SHA-256-verified bundle. End users do
not need Rust, Cargo, or the Android NDK. To rebuild it, install the Rust
targets aarch64-linux-android, armv7-linux-androideabi, i686-linux-android, and
x86_64-linux-android, then run python tools/build_rust_blur.py. The script
uses rust-lld supplied by the Rust toolchain. The bundle manifest records
the compiler version and source digest.
