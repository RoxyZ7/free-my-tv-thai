package io.freemytv;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import java.net.URI;

final class CameraPreview extends TextureView implements TextureView.SurfaceTextureListener {
    enum State { IDLE, CONNECTING, LIVE, OFFLINE }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final boolean fillFrame;
    private float cornerRadius;
    private MediaPlayer player;
    private Surface surface;
    private URI stream;
    private int videoWidth, videoHeight;
    private State state = State.IDLE;
    private final Runnable timeout = () -> {
        if (state == State.CONNECTING) failed();
    };

    CameraPreview(Context context, Runnable changed) {
        this(context, changed, false);
    }

    CameraPreview(Context context, Runnable changed, boolean fillFrame) {
        super(context);
        this.changed = changed;
        this.fillFrame = fillFrame;
        if (fillFrame) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
            setClipToOutline(true);
        }
        setOpaque(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setSurfaceTextureListener(this);
    }

    State state() { return state; }

    void setCornerRadius(float radius) {
        if (cornerRadius == radius) return;
        cornerRadius = radius;
        invalidateOutline();
    }

    void showStream(URI next) {
        if (java.util.Objects.equals(stream, next)) return;
        stop();
        stream = next;
        if (next != null) {
            setState(State.CONNECTING);
            startIfReady();
        }
    }

    void stop() {
        stream = null;
        releasePlayer();
        setState(State.IDLE);
    }

    private void startIfReady() {
        if (stream == null || player != null || !isAvailable() || state != State.CONNECTING) return;
        CameraLan lan = CameraLan.current(getContext());
        if (lan == null || lan.address(stream) == null) {
            failed();
            return;
        }
        try {
            surface = new Surface(getSurfaceTexture());
            player = new MediaPlayer();
            player.setSurface(surface);
            player.setVolume(0f, 0f);
            player.setOnPreparedListener(p -> {
                if (p != player) return;
                try { p.start(); } catch (RuntimeException ignored) { failed(); }
            });
            player.setOnVideoSizeChangedListener((p, width, height) -> {
                if (p != player) return;
                videoWidth = width;
                videoHeight = height;
                scaleVideo();
            });
            player.setOnInfoListener((p, what, extra) -> {
                if (p == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    handler.removeCallbacks(timeout);
                    setState(State.LIVE);
                    return true;
                }
                return false;
            });
            player.setOnErrorListener((p, what, extra) -> {
                if (p == player) failed();
                return true;
            });
            player.setOnCompletionListener(p -> { if (p == player) failed(); });
            player.setDataSource(getContext(), android.net.Uri.parse(stream.toASCIIString()),
                    java.util.Collections.singletonMap("x-hide-urls-from-log", "true"));
            player.prepareAsync();
            handler.postDelayed(timeout, 12_000);
        } catch (Exception ignored) {
            failed();
        }
    }

    private void setState(State next) {
        if (state == next) return;
        state = next;
        changed.run();
    }

    private void failed() {
        releasePlayer();
        setState(State.OFFLINE);
    }

    private void releasePlayer() {
        handler.removeCallbacks(timeout);
        if (player != null) {
            MediaPlayer previous = player;
            player = null;
            previous.release();
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        videoWidth = videoHeight = 0;
    }

    private void scaleVideo() {
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        float scaleX = width / (float) videoWidth, scaleY = height / (float) videoHeight;
        float scale = fillFrame ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f);
        setTransform(matrix);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        startIfReady();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { scaleVideo(); }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releasePlayer();
        if (stream != null && state != State.OFFLINE) setState(State.CONNECTING);
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
}
