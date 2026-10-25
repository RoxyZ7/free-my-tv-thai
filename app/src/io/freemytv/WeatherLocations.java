package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Open-Meteo geocoding results for the on-TV weather location picker. */
final class WeatherLocations {
    private WeatherLocations() {}

    static final class Place {
        final String label;
        final double latitude, longitude;
        Place(String label, double latitude, double longitude) {
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static List<Place> search(String query) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(WeatherPolicy.geocodingUrl(query, language())).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            String body;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                body = output.toString(StandardCharsets.UTF_8.name());
            }
            JSONArray results = new JSONObject(body).optJSONArray("results");
            List<Place> places = new ArrayList<>();
            if (results == null) return places;
            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.optJSONObject(i);
                if (result == null) continue;
                double latitude = result.optDouble("latitude", Double.NaN);
                double longitude = result.optDouble("longitude", Double.NaN);
                String name = result.optString("name", "").trim();
                if (name.isEmpty() || !WeatherPolicy.validCoordinates(latitude, longitude)) continue;
                String admin = result.optString("admin1", "").trim();
                String country = result.optString("country", "").trim();
                StringBuilder label = new StringBuilder(name);
                if (!admin.isEmpty() && !admin.equalsIgnoreCase(name)) label.append(", ").append(admin);
                if (!country.isEmpty()) label.append(", ").append(country);
                places.add(new Place(label.toString(), latitude, longitude));
            }
            return places;
        } finally {
            connection.disconnect();
        }
    }

    private static String language() {
        return Ui.th ? "th" : Ui.en ? "en" : Ui.es ? "es" : "pt";
    }
}
