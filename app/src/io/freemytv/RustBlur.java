package io.freemytv;

final class RustBlur {
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("freemytv_blur");
            loaded = true;
        } catch (LinkageError | SecurityException ignored) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private RustBlur() { }

    static boolean apply(int[] pixels, int[] scratch, int width, int height, int radius, int passes) {
        if (!AVAILABLE) return false;
        try {
            return blurNative(pixels, scratch, width, height, radius, passes);
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private static native boolean blurNative(int[] pixels, int[] scratch, int width, int height, int radius, int passes);
}
