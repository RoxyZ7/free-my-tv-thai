package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CameraStore {
    private static final String KEY = "local-camera-v1";
    private final SharedPreferences prefs;

    CameraStore(Context context) {
        prefs = context.getSharedPreferences("local-camera", Context.MODE_PRIVATE);
    }

    boolean hasCamera() { return prefs.contains("stream"); }

    URI read() {
        try {
            String value = prefs.getString("stream", "");
            if (value.isEmpty()) return null;
            byte[] data = Base64.decode(value, Base64.NO_WRAP);
            if (data.length < 29 || data.length > 16416) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, data, 0, 12));
            return CameraPolicy.endpoint(new String(cipher.doFinal(data, 12, data.length - 12),
                    StandardCharsets.UTF_8), "rtsp");
        } catch (Exception ignored) {
            return null;
        }
    }

    boolean save(URI stream) {
        if (CameraPolicy.endpoint(stream.toASCIIString(), "rtsp") == null) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV();
            if (iv.length != 12) return false;
            byte[] sealed = cipher.doFinal(stream.toASCIIString().getBytes(StandardCharsets.UTF_8));
            byte[] data = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, data, 0, iv.length);
            System.arraycopy(sealed, 0, data, iv.length, sealed.length);
            return prefs.edit().putString("stream", Base64.encodeToString(data, Base64.NO_WRAP)).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean remove() { return prefs.edit().remove("stream").commit(); }

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
