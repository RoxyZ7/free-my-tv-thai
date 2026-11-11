package io.freemytv;

import java.net.URI;

final class CameraPolicy {
    static float weatherWidth(float available, float gutter) {
        return Math.max(0, available - gutter) * 0.4f;
    }

    static boolean play(boolean resumed, boolean drawer, boolean covered, boolean selected) {
        return resumed && drawer && !covered && selected;
    }

    static URI endpoint(String value, String scheme) {
        if (value == null || value.length() > 4096) return null;
        try {
            URI uri = new URI(value.trim());
            if (!scheme.equalsIgnoreCase(uri.getScheme()) || !privateV4(uri.getHost())
                    || uri.toASCIIString().length() > 4096 || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || ("http".equals(scheme) && uri.getRawUserInfo() != null)) return null;
            return uri;
        } catch (IllegalArgumentException | java.net.URISyntaxException ignored) {
            return null;
        }
    }

    private static boolean privateV4(String host) {
        if (host == null || !host.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = host.split("\\.");
        for (String part : parts) if (Integer.parseInt(part) > 255) return false;
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }
}
