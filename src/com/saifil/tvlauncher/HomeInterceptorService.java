package com.saifil.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class HomeInterceptorService extends AccessibilityService {

    private static final String TAG = "SaifilLauncher";
    private static final String PREFS_NAME = "saifil_launcher_prefs";
    private static final String KEY_AMAZON_MODE = "amazon_launcher_mode";
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String FIRE_HOME_STARTER = "com.amazon.firehomestarter";

    private static volatile Boolean cachedAmazonMode = null;
    private static volatile long switchedToAmazonAtMs = 0L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View overlayKeepAliveView = null;
    private String lastActivePackage = "com.saifil.tvlauncher";
    private final long[] backPressTimestamps = new long[5];
    private int backPressCount = 0;

    private final Runnable delayedRedirectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAmazonLauncherMode(HomeInterceptorService.this)) {
                launchCustomHome();
            }
        }
    };

    public static boolean isAmazonLauncherMode(Context ctx) {
        if (cachedAmazonMode != null) {
            return cachedAmazonMode;
        }
        if (ctx == null) {
            return false;
        }
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cachedAmazonMode = prefs.getBoolean(KEY_AMAZON_MODE, false);
        return cachedAmazonMode;
    }

    public static long getSwitchedToAmazonAtMs() {
        return switchedToAmazonAtMs;
    }

    /**
     * Pauses Saifil Launcher interception and opens the native stock Amazon Fire TV Home screen.
     * To return later without a laptop, simply launch "Saifil Launcher" from your Fire TV Apps list.
     */
    public static void switchToAmazonLauncher(Context ctx) {
        cachedAmazonMode = true;
        switchedToAmazonAtMs = System.currentTimeMillis();
        if (ctx != null) {
            Context appCtx = ctx.getApplicationContext();
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AMAZON_MODE, true)
                    .commit();
            try {
                Toast.makeText(
                        appCtx,
                        "Switched to Amazon Launcher (Open Saifil Launcher app anytime to return)",
                        Toast.LENGTH_LONG
                ).show();
            } catch (Exception ignored) {
            }
            launchStockAmazonHome(appCtx);
            if (ctx instanceof Activity) {
                try {
                    ((Activity) ctx).finish();
                } catch (Exception ignored) {
                }
            }
        }
        Log.i(TAG, "Switched to stock Amazon Fire TV Launcher mode");
    }

    /**
     * Re-enables Saifil Launcher home interception when the user launches Saifil Launcher again.
     */
    public static void activateSaifilLauncherMode(Context ctx, boolean showToast) {
        boolean wasAmazonMode = isAmazonLauncherMode(ctx);
        cachedAmazonMode = false;
        if (ctx != null) {
            Context appCtx = ctx.getApplicationContext();
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AMAZON_MODE, false)
                    .commit();
            if (wasAmazonMode && showToast) {
                try {
                    Toast.makeText(appCtx, "Saifil Launcher active", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
            }
        }
        Log.i(TAG, "Saifil Launcher mode active");
    }

    private static void launchStockAmazonHome(Context ctx) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setComponent(new ComponentName(AMAZON_LAUNCHER, "com.amazon.tv.launcher.ui.HomeActivity_vNext"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(intent);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent fallback = new Intent(Intent.ACTION_MAIN);
            fallback.addCategory(Intent.CATEGORY_HOME);
            fallback.setPackage(AMAZON_LAUNCHER);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(fallback);
        } catch (Exception e) {
            Log.e(TAG, "Failed launching stock Amazon Home", e);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }
        ensureOverlayExemptionView();
        boolean amazonMode = isAmazonLauncherMode(this);
        Log.i(TAG, "HomeInterceptorService connected (amazonLauncherMode=" + amazonMode + ")");
        if (!amazonMode) {
            launchCustomHome();
        }
    }

    /**
     * Attaches a 1x1px completely transparent, non-touchable TYPE_APPLICATION_OVERLAY view.
     * On Android 9 / Fire OS 7, ActivityManagerService exempts any process with an active
     * TYPE_APPLICATION_OVERLAY window from the 5-second stopAppSwitches() Home-button block.
     */
    private void ensureOverlayExemptionView() {
        if (overlayKeepAliveView != null) {
            return;
        }
        try {
            if (!Settings.canDrawOverlays(this)) {
                return;
            }
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            View v = new View(this);
            v.setBackgroundColor(Color.TRANSPARENT);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(v, lp);
            overlayKeepAliveView = v;
            Log.i(TAG, "Attached 1x1 TYPE_APPLICATION_OVERLAY exemption view");
        } catch (Exception e) {
            Log.w(TAG, "Could not attach overlay exemption view", e);
        }
    }

    /**
     * Intercepts the physical/remote HOME button (KEYCODE_HOME = 3) BEFORE Fire OS's
     * PhoneWindowManager sees it. On Fire OS, if the system sees KEYCODE_HOME, it calls
     * stopAppSwitches() which blocks any non-system app from starting an activity for 5 seconds
     * ("Activity start request from 10231 stopped") and launches com.amazon.tv.launcher.
     * By returning true here, Fire OS never receives KEYCODE_HOME and never calls stopAppSwitches()!
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        int keyCode = event.getKeyCode();

        // Emergency Remote Fail-Safe: Pressing BACK 5 times within 2.5 seconds while on Saifil Launcher
        // immediately exits to the stock Amazon Fire TV Launcher even if our UI is frozen.
        if (keyCode == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !isAmazonLauncherMode(this)) {
            if ("com.saifil.tvlauncher".equals(lastActivePackage)) {
                long now = System.currentTimeMillis();
                backPressTimestamps[backPressCount % 5] = now;
                backPressCount++;
                if (backPressCount >= 5) {
                    long oldest = backPressTimestamps[backPressCount % 5];
                    if (now - oldest <= 2500L) {
                        backPressCount = 0;
                        Log.w(TAG, "Emergency 5x BACK fail-safe triggered -> switching to Amazon Launcher");
                        switchToAmazonLauncher(this);
                        return true;
                    }
                }
            } else {
                backPressCount = 0;
            }
        }

        if (isAmazonLauncherMode(this)) {
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_ALL_APPS) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Log.i(TAG, "Intercepted KEYCODE_HOME DOWN -> launching Saifil Launcher directly");
                launchCustomHome();
            }
            // Consume both ACTION_DOWN and ACTION_UP so Fire OS never triggers stock Amazon Home or stopAppSwitches()
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        lastActivePackage = pkg;

        if (isAmazonLauncherMode(this)) {
            return;
        }

        if (FIRE_HOME_STARTER.equals(pkg)) {
            Log.i(TAG, "Intercepted firehomestarter -> redirecting to Saifil Launcher");
            redirectToCustomHome();
            return;
        }

        if (AMAZON_LAUNCHER.equals(pkg)) {
            // Allow standalone MainSettingsActivity / QuickSettingsProxyActivity, but ALWAYS block HomeActivity_vNext
            if (cls.contains("Settings") || cls.contains("QuickSettings")) {
                Log.i(TAG, "Allowing standalone Fire TV Settings activity: " + cls);
                return;
            }
            Log.i(TAG, "Intercepted stock launcher window: " + pkg + "/" + cls + " -> redirecting to Saifil Launcher");
            redirectToCustomHome();
        }
    }

    private void redirectToCustomHome() {
        if (isAmazonLauncherMode(this)) {
            return;
        }
        ensureOverlayExemptionView();
        launchCustomHome();
        mainHandler.removeCallbacks(delayedRedirectRunnable);
        mainHandler.postDelayed(delayedRedirectRunnable, 120L);
        mainHandler.postDelayed(delayedRedirectRunnable, 600L);
        mainHandler.postDelayed(delayedRedirectRunnable, 5100L);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayKeepAliveView != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) {
                    wm.removeView(overlayKeepAliveView);
                }
            } catch (Exception ignored) {
            }
            overlayKeepAliveView = null;
        }
    }

    private void launchCustomHome() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
            );
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch MainActivity from HomeInterceptorService", e);
        }
    }
}
