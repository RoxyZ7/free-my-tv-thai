package io.freemytv;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** Pure policies shared by the weather settings UI, refresh scheduler, and tests. */
final class WeatherPolicy {
    private static final int[] REFRESH_MINUTES = {15, 30, 60};
    private WeatherPolicy() {}

    static int normalizeRefreshMinutes(int minutes) {
        for (int value : REFRESH_MINUTES) if (value == minutes) return value;
        return 30;
    }

    static int nextRefreshMinutes(int current, int direction) {
        int normalized = normalizeRefreshMinutes(current);
        if (direction == 0) return normalized;
        int at = normalized == 15 ? 0 : normalized == 60 ? 2 : 1;
        return REFRESH_MINUTES[Math.floorMod(at + (direction > 0 ? 1 : -1), REFRESH_MINUTES.length)];
    }

    static long refreshMillis(int minutes) {
        return normalizeRefreshMinutes(minutes) * 60_000L;
    }

    static boolean validCoordinates(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude) && latitude >= -90 && latitude <= 90
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude) && longitude >= -180 && longitude <= 180;
    }

    static boolean cacheMatches(String requestedPlace, String cachedPlace) {
        return requestedPlace != null && requestedPlace.equals(cachedPlace);
    }

    static String geocodingUrl(String query, String language) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter a city or province.");
        String lang = "th".equals(language) || "en".equals(language) || "es".equals(language) || "pt".equals(language)
                ? language : "en";
        try {
            return "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(value, "UTF-8")
                    + "&count=5&language=" + lang + "&format=json";
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
