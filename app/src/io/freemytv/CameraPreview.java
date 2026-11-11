package io.freemytv;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import java.net.URI;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

final class CameraPreview extends TextureView implements TextureView.SurfaceTextureListener {
    enum State { IDLE, CONNECTING, LIVE, OFFLINE }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final boolean fillFrame;
    private MediaPlayer player;
    private ExoPlayer tcpPlayer;
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
        setOpaque(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setSurfaceTextureListener(this);
    }

    State state() { return state; }

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
        if (stream == null || player != null || tcpPlayer != null || !isAvailable() || state != State.CONNECTING) return;
        CameraLan lan = CameraLan.current(getContext());
        if (lan == null || lan.address(stream) == null) {
            failed();
            return;
        }
        try {
            surface = new Surface(getSurfaceTexture());
            if (isVstarcamTcp(stream)) {
                startTcp(lan);
                return;
            }
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

    private static boolean isVstarcamTcp(URI stream) {
        String path = stream.getPath();
        return "/tcp/av0_0".equals(path) || "/tcp/av0_1".equals(path);
    }

    private void startTcp(CameraLan lan) {
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
        ExoPlayer p = new ExoPlayer.Builder(getContext()).build();
        tcpPlayer = p;
        p.setVideoSurface(surface);
        p.setVolume(0f);
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        p.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (p != tcpPlayer) return;
                videoWidth = size.width;
                videoHeight = size.height;
                scaleVideo();
            }

            @Override public void onRenderedFirstFrame() {
                if (p != tcpPlayer) return;
                handler.removeCallbacks(timeout);
                setState(State.LIVE);
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (p != tcpPlayer) return;
                failed();
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (p == tcpPlayer && state == Player.STATE_ENDED) failed();
            }
        });
        p.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .setDebugLoggingEnabled(false).setSocketFactory(new CameraSocketFactory(lan, stream))
                .createMediaSource(MediaItem.fromUri(stream.toASCIIString())));
        p.prepare();
        p.play();
        handler.postDelayed(timeout, 12_000);
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
        if (tcpPlayer != null) {
            ExoPlayer previous = tcpPlayer;
            tcpPlayer = null;
            previous.release();
        }
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
