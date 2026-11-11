package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CameraSettingsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> request;
    private int generation;
    private CameraStore store;
    private TextView status;
    private AlertDialog dialog;
    private CameraPreview preview;

    static String label(String thai, String english) { return Ui.th ? thai : english; }
    static String title() { return label("กล้องวงจรปิด", "Security camera"); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new CameraStore(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(40), dp(24), dp(40), dp(20));
        TextView heading = new TextView(this);
        heading.setText(title());
        heading.setTextSize(26);
        page.addView(heading);
        status = new TextView(this);
        status.setPadding(0, dp(10), 0, dp(12));
        page.addView(status);
        Button scan = button(page, label("ค้นหากล้อง ONVIF", "Find ONVIF cameras"), this::scan);
        button(page, label("ใส่ URL ของ RTSP", "Enter RTSP URL"), this::manual);
        button(page, label("นำกล้องออกจากหน้าแรก", "Remove camera from Home"), this::remove);
        button(page, label("กลับ", "Back"), this::finish);
        setContentView(page);
        ready();
        scan.requestFocus();
    }

    private Button button(LinearLayout page, String text, Runnable run) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> run.run());
        page.addView(button, new LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private void ready() {
        status.setText(store.hasCamera() ? label("ตั้งค่ากล้องแล้ว", "Camera configured")
                : label("ยังไม่ได้เลือกกล้อง", "No camera selected"));
    }

    private void scan() {
        CameraLan lan = CameraLan.current(this);
        if (lan == null) { offline(); return; }
        OnvifDiscovery discovery = new OnvifDiscovery(lan);
        run(label("กำลังค้นหากล้อง…", "Looking for cameras…"), () -> discovery.scan(this), cameras -> {
            if (cameras.isEmpty()) {
                status.setText(label("ไม่พบกล้อง ONVIF ลองใส่ URL ของ RTSP", "No ONVIF cameras found. Try an RTSP URL."));
                return;
            }
            String[] names = new String[cameras.size()];
            for (int i = 0; i < names.length; i++) names[i] = (i + 1) + ". " + cameras.get(i).name;
            dialog = new AlertDialog.Builder(this).setTitle(label("เลือกกล้อง", "Choose camera"))
                    .setItems(names, (d, i) -> profiles(discovery, cameras.get(i), "", ""))
                    .setNegativeButton(android.R.string.cancel, null).show();
            ready();
        }, null);
    }

    private void profiles(OnvifDiscovery discovery, OnvifDiscovery.Camera camera, String user, String password) {
        run(label("กำลังอ่านรายการสตรีม…", "Reading camera streams…"),
                () -> discovery.profiles(camera, user, password), profiles -> {
                    if (profiles.isEmpty()) {
                        status.setText(label("ไม่พบสตรีม H.264 ลองใส่ URL ของ RTSP", "No H.264 stream found. Try an RTSP URL."));
                        return;
                    }
                    pickProfile(discovery, camera, profiles, user, password);
                }, () -> credentials(discovery, camera));
    }

    private void pickProfile(OnvifDiscovery discovery, OnvifDiscovery.Camera camera,
                             List<OnvifDiscovery.Profile> profiles, String user, String password) {
        String[] names = new String[profiles.size()];
        for (int i = 0; i < names.length; i++) names[i] = profiles.get(i).name;
        ready();
        dialog = new AlertDialog.Builder(this).setTitle(label("เลือกสตรีม H.264", "Choose H.264 stream"))
                .setItems(names, (d, i) -> run(label("กำลังอ่านที่อยู่สตรีม…", "Getting stream address…"),
                        () -> discovery.stream(profiles.get(i), user, password), this::confirm,
                        () -> credentials(discovery, camera)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void credentials(OnvifDiscovery discovery, OnvifDiscovery.Camera camera) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), 0, dp(24), 0);
        EditText user = field(label("ชื่อผู้ใช้", "Username"), InputType.TYPE_TEXT_VARIATION_NORMAL, 128);
        EditText password = field(label("รหัสผ่าน", "Password"), InputType.TYPE_TEXT_VARIATION_PASSWORD, 256);
        fields.addView(user);
        fields.addView(password);
        dialog = new AlertDialog.Builder(this).setTitle(label("ข้อมูลเข้าสู่ระบบของกล้อง", "Camera credentials"))
                .setView(fields).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label("เชื่อมต่อ", "Connect"), (d, which) ->
                        profiles(discovery, camera, user.getText().toString(), password.getText().toString())).show();
        protectDialog();
        ready();
    }

    private EditText field(String hint, int variation, int max) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | variation | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(max)});
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSaveEnabled(false);
        return input;
    }

    private void manual() {
        cancelWork();
        EditText input = field("rtsp://…", InputType.TYPE_TEXT_VARIATION_PASSWORD, 4096);
        dialog = new AlertDialog.Builder(this).setTitle(label("ใส่ URL ของ RTSP", "Enter RTSP URL"))
                .setView(input).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label("ดูตัวอย่าง", "Preview"), (d, which) -> {
                    URI uri = CameraPolicy.endpoint(input.getText().toString(), "rtsp");
                    CameraLan lan = CameraLan.current(this);
                    if (lan != null && lan.address(uri) != null) confirm(uri);
                    else status.setText(label("ใช้ URL ของ RTSP ที่เป็น IP ในวงแลนเดียวกัน",
                            "Enter an RTSP URL with an IP on the same LAN."));
                }).show();
        protectDialog();
    }

    private void confirm(URI uri) {
        FrameLayout frame = new FrameLayout(this);
        frame.setMinimumHeight(dp(190));
        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        CameraPreview player = new CameraPreview(this, () -> updatePreview(message));
        preview = player;
        frame.addView(player, new FrameLayout.LayoutParams(-1, dp(190)));
        frame.addView(message, new FrameLayout.LayoutParams(-1, dp(190)));
        dialog = new AlertDialog.Builder(this).setTitle(label("ดูตัวอย่างกล้อง", "Camera preview"))
                .setView(frame).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label("ใช้กล้องนี้", "Use this camera"), (d, which) ->
                        run(label("กำลังบันทึก…", "Saving…"), () -> store.save(uri), saved -> {
                            if (saved) finish(); else offline();
                        }, null)).show();
        protectDialog();
        dialog.setOnDismissListener(d -> {
            player.stop();
            if (preview == player) preview = null;
        });
        player.showStream(uri);
        updatePreview(message);
        ready();
    }

    private void protectDialog() {
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void updatePreview(TextView message) {
        if (preview == null) return;
        CameraPreview.State state = preview.state();
        message.setText(state == CameraPreview.State.LIVE ? "" : state == CameraPreview.State.OFFLINE
                ? label("เปิดสตรีมไม่ได้", "Unable to play this stream")
                : label("กำลังเชื่อมต่อ…", "Connecting…"));
    }

    private void remove() {
        cancelWork();
        dialog = new AlertDialog.Builder(this).setTitle(label("นำกล้องออกจากหน้าแรกหรือไม่", "Remove camera from Home?"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label("นำออก", "Remove"), (d, which) ->
                        run(label("กำลังนำออก…", "Removing…"), store::remove, removed -> {
                            if (removed) ready(); else offline();
                        }, null)).show();
    }

    private void offline() { status.setText(label("เชื่อมต่อหรือบันทึกกล้องไม่ได้", "Unable to connect or save the camera.")); }

    private <T> void run(String working, Callable<T> action, java.util.function.Consumer<T> success, Runnable auth) {
        cancelWork();
        int ticket = generation;
        status.setText(working);
        request = worker.submit(() -> {
            try {
                T result = action.call();
                deliver(ticket, () -> success.accept(result));
            } catch (SecurityException ignored) {
                deliver(ticket, auth == null ? this::offline : auth);
            } catch (Exception ignored) {
                deliver(ticket, this::offline);
            }
        });
    }

    private void deliver(int ticket, Runnable action) {
        runOnUiThread(() -> { if (ticket == generation && !isFinishing() && !isDestroyed()) action.run(); });
    }

    private void cancelWork() {
        generation++;
        if (request != null) request.cancel(true);
        request = null;
    }

    @Override protected void onPause() {
        super.onPause();
        cancelWork();
        if (dialog != null) dialog.dismiss();
        if (preview != null) preview.stop();
        ready();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
