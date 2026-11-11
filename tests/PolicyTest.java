package io.freemytv;

import java.util.Arrays;
import java.util.List;

public final class PolicyTest {
    public static void main(String[] args) {
        check(CameraPolicy.weatherWidth(1000, 20) == 392 && CameraPolicy.weatherWidth(10, 20) == 0,
                "camera split uses 40 percent after its gutter");
        check(CameraPolicy.play(true, true, false, true)
                && !CameraPolicy.play(false, true, false, true)
                && !CameraPolicy.play(true, false, false, true)
                && !CameraPolicy.play(true, true, true, true)
                && !CameraPolicy.play(true, true, false, false), "camera only plays in the visible selected drawer");
        check(CameraPolicy.endpoint("rtsp://192.168.0.2:554/live", "rtsp") != null
                && CameraPolicy.endpoint("rtsp://example.test/live", "rtsp") == null
                && CameraPolicy.endpoint("rtsp://203.0.113.10/live", "rtsp") == null
                && CameraPolicy.endpoint("rtsp://127.0.0.1/live", "rtsp") == null
                && CameraPolicy.endpoint("rtsp://192.168.0.2:65536/live", "rtsp") == null
                && CameraPolicy.endpoint("http://example@192.168.0.2/onvif", "http") == null,
                "camera addresses must be numeric private IPv4 with a valid port");
        check(ShelfPolicy.packages(" a.one,b.two,a.one,invalid, ").size() == 2, "ordered deduplication");
        check(ShelfPolicy.start(0) == 0 && ShelfPolicy.start(6) == 0, "first seven");
        check(ShelfPolicy.start(7) == 7 && ShelfPolicy.start(14) == 14, "page boundaries");
        check(ShelfPolicy.visibleCount(7, 8) == 1 && ShelfPolicy.visibleCount(7, 14) == 7, "partial pages");
        check(ShelfPolicy.accessible(7, 0, 15), "next page reachable to TalkBack");
        check(ShelfPolicy.accessible(6, 7, 15), "previous page reachable to TalkBack");
        check(!ShelfPolicy.accessible(14, 0, 15), "only adjacent page edge exposed");
        check(ShelfPolicy.move(Arrays.asList("a.one", "b.two", "c.three"), "a.one", 1)
                .equals(Arrays.asList("b.two", "a.one", "c.three")), "remote reorder changes the selected app position");
        check(ShelfPolicy.move(Arrays.asList("a.one", "b.two"), "a.one", -1)
                .equals(Arrays.asList("a.one", "b.two")), "reorder stops at the first position");
        check(ShelfPolicy.toggle(Arrays.asList("a.one", "b.two"), "a.one")
                .equals(Arrays.asList("b.two")), "remote hide removes an app from the shelf");
        check(ShelfPolicy.toggle(Arrays.asList("a.one"), "b.two")
                .equals(Arrays.asList("a.one", "b.two")), "remote show appends a hidden app");
        check(ShelfPolicy.defaults("a.one,b.two,a.one").equals(Arrays.asList("a.one", "b.two")),
                "restore defaults keeps the original unique app order");
        check(ShelfPolicy.editorRow(0) == 1 && ShelfPolicy.editorRow(6) == 7 && ShelfPolicy.editorRow(-1) == 0,
                "opening the shelf editor focuses the app row, never the restore action by accident");
        check(WeatherPolicy.nextRefreshMinutes(30, 1) == 60
                && WeatherPolicy.nextRefreshMinutes(60, 1) == 15
                && WeatherPolicy.nextRefreshMinutes(30, -1) == 15, "weather refresh interval cycles with the remote");
        check(WeatherPolicy.refreshMillis(15) == 900000L, "weather interval drives the refresh deadline");
        check(WeatherPolicy.geocodingUrl("Lamphun & co", "th").equals(
                "https://geocoding-api.open-meteo.com/v1/search?name=Lamphun+%26+co&count=5&language=th&format=json"),
                "city search safely encodes user text");
        check(WeatherPolicy.validCoordinates(18.58, 99.01) && !WeatherPolicy.validCoordinates(91, 99.01)
                && !WeatherPolicy.validCoordinates(18.58, Double.NaN), "weather location rejects invalid coordinates");
        check(WeatherPolicy.cacheMatches("18.58,99.01", "18.58,99.01")
                && !WeatherPolicy.cacheMatches("18.58,99.01", "13.75,100.50"), "offline forecast is reused only for its saved city");
        check(PackageState.needsRecovery(false, 1, false), "partial activation can retry Original");
        check(PackageState.needsRecovery(false, 0, true), "Home/dream-only recovery can retry");
        check(!PackageState.needsRecovery(false, 0, false), "untouched Original needs no recovery");
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("journal-test").toFile();
            java.io.File target = new java.io.File(dir, "state");
            RecoveryFile.write(target, "baseline".getBytes("UTF-8"));
            check(new String(java.nio.file.Files.readAllBytes(target.toPath()), "UTF-8").equals("baseline"), "journal persisted");
            RecoveryFile.write(target, "progress".getBytes("UTF-8"));
            check(new String(java.nio.file.Files.readAllBytes(target.toPath()), "UTF-8").equals("progress"), "journal atomically replaced");
            check(!PackageState.busy(200000, 1), "interrupted busy state expires");
            check(PackageState.busy(1000, 900), "active operation remains busy");
            boolean failed = false;
            try { RecoveryFile.write(new java.io.File(target, "impossible"), new byte[0]); }
            catch (java.io.IOException expected) { failed = true; }
            check(failed, "journal write failure propagates before mutations");
            target.delete(); dir.delete();
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        check(AdPackages.forDevice("Xiaomi", "Xiaomi").length > 0, "Xiaomi ads available");
        check(AdPackages.forDevice("Sony", "Sony").length == 0, "no Xiaomi policy on other brands");
        for (String pkg : new String[] {"com.google.android.gms", "com.xiaomi.tvqs.overseas.y24",
                "com.xiaomi.android.tvsetup.partnercustomizer", "com.android.bluetooth", "com.google.android.apps.mediashell"}) {
            check(!AdPackages.isAd(pkg), "essential package protected: " + pkg);
        }
        check(!PackageState.shouldDisable(3, false), "predisabled package preserved");
        check(!PackageState.shouldDisable(2, false), "system-disabled package preserved");
        check(PackageState.shouldDisable(0, false), "default enabled can change");
        check(PackageState.restoreCommand(0, "a.b").equals("pm default-state --user 0 a.b"), "default state recovery");
        check(PackageState.restoreCommand(3, "a.b").equals("pm disable-user --user 0 a.b"), "disabled state recovery");
        boolean rejected = false;
        try { PackageState.restoreCommand(0, "bad;reboot"); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "no shell injection");
        check(Thai.condition(65, true).equals("ฝนตกหนัก"), "Thai weather");
        check(Thai.text("On · 24 titles from the TV's apps, 3 new ones every hour.").contains("24 เรื่อง"), "dynamic Thai count");
        System.out.println("PASS Java shelf/ad/recovery policies");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
