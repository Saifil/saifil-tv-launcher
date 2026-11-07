package com.saifil.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "SaifilLauncher";
    private static final float PLAYBACK_SPEED = 0.75f;

    private TextureView textureView;
    private LiquidGlassStatusBar statusBar;
    private AppDockView appDockView;
    private MediaPlayer mediaPlayer;
    private Surface videoSurface;
    private boolean isPreparing = false;
    private boolean isPrepared = false;
    private boolean isActivityResumed = false;
    private long lastFrameUpdatedMs = 0L;
    private String currentPlayingPath = null;
    private final List<File> videoFiles = new ArrayList<>();
    private int currentVideoIndex = 0;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());

    /**
     * Watchdog that checks 900ms after resume/screen-wake whether TextureView has actually received
     * a new decoded video frame (`onSurfaceTextureUpdated`). On MediaTek Fire TV hardware, deep standby
     * can silently invalidate the hardware decoder while `MediaPlayer` reports no error, leaving the
     * last frame frozen on screen. If no frame arrives within 900ms, we cleanly recreate the MediaPlayer.
     */
    private final Runnable frameWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isActivityResumed || videoSurface == null || videoFiles.isEmpty()) {
                return;
            }
            if (isPreparing) {
                watchdogHandler.postDelayed(this, 900L);
                return;
            }
            long elapsedSinceFrame = System.currentTimeMillis() - lastFrameUpdatedMs;
            if (mediaPlayer == null || !isPrepared || elapsedSinceFrame > 850L) {
                Log.w(TAG, "Watchdog detected stalled video after wake (elapsedSinceFrame=" + elapsedSinceFrame + "ms), recreating MediaPlayer");
                playVideoAtIndex(currentVideoIndex);
            }
        }
    };

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.i(TAG, "ACTION_SCREEN_OFF received: releasing MediaPlayer for clean wake");
                watchdogHandler.removeCallbacks(frameWatchdogRunnable);
                releaseMediaPlayer();
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                Log.i(TAG, action + " received: starting fresh video playback");
                if (statusBar != null) {
                    statusBar.refreshSystemStatus();
                }
                ensureSurfaceAndStartVideo(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashFailSafe();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        hideSystemUI();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0A0D14"));

        // 1. Fullscreen Hardware-Accelerated Video Wallpaper Layer
        textureView = new TextureView(this);
        textureView.setOpaque(false);
        textureView.setSurfaceTextureListener(this);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        root.addView(textureView, videoParams);

        // 2. Top-Left Prominent Greeting (Apple SF Style: Tracked Uppercase "WELCOME" + Light "Saifil Momin")
        LinearLayout headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);

        TextView welcomeText = new TextView(this);
        welcomeText.setText("WELCOME");
        welcomeText.setTextColor(Color.argb(180, 255, 255, 255));
        welcomeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        welcomeText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        welcomeText.setLetterSpacing(0.22f);
        welcomeText.setShadowLayer(10f, 0f, 2f, Color.argb(160, 0, 0, 0));
        headerBox.addView(welcomeText);

        TextView nameText = new TextView(this);
        nameText.setText("Saifil Momin");
        nameText.setTextColor(Color.WHITE);
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        nameText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        nameText.setLetterSpacing(0.03f);
        nameText.setShadowLayer(14f, 0f, 3f, Color.argb(175, 0, 0, 0));
        headerBox.addView(nameText);

        int marginLeft = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 26, getResources().getDisplayMetrics()));
        int marginTopLeft = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics()));
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
        );
        headerParams.setMargins(marginLeft, marginTopLeft, marginLeft, marginTopLeft);
        root.addView(headerBox, headerParams);

        // 3. Top-Right macOS Menu Bar (Wi-Fi, Bluetooth, Settings, Date & Time)
        statusBar = new LiquidGlassStatusBar(this);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        int marginH = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics()));
        int marginV = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics()));
        statusParams.setMargins(marginH, marginV, marginH, marginV);
        root.addView(statusBar, statusParams);

        // 4. Subtle Bottom Dark Gradient Vignette (keeps the center Black Hole completely unobstructed)
        View bottomVignette = new View(this);
        GradientDrawable vignetteDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.argb(175, 4, 6, 10)}
        );
        bottomVignette.setBackground(vignetteDrawable);
        int vignetteHeight = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 150, getResources().getDisplayMetrics()));
        FrameLayout.LayoutParams vignetteParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                vignetteHeight,
                Gravity.BOTTOM
        );
        root.addView(bottomVignette, vignetteParams);

        // 5. Bottom macOS Single-Line Apps & Inputs Dock
        appDockView = new AppDockView(this);
        appDockView.setTopRightTargetView(statusBar);
        statusBar.setAppDockView(appDockView);
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        int dockBottomMargin = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics()));
        dockParams.setMargins(0, 0, 0, dockBottomMargin);
        root.addView(appDockView, dockParams);

        setContentView(root);
        appDockView.post(new Runnable() {
            @Override
            public void run() {
                appDockView.requestInitialFocus();
            }
        });

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, screenFilter);

        discoverVideos();
    }

    private void discoverVideos() {
        videoFiles.clear();
        File bgDir = getExternalFilesDir("bg");
        if (bgDir != null && bgDir.exists() && bgDir.isDirectory()) {
            File[] files = bgDir.listFiles();
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".mp4")) {
                        videoFiles.add(f);
                    }
                }
            }
        }
        Log.i(TAG, "Discovered " + videoFiles.size() + " background videos");
    }

    private void ensureSurfaceAndStartVideo(boolean forceFreshPlayer) {
        if ((videoSurface == null || !videoSurface.isValid()) && textureView != null && textureView.isAvailable()) {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                if (videoSurface != null) {
                    try {
                        videoSurface.release();
                    } catch (Exception ignored) {
                    }
                }
                videoSurface = new Surface(st);
            }
        }
        if (forceFreshPlayer) {
            releaseMediaPlayer();
        }
        startOrResumeVideo();
        scheduleFrameWatchdog();
    }

    private void scheduleFrameWatchdog() {
        watchdogHandler.removeCallbacks(frameWatchdogRunnable);
        watchdogHandler.postDelayed(frameWatchdogRunnable, 950L);
    }

    private void startOrResumeVideo() {
        if (videoSurface == null || !videoSurface.isValid()) {
            return;
        }
        if (!isScreenInteractive()) {
            return;
        }
        discoverVideos();
        if (videoFiles.isEmpty()) {
            releaseMediaPlayer();
            return;
        }
        currentVideoIndex = currentVideoIndex % videoFiles.size();
        String expectedPath = videoFiles.get(currentVideoIndex).getAbsolutePath();
        if (mediaPlayer != null && expectedPath.equals(currentPlayingPath)) {
            if (isPreparing) {
                return;
            }
            if (isPrepared) {
                try {
                    if (!mediaPlayer.isPlaying()) {
                        applyPlaybackSpeedAndStart(mediaPlayer);
                        Log.i(TAG, "Resumed video playback at " + PLAYBACK_SPEED + "x");
                    }
                    scheduleFrameWatchdog();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Resume failed, recreating MediaPlayer", e);
                    releaseMediaPlayer();
                }
            }
        }
        playVideoAtIndex(currentVideoIndex);
    }

    private void playVideoAtIndex(int index) {
        if (videoFiles.isEmpty() || videoSurface == null || !videoSurface.isValid()) {
            return;
        }
        currentVideoIndex = index % videoFiles.size();
        File target = videoFiles.get(currentVideoIndex);

        releaseMediaPlayer();
        try {
            currentPlayingPath = target.getAbsolutePath();
            Log.i(TAG, "Preparing video: " + currentPlayingPath);
            isPreparing = true;
            isPrepared = false;
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setSurface(videoSurface);
            mediaPlayer.setDataSource(target.getAbsolutePath());
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.setLooping(videoFiles.size() == 1);
            mediaPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    updateTextureViewScaling(width, height);
                }
            });
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    isPreparing = false;
                    isPrepared = true;
                    lastFrameUpdatedMs = System.currentTimeMillis();
                    updateTextureViewScaling(mp.getVideoWidth(), mp.getVideoHeight());
                    applyPlaybackSpeedAndStart(mp);
                    scheduleFrameWatchdog();
                    Log.i(TAG, "Video started playing (" + mp.getVideoWidth() + "x" + mp.getVideoHeight() + ") at " + PLAYBACK_SPEED + "x");
                }
            });
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (videoFiles.size() > 1) {
                        playVideoAtIndex(currentVideoIndex + 1);
                    } else {
                        mp.seekTo(0);
                        applyPlaybackSpeedAndStart(mp);
                    }
                }
            });
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra + " — scheduling retry");
                    isPreparing = false;
                    isPrepared = false;
                    scheduleFrameWatchdog();
                    return true;
                }
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MediaPlayer", e);
            isPreparing = false;
            isPrepared = false;
        }
    }

    private void applyPlaybackSpeedAndStart(MediaPlayer mp) {
        try {
            if (!mp.isPlaying()) {
                mp.start();
            }
        } catch (Exception ignored) {
        }
        try {
            PlaybackParams params = mp.getPlaybackParams();
            if (params == null) {
                params = new PlaybackParams();
            }
            params.setSpeed(PLAYBACK_SPEED);
            mp.setPlaybackParams(params);
        } catch (Exception e) {
            Log.w(TAG, "setPlaybackParams failed, continuing normal playback", e);
            try {
                if (!mp.isPlaying()) {
                    mp.start();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isScreenInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Exception ignored) {
            return true;
        }
    }

    private void updateTextureViewScaling(int videoWidth, int videoHeight) {
        if (textureView == null || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float videoAspect = (float) videoWidth / videoHeight;
        float viewAspect = (float) viewWidth / viewHeight;

        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect;
        } else {
            scaleY = viewAspect / videoAspect;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        textureView.setTransform(matrix);
    }

    private void releaseMediaPlayer() {
        isPreparing = false;
        isPrepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.i(TAG, "SurfaceTexture available: " + width + "x" + height);
        videoSurface = new Surface(surface);
        startOrResumeVideo();
        scheduleFrameWatchdog();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (mediaPlayer != null && isPrepared) {
            updateTextureViewScaling(mediaPlayer.getVideoWidth(), mediaPlayer.getVideoHeight());
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        watchdogHandler.removeCallbacks(frameWatchdogRunnable);
        releaseMediaPlayer();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        lastFrameUpdatedMs = System.currentTimeMillis();
    }

    private void installCrashFailSafe() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    Log.e(TAG, "Uncaught exception in Saifil Launcher — activating Amazon Launcher fail-safe", e);
                    HomeInterceptorService.switchToAmazonLauncher(getApplicationContext());
                } catch (Exception ignored) {
                }
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(t, e);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;
        // If user launched Saifil Launcher from the stock Amazon Apps list to come back, re-activate interception
        if (HomeInterceptorService.isAmazonLauncherMode(this)
                && System.currentTimeMillis() - HomeInterceptorService.getSwitchedToAmazonAtMs() > 2500L) {
            HomeInterceptorService.activateSaifilLauncherMode(this, true);
        }
        overridePendingTransition(0, 0);
        hideSystemUI();
        if (statusBar != null) {
            statusBar.refreshSystemStatus();
        }
        ensureSurfaceAndStartVideo(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && isActivityResumed) {
            hideSystemUI();
            ensureSurfaceAndStartVideo(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
        watchdogHandler.removeCallbacks(frameWatchdogRunnable);
        if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.pause();
                Log.i(TAG, "Paused video playback");
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        watchdogHandler.removeCallbacks(frameWatchdogRunnable);
        // Fully release MediaPlayer when the TV goes to sleep or switches to another fullscreen app
        // so waking the TV always starts a fresh hardware decoder session
        releaseMediaPlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        watchdogHandler.removeCallbacks(frameWatchdogRunnable);
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {
        }
        releaseMediaPlayer();
    }

    @Override
    public void onBackPressed() {
        // Ignore Back button on home screen
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }
}
