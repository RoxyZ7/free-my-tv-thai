package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
        FrameLayout backdrop = new FrameLayout(this) {
            private final Scene scene = new Scene();
            {
                HomeData.Weather weather = HomeData.get(CameraSettingsActivity.this).weather;
                Shaders.sky(scene.palette, System.currentTimeMillis(), weather.sunrise, weather.sunset);
                scene.setMotion(0.5f, 0.2f);
                scene.applyPalette();
                setWillNotDraw(false);
            }

            @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                scene.setSize(w, h);
                scene.advance(0);
            }

            @Override protected void onDraw(Canvas canvas) {
                scene.drawBackground(canvas);
            }
        };
        LinearLayout columns = new LinearLayout(this);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        columns.setPadding(dp(42), dp(24), dp(42), dp(24));

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(0, 0, dp(28), 0);
        View icon = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                Symbols.draw(getContext(), canvas, "live_tv", getWidth() / 2f, getHeight() / 2f,
                        dp(70), 0xEBFFFFFF);
            }
        };
        summary.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(92)));
        TextView heading = new TextView(this);
        heading.setText(title());
        heading.setTypeface(Ui.displaySemibold);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(32);
        heading.setGravity(Gravity.CENTER);
        summary.addView(heading);
        status = new TextView(this);
        status.setTypeface(Ui.medium);
        status.setTextSize(15);
        status.setTextColor(0xC8FFFFFF);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(12), dp(12), 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        summary.addView(status, new LinearLayout.LayoutParams(-1, -2));
        columns.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        Button saved = button(menu, label("กล้องที่บันทึกไว้", "Saved cameras"), this::savedCameras);
        Button scan = button(menu, label("ค้นหากล้อง ONVIF", "Find ONVIF cameras"), this::scan);
        button(menu, label("ใส่ URL ของ RTSP", "Enter RTSP URL"), this::manual);
        button(menu, label("นำกล้องออกจากหน้าแรก", "Remove camera from Home"), this::remove);
        button(menu, label("กลับ", "Back"), this::finish);
        columns.addView(menu, new LinearLayout.LayoutParams(0, -2, 1.02f));
        backdrop.addView(columns, new FrameLayout.LayoutParams(-1, -1));
        setContentView(backdrop);
        ready();
        (store.hasCamera() ? saved : scan).requestFocus();
    }

    private Button button(LinearLayout page, String text, Runnable run) {
        Button button = new Button(this) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Symbols.draw(getContext(), canvas, "chevron_right", getWidth() - dp(18),
                        getHeight() / 2f, dp(12), isFocused() ? 0xA01C1C1E : 0x91FFFFFF);
            }
        };
        button.setText(text);
        button.setTypeface(Ui.semibold);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPaddingRelative(dp(18), 0, dp(36), 0);
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setStateListAnimator(null);
        button.setOnClickListener(v -> run.run());
        button.setOnFocusChangeListener((v, focused) -> styleRow(button, focused));
        styleRow(button, false);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(48));
        row.bottomMargin = dp(5);
        page.addView(button, row);
        return button;
    }

    private void styleRow(Button button, boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(focused ? 0xFFFFFFFF : 0x18FFFFFF);
        button.setBackground(shape);
        button.setTextColor(focused ? 0xFF1C1C1E : 0xF2FFFFFF);
        button.setElevation(focused ? dp(4) : 0);
    }

    private AlertDialog.Builder cameraDialog() {
        return new AlertDialog.Builder(this, R.style.FreeMyTV_CameraDialog);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private void ready() {
        status.setText(store.hasCamera() ? label("ตั้งค่ากล้องแล้ว", "Camera configured")
                : label("ยังไม่ได้เลือกกล้อง", "No camera selected"));
    }

    private void savedCameras() {
        run(label("กำลังโหลดกล้อง…", "Loading cameras…"), store::list, library -> {
            ready();
            if (library.cameras.isEmpty()) {
                status.setText(label("ยังไม่มีกล้องที่บันทึกไว้", "No saved cameras yet"));
                return;
            }
            String[] names = new String[library.cameras.size()];
            for (int i = 0; i < names.length; i++) {
                CameraStore.SavedCamera camera = library.cameras.get(i);
                String name = camera.name.isEmpty() ? label("กล้อง ", "Camera ") + (i + 1) : camera.name;
                names[i] = camera.id.equals(library.active) ? name + label(" (ใช้อยู่)", " (active)") : name;
            }
            dialog = cameraDialog().setTitle(label("กล้องที่บันทึกไว้", "Saved cameras"))
                    .setItems(names, (d, i) -> savedActions(library.cameras.get(i)))
                    .setNegativeButton(android.R.string.cancel, null).show();
        }, null);
    }

    private void savedActions(CameraStore.SavedCamera camera) {
        String[] actions = {label("ดูตัวอย่างและใช้กล้องนี้", "Preview and use this camera"),
                label("เปลี่ยนชื่อ", "Rename"), label("ลบออกจากรายการ", "Delete from saved cameras")};
        dialog = cameraDialog().setTitle(label("จัดการกล้อง", "Manage camera"))
                .setItems(actions, (d, i) -> {
                    if (i == 0) confirm(camera.stream, () -> store.select(camera.id));
                    else if (i == 1) rename(camera);
                    else delete(camera);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void rename(CameraStore.SavedCamera camera) {
        EditText name = field(label("ชื่อกล้อง", "Camera name"), InputType.TYPE_TEXT_VARIATION_NORMAL, 48);
        name.setText(camera.name);
        dialog = cameraDialog().setTitle(label("เปลี่ยนชื่อกล้อง", "Rename camera"))
                .setView(name).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label("บันทึก", "Save"), (d, which) -> {
                    String text = name.getText().toString().trim();
                    run(label("กำลังบันทึก…", "Saving…"), () -> store.rename(camera.id, text),
                            ok -> { if (ok) savedCameras(); else offline(); }, null);
                }).show();
    }

    private void delete(CameraStore.SavedCamera camera) {
        dialog = cameraDialog()
                .setTitle(label("ลบกล้องจากรายการที่บันทึกไว้หรือไม่", "Delete this saved camera?"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label("ลบ", "Delete"), (d, which) ->
                        run(label("กำลังลบ…", "Deleting…"), () -> store.delete(camera.id),
                                ok -> { if (ok) savedCameras(); else offline(); }, null)).show();
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
            dialog = cameraDialog().setTitle(label("เลือกกล้อง", "Choose camera"))
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
        dialog = cameraDialog().setTitle(label("เลือกสตรีม H.264", "Choose H.264 stream"))
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
        dialog = cameraDialog().setTitle(label("ข้อมูลเข้าสู่ระบบของกล้อง", "Camera credentials"))
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
        dialog = cameraDialog().setTitle(label("ใส่ URL ของ RTSP", "Enter RTSP URL"))
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
        confirm(uri, () -> store.save(uri));
    }

    private void confirm(URI uri, Callable<Boolean> select) {
        FrameLayout frame = new FrameLayout(this);
        frame.setMinimumHeight(dp(190));
        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        CameraPreview player = new CameraPreview(this, () -> updatePreview(message));
        preview = player;
        frame.addView(player, new FrameLayout.LayoutParams(-1, dp(190)));
        frame.addView(message, new FrameLayout.LayoutParams(-1, dp(190)));
        dialog = cameraDialog().setTitle(label("ดูตัวอย่างกล้อง", "Camera preview"))
                .setView(frame).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(label("ใช้กล้องนี้", "Use this camera"), (d, which) ->
                        run(label("กำลังบันทึก…", "Saving…"), select, saved -> {
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
        dialog = cameraDialog().setTitle(label("นำกล้องออกจากหน้าแรกหรือไม่", "Remove camera from Home?"))
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
