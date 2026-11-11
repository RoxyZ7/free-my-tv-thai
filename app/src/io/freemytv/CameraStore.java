package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CameraStore {
    private static final String KEY = "local-camera-v1";
    private static final String LIBRARY = "camera-library";
    private static final String LEGACY = "stream";
    private static final int MAX_CAMERAS = 12;
    private static final int MAX_BYTES = 256 * 1024;
    private final SharedPreferences prefs;

    static final class SavedCamera {
        final String id, name;
        final URI stream;

        SavedCamera(String id, String name, URI stream) {
            this.id = id;
            this.name = name;
            this.stream = stream;
        }
    }

    static final class Library {
        final List<SavedCamera> cameras = new ArrayList<>();
        String active = "";

        SavedCamera selected() {
            for (SavedCamera camera : cameras) {
                if (camera.id.equals(active)) return camera;
            }
            return null;
        }
    }

    CameraStore(Context context) {
        prefs = context.getSharedPreferences("local-camera", Context.MODE_PRIVATE);
    }

    boolean hasCamera() { return read() != null; }

    URI read() {
        try {
            SavedCamera selected = load().selected();
            return selected == null ? null : selected.stream;
        } catch (Exception ignored) {
            return null;
        }
    }

    Library list() throws Exception { return load(); }

    boolean save(URI stream) {
        if (CameraPolicy.endpoint(stream.toASCIIString(), "rtsp") == null) return false;
        try {
            Library library = load();
            for (SavedCamera camera : library.cameras) {
                if (camera.stream.equals(stream)) return select(library, camera.id);
            }
            if (library.cameras.size() >= MAX_CAMERAS) return false;
            String id = UUID.randomUUID().toString();
            library.cameras.add(new SavedCamera(id, "", stream));
            return select(library, id);
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean select(String id) {
        try {
            return select(load(), id);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean select(Library library, String id) throws Exception {
        library.active = id;
        return library.selected() != null && write(library);
    }

    boolean remove() {
        try {
            Library library = load();
            library.active = "";
            return write(library);
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean delete(String id) {
        try {
            Library library = load();
            if (!library.cameras.removeIf(camera -> camera.id.equals(id))) return false;
            if (id.equals(library.active)) library.active = "";
            return write(library);
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean rename(String id, String name) {
        if (name.length() > 48) return false;
        try {
            Library library = load();
            for (int i = 0; i < library.cameras.size(); i++) {
                SavedCamera camera = library.cameras.get(i);
                if (!camera.id.equals(id)) continue;
                library.cameras.set(i, new SavedCamera(id, name.trim(), camera.stream));
                return write(library);
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Library load() throws Exception {
        Library library = new Library();
        String value = prefs.getString(LIBRARY, "");
        if (value.isEmpty()) {
            String legacy = prefs.getString(LEGACY, "");
            if (!legacy.isEmpty()) {
                URI uri = CameraPolicy.endpoint(decrypt(legacy), "rtsp");
                if (uri == null) throw new IllegalStateException("Invalid saved camera");
                library.active = "first-camera";
                library.cameras.add(new SavedCamera(library.active, "", uri));
            }
            return library;
        }
        JSONObject saved = new JSONObject(decrypt(value));
        JSONArray cameras = saved.getJSONArray("cameras");
        if (cameras.length() > MAX_CAMERAS) throw new IllegalStateException("Too many saved cameras");
        library.active = saved.getString("active");
        for (int i = 0; i < cameras.length(); i++) {
            JSONObject camera = cameras.getJSONObject(i);
            URI stream = CameraPolicy.endpoint(camera.getString("stream"), "rtsp");
            if (stream == null) throw new IllegalStateException("Invalid saved camera");
            library.cameras.add(new SavedCamera(camera.getString("id"), camera.getString("name"), stream));
        }
        return library;
    }

    private boolean write(Library library) throws Exception {
        JSONArray cameras = new JSONArray();
        for (SavedCamera camera : library.cameras) {
            cameras.put(new JSONObject().put("id", camera.id).put("name", camera.name)
                    .put("stream", camera.stream.toASCIIString()));
        }
        String json = new JSONObject().put("active", library.active).put("cameras", cameras).toString();
        return prefs.edit().putString(LIBRARY, encrypt(json)).remove(LEGACY).commit();
    }

    private static String decrypt(String value) throws Exception {
        if (value.length() > 4 * MAX_BYTES / 3 + 4) throw new IllegalStateException("Oversized camera library");
        byte[] data = Base64.decode(value, Base64.NO_WRAP);
        if (data.length < 29 || data.length > MAX_BYTES) throw new IllegalStateException("Invalid camera library");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, data, 0, 12));
        return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IllegalStateException("Invalid camera cipher nonce");
        byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        if (iv.length + sealed.length > MAX_BYTES) throw new IllegalStateException("Oversized camera library");
        byte[] data = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, data, 0, iv.length);
        System.arraycopy(sealed, 0, data, iv.length, sealed.length);
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY, null);
        if (key != null || !create) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
