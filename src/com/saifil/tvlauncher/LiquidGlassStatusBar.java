package com.saifil.tvlauncher;

import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class LiquidGlassStatusBar extends LinearLayout {

    public static final int ICON_WIFI = 1;
    public static final int ICON_BLUETOOTH = 2;
    public static final int ICON_SETTINGS = 3;

    private static final String PREFS_NAME = "saifil_launcher_prefs";
    private static final String KEY_TEMP = "cached_temp_f";
    private static final String KEY_IS_DAY = "cached_is_day";

    private final WeatherMoonView weatherIcon;
    private final TextView weatherText;
    private final GlassIconView wifiIcon;
    private final GlassIconView bluetoothIcon;
    private final GlassIconView settingsIcon;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastWeatherFetchMs = 0;
    private AppDockView appDockView;

    public LiquidGlassStatusBar(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipToPadding(false);
        setClipChildren(false);
        setPadding(0, 0, 0, 0);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int cachedTemp = prefs.getInt(KEY_TEMP, 62);
        boolean cachedIsDay = prefs.getBoolean(KEY_IS_DAY, false);

        // 0. Live Weather Indicator (Moon/Sun Icon + Temperature)
        weatherIcon = new WeatherMoonView(context);
        weatherIcon.setDayMode(cachedIsDay);
        LayoutParams weatherIconLp = new LayoutParams(dp(18), dp(34));
        addView(weatherIcon, weatherIconLp);

        weatherText = new TextView(context);
        weatherText.setText(cachedTemp + "°");
        weatherText.setTextColor(Color.argb(242, 255, 255, 255));
        weatherText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        weatherText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        weatherText.setLetterSpacing(0.02f);
        weatherText.setShadowLayer(dpF(5f), 0f, dpF(1.2f), Color.argb(155, 0, 0, 0));
        weatherText.setSingleLine(true);
        weatherText.setPadding(0, 0, 0, dp(5));
        LayoutParams weatherTextLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        weatherTextLp.leftMargin = dp(4);
        weatherTextLp.rightMargin = dp(8);
        addView(weatherText, weatherTextLp);

        // 1. Wi-Fi Icon
        wifiIcon = new GlassIconView(context, ICON_WIFI);
        wifiIcon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openWifiSettings();
            }
        });
        addView(wifiIcon, createIconLayoutParams(0));

        // 2. Bluetooth Icon
        bluetoothIcon = new GlassIconView(context, ICON_BLUETOOTH);
        bluetoothIcon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openBluetoothSettings();
            }
        });
        addView(bluetoothIcon, createIconLayoutParams(dp(5)));

        // 3. Classic Gear Settings Icon
        settingsIcon = new GlassIconView(context, ICON_SETTINGS);
        settingsIcon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openSystemSettings();
            }
        });
        addView(settingsIcon, createIconLayoutParams(dp(5)));

        // 4. Date + Day + Time (TextClock) — macOS Menu Bar style ("Fri Nov 6  5:01 PM")
        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("EEE MMM d  h:mm a");
        clock.setFormat24Hour("EEE MMM d  HH:mm");
        clock.setTextColor(Color.argb(242, 255, 255, 255));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        clock.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        clock.setLetterSpacing(0.02f);
        clock.setShadowLayer(dpF(5f), 0f, dpF(1.2f), Color.argb(155, 0, 0, 0));
        clock.setSingleLine(true);
        clock.setPadding(0, 0, 0, dp(5));

        LayoutParams clockParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        clockParams.leftMargin = dp(10);
        addView(clock, clockParams);

        refreshSystemStatus();
    }

    private LayoutParams createIconLayoutParams(int leftMargin) {
        int width = dp(28);
        int height = dp(34);
        LayoutParams lp = new LayoutParams(width, height);
        lp.leftMargin = leftMargin;
        return lp;
    }

    public void refreshSystemStatus() {
        int wifiBars = queryWifiSignalBars();
        boolean btActive = queryBluetoothActive();
        wifiIcon.setSignalState(wifiBars);
        bluetoothIcon.setActiveState(btActive);
        settingsIcon.setActiveState(true);
        fetchWeatherIfNeeded();
    }

    private void fetchWeatherIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastWeatherFetchMs < 15 * 60 * 1000L) {
            return;
        }
        lastWeatherFetchMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Query Open-Meteo (San Francisco / Bay Area default coordinates, zero API key required)
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=37.7749&longitude=-122.4194&current_weather=true&temperature_unit=fahrenheit");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    if (conn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        JSONObject root = new JSONObject(sb.toString());
                        JSONObject cw = root.optJSONObject("current_weather");
                        if (cw != null) {
                            final int tempF = (int) Math.round(cw.optDouble("temperature", 62.0));
                            final boolean isDay = cw.optInt("is_day", 0) == 1;
                            getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putInt(KEY_TEMP, tempF)
                                    .putBoolean(KEY_IS_DAY, isDay)
                                    .apply();
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    weatherText.setText(tempF + "°");
                                    weatherIcon.setDayMode(isDay);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private int queryWifiSignalBars() {
        try {
            Context appCtx = getContext().getApplicationContext();
            ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network net = cm.getActiveNetwork();
                if (net == null) {
                    return 0;
                }
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return 0;
                }
            }
            WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    int level = WifiManager.calculateSignalLevel(info.getRssi(), 4);
                    return Math.max(1, Math.min(3, level));
                }
            }
            return 3;
        } catch (Exception ignored) {
            return 3;
        }
    }

    private boolean queryBluetoothActive() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (Exception ignored) {
            return true;
        }
    }

    public void setAppDockView(AppDockView dockView) {
        this.appDockView = dockView;
    }

    private void openWifiSettings() {
        if (!tryStartComponent("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.network.NetworkActivity")
                && !tryStartComponent("com.amazon.tv.settings", "com.amazon.tv.settings.tv.network.NetworkActivity")
                && !tryStartAction(Settings.ACTION_WIFI_SETTINGS)) {
            openSystemSettings();
        }
    }

    private void openBluetoothSettings() {
        if (!tryStartComponent("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity")
                && !tryStartComponent("com.amazon.tv.settings", "com.amazon.tv.settings.tv.devices.controllers.ControllersActivity")
                && !tryStartAction(Settings.ACTION_BLUETOOTH_SETTINGS)) {
            openSystemSettings();
        }
    }

    public void openSystemSettings() {
        if (!tryStartComponent("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity")) {
            tryStartComponent("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.hud.HudActivity");
        }
    }

    private static class SettingsEntry {
        final String title;
        final String subtitle;
        final String pkg;
        final String cls;
        final String fallbackPkg;
        final String fallbackCls;
        final boolean isDockAppsManager;

        SettingsEntry(String title, String subtitle, String pkg, String cls, String fallbackPkg, String fallbackCls, boolean isDockAppsManager) {
            this.title = title;
            this.subtitle = subtitle;
            this.pkg = pkg;
            this.cls = cls;
            this.fallbackPkg = fallbackPkg;
            this.fallbackCls = fallbackCls;
            this.isDockAppsManager = isDockAppsManager;
        }
    }

    /**
     * Opens the native Fire TV System Settings Hub.
     * On Fire OS 7 (Insignia Fire TV), `Settings.ACTION_SETTINGS` points to a dummy CTS no-op stub
     * (`CTSDummySettingsHandler`), while all real TV settings live in exported activities inside
     * `com.amazon.tv.settings.v2`. This hub gives 1-click access to every native TV settings page.
     */
    public void showSystemSettingsDialog() {
        final Context ctx = getContext();
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout overlay = new FrameLayout(ctx);
        overlay.setBackgroundColor(Color.argb(200, 6, 8, 13));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(244, 18, 22, 31));
        cardBg.setCornerRadius(dpF(22f));
        cardBg.setStroke(dp(1), Color.argb(48, 255, 255, 255));
        card.setBackground(cardBg);
        card.setPadding(dp(26), dp(22), dp(26), dp(22));

        // Header Row
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(VERTICAL);
        TextView titleView = new TextView(ctx);
        titleView.setText("TV System Settings");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleCol.addView(titleView);

        TextView subView = new TextView(ctx);
        subView.setText("Open native Fire TV picture, sound, network, device, or home dock settings");
        subView.setTextColor(Color.argb(150, 255, 255, 255));
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subView.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subView);

        LayoutParams titleColLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(titleCol, titleColLp);

        final TextView closeBtn = new TextView(ctx);
        closeBtn.setText("Close");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        closeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        closeBtn.setPadding(dp(18), dp(8), dp(18), dp(8));
        closeBtn.setFocusable(true);
        closeBtn.setClickable(true);

        final GradientDrawable closeBgNormal = new GradientDrawable();
        closeBgNormal.setColor(Color.argb(35, 255, 255, 255));
        closeBgNormal.setCornerRadius(dpF(10f));
        closeBgNormal.setStroke(dp(1), Color.argb(60, 255, 255, 255));

        final GradientDrawable closeBgFocused = new GradientDrawable();
        closeBgFocused.setColor(Color.argb(75, 255, 255, 255));
        closeBgFocused.setCornerRadius(dpF(10f));
        closeBgFocused.setStroke(dp(2), Color.WHITE);

        closeBtn.setBackground(closeBgNormal);
        closeBtn.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                closeBtn.setBackground(hasFocus ? closeBgFocused : closeBgNormal);
            }
        });
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        headerRow.addView(closeBtn, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        card.addView(headerRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        final SettingsEntry[] entries = new SettingsEntry[]{
                new SettingsEntry(
                        "Display & Sounds",
                        "Picture mode, backlight, audio output & HDMI-CEC",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.display_sounds.DisplayAndSoundsActivity",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.hud.HudActivity",
                        false
                ),
                new SettingsEntry(
                        "Quick Settings Drawer (HUD)",
                        "Native side overlay for Picture, Sound & Sleep Timer",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.hud.HudActivity",
                        "com.amazon.tv.launcher",
                        "com.amazon.tv.launcher.ui.QuickSettingsProxyActivity",
                        false
                ),
                new SettingsEntry(
                        "Network & Wi-Fi",
                        "Wi-Fi networks, signal strength & connection status",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.network.NetworkActivity",
                        null,
                        null,
                        false
                ),
                new SettingsEntry(
                        "Remotes & Bluetooth Devices",
                        "Fire TV remotes, Bluetooth headphones & speakers",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity",
                        null,
                        null,
                        false
                ),
                new SettingsEntry(
                        "Applications",
                        "Manage installed apps, clear cache & permissions",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.applications.ApplicationsActivity",
                        null,
                        null,
                        false
                ),
                new SettingsEntry(
                        "Device & Software (My Fire TV)",
                        "About, storage, Restart TV, sleep & Developer/ADB options",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.device.DeviceActivity",
                        null,
                        null,
                        false
                ),
                new SettingsEntry(
                        "Live TV & Input Settings",
                        "Antenna channels, HDMI input labels & CEC control",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.inputs.InputsActivity",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.display_sounds.DisplayAndSoundsActivity",
                        false
                ),
                new SettingsEntry(
                        "Preferences",
                        "Parental controls, privacy, time zone & language",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.preferences.PreferencesActivity",
                        null,
                        null,
                        false
                ),
                new SettingsEntry(
                        "Accessibility",
                        "Closed captions, VoiceView, screen magnifier & contrast",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.accessibility.AccessibilityActivity",
                        null,
                        null,
                        false
                ),
                new SettingsEntry(
                        "Account & Profile Settings",
                        "Amazon account, content sync & Prime settings",
                        "com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.my_account.MyAccountActivity",
                        null,
                        null,
                        false
                ),
                new SettingsEntry(
                        "Manage Home Dock Apps",
                        "Choose which apps & HDMI inputs appear in your bottom dock",
                        null,
                        null,
                        null,
                        null,
                        true
                ),
                new SettingsEntry(
                        "Full Fire TV Settings Page",
                        "Open the complete native Fire OS Settings overview",
                        "com.amazon.tv.launcher",
                        "com.amazon.tv.launcher.ui.MainSettingsActivity",
                        "com.amazon.tv.launcher",
                        "com.amazon.tv.launcher.ui.SettingsActivity",
                        false
                )
        };

        LinearLayout gridContainer = new LinearLayout(ctx);
        gridContainer.setOrientation(VERTICAL);
        LayoutParams gridLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        gridLp.topMargin = dp(14);

        int columns = 2;
        LinearLayout currentRow = null;
        View firstEntryView = null;

        for (int i = 0; i < entries.length; i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(ctx);
                currentRow.setOrientation(HORIZONTAL);
                LayoutParams rowLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = (i == 0) ? 0 : dp(8);
                gridContainer.addView(currentRow, rowLp);
            }

            final SettingsEntry entry = entries[i];
            View entryCard = createSettingsEntryCard(ctx, entry, dialog);
            LayoutParams cellLp = new LayoutParams(0, dp(52), 1f);
            if (i % columns > 0) {
                cellLp.leftMargin = dp(10);
            }
            currentRow.addView(entryCard, cellLp);
            if (firstEntryView == null) {
                firstEntryView = entryCard;
            }
        }

        card.addView(gridContainer, gridLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(720),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        overlay.addView(card, cardLp);

        dialog.setContentView(overlay);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        dialog.show();
        if (firstEntryView != null) {
            firstEntryView.requestFocus();
        }
    }

    private View createSettingsEntryCard(Context ctx, final SettingsEntry entry, final Dialog dialog) {
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);
        row.setClickable(true);

        final GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.argb(24, 255, 255, 255));
        normalBg.setCornerRadius(dpF(12f));
        normalBg.setStroke(dp(1), Color.argb(50, 255, 255, 255));

        final GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(Color.argb(58, 255, 255, 255));
        focusedBg.setCornerRadius(dpF(12f));
        focusedBg.setStroke(dp(2), Color.WHITE);

        row.setBackground(normalBg);

        TextView title = new TextView(ctx);
        title.setText(entry.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        row.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(entry.subtitle);
        sub.setTextColor(Color.argb(155, 255, 255, 255));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        sub.setSingleLine(true);
        row.addView(sub);

        row.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                row.setBackground(hasFocus ? focusedBg : normalBg);
            }
        });

        row.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    row.performClick();
                    return true;
                }
                return false;
            }
        });

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (entry.isDockAppsManager) {
                    if (appDockView != null) {
                        appDockView.showAppManagerDialog();
                    }
                    return;
                }
                if (entry.pkg != null && entry.cls != null) {
                    if (tryStartComponent(entry.pkg, entry.cls)) {
                        return;
                    }
                }
                if (entry.fallbackPkg != null && entry.fallbackCls != null) {
                    tryStartComponent(entry.fallbackPkg, entry.fallbackCls);
                }
            }
        });

        return row;
    }

    private boolean tryStartComponent(String pkg, String cls) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkg, cls));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean tryStartAction(String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private float dpF(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    /**
     * Warm amber Crescent Moon / Sun icon for the top-bar weather display.
     */
    private class WeatherMoonView extends View {
        private boolean isDay = false;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        WeatherMoonView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor("#FCD34D")); // Warm amber-300
            cutoutPaint.setStyle(Paint.Style.FILL);
            cutoutPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        void setDayMode(boolean day) {
            this.isDay = day;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = (getHeight() - dpF(6f)) / 2f;
            float r = dpF(6.2f);

            canvas.drawCircle(cx, cy, r, paint);
            if (!isDay) {
                // Carve crescent moon
                canvas.drawCircle(cx + dpF(3.2f), cy - dpF(2.4f), r * 0.85f, cutoutPaint);
            }
        }
    }

    /**
     * Option 2: Floating macOS Menu Bar icon with subtle optical shadow and bottom focus dot.
     */
    private class GlassIconView extends View {

        private final int iconType;
        private int signalBars = 3;
        private boolean isActive = true;

        private final Paint iconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gearStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcRect = new RectF();
        private final Path path = new Path();

        GlassIconView(Context context, int iconType) {
            super(context);
            this.iconType = iconType;
            setFocusable(true);
            setFocusableInTouchMode(false);
            setClickable(true);
            setLayerType(LAYER_TYPE_SOFTWARE, null);

            float shadowRadius = dpF(4.5f);
            float shadowDy = dpF(1.2f);
            int shadowColor = Color.argb(145, 0, 0, 0);

            iconStrokePaint.setStyle(Paint.Style.STROKE);
            iconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            iconStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            iconStrokePaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor);

            gearStrokePaint.setStyle(Paint.Style.STROKE);
            gearStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            gearStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            gearStrokePaint.setPathEffect(new CornerPathEffect(dpF(1.35f)));
            gearStrokePaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor);

            iconFillPaint.setStyle(Paint.Style.FILL);
            iconFillPaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor);

            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(Color.WHITE);
            dotPaint.setShadowLayer(dpF(3.5f), 0f, dpF(1f), shadowColor);

            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.animate()
                            .scaleX(hasFocus ? 1.12f : 1.0f)
                            .scaleY(hasFocus ? 1.12f : 1.0f)
                            .setDuration(150)
                            .start();
                    invalidate();
                }
            });
        }

        void setSignalState(int bars) {
            this.signalBars = bars;
            this.isActive = bars > 0;
            invalidate();
        }

        void setActiveState(boolean active) {
            this.isActive = active;
            invalidate();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                performClick();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && this == settingsIcon) {
                // Clamp right on rightmost status bar icon so focus doesn't drop
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && this == wifiIcon) {
                // Clamp left on leftmost status bar icon
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float cx = getWidth() / 2f;
            float cy = (getHeight() - dpF(6f)) / 2f;

            boolean focused = isFocused();
            int activeAlpha = focused ? 255 : (isActive ? 228 : 120);
            int dimAlpha = 95;

            if (iconType == ICON_WIFI) {
                drawWifiIcon(canvas, cx, cy, activeAlpha, dimAlpha);
            } else if (iconType == ICON_BLUETOOTH) {
                drawBluetoothIcon(canvas, cx, cy, activeAlpha);
            } else if (iconType == ICON_SETTINGS) {
                drawClassicRoundedGear(canvas, cx, cy, activeAlpha);
            }

            if (focused) {
                float dotY = getHeight() - dpF(3.5f);
                canvas.drawCircle(cx, dotY, dpF(2.0f), dotPaint);
            }
        }

        private void drawWifiIcon(Canvas canvas, float cx, float cy, int activeAlpha, int dimAlpha) {
            float s = dpF(18.5f) / 24f;
            float ox = cx - 12f * s;
            float oy = cy - 12.5f * s;

            iconStrokePaint.setStrokeWidth(1.8f * s);

            float arcCx = ox + 12f * s;
            float arcCy = oy + 20.2f * s;

            iconStrokePaint.setColor(Color.argb(signalBars >= 3 ? activeAlpha : dimAlpha, 255, 255, 255));
            float r3 = 12.2f * s;
            arcRect.set(arcCx - r3, arcCy - r3, arcCx + r3, arcCy + r3);
            canvas.drawArc(arcRect, 226f, 88f, false, iconStrokePaint);

            iconStrokePaint.setColor(Color.argb(signalBars >= 2 ? activeAlpha : dimAlpha, 255, 255, 255));
            float r2 = 8.4f * s;
            arcRect.set(arcCx - r2, arcCy - r2, arcCx + r2, arcCy + r2);
            canvas.drawArc(arcRect, 226f, 88f, false, iconStrokePaint);

            iconStrokePaint.setColor(Color.argb(signalBars >= 1 ? activeAlpha : dimAlpha, 255, 255, 255));
            float r1 = 4.6f * s;
            arcRect.set(arcCx - r1, arcCy - r1, arcCx + r1, arcCy + r1);
            canvas.drawArc(arcRect, 226f, 88f, false, iconStrokePaint);

            iconFillPaint.setColor(Color.argb(signalBars >= 1 ? activeAlpha : dimAlpha, 255, 255, 255));
            canvas.drawCircle(arcCx, oy + 19.8f * s, 1.4f * s, iconFillPaint);
        }

        private void drawBluetoothIcon(Canvas canvas, float cx, float cy, int alpha) {
            float s = dpF(18.0f) / 24f;
            float ox = cx - 12f * s;
            float oy = cy - 12f * s;

            iconStrokePaint.setColor(Color.argb(alpha, 255, 255, 255));
            iconStrokePaint.setStrokeWidth(1.75f * s);

            path.reset();
            path.moveTo(ox + 6.8f * s, oy + 7.5f * s);
            path.lineTo(ox + 17.2f * s, oy + 16.5f * s);
            path.lineTo(ox + 12.0f * s, oy + 21.2f * s);
            path.lineTo(ox + 12.0f * s, oy + 2.8f * s);
            path.lineTo(ox + 17.2f * s, oy + 7.5f * s);
            path.lineTo(ox + 6.8f * s, oy + 16.5f * s);
            canvas.drawPath(path, iconStrokePaint);
        }

        private void drawClassicRoundedGear(Canvas canvas, float cx, float cy, int alpha) {
            float s = dpF(18.5f) / 24f;
            int color = Color.argb(alpha, 255, 255, 255);

            gearStrokePaint.setColor(color);
            gearStrokePaint.setStrokeWidth(1.7f * s);

            iconStrokePaint.setColor(color);
            iconStrokePaint.setStrokeWidth(1.7f * s);

            float rInner = 6.9f * s;
            float rOuter = 9.6f * s;
            float toothTipHalfDeg = 9.0f;
            float toothBaseHalfDeg = 13.0f;

            path.reset();
            for (int i = 0; i < 8; i++) {
                float centerDeg = i * 45f;
                double a1 = Math.toRadians(centerDeg - toothBaseHalfDeg);
                double a2 = Math.toRadians(centerDeg - toothTipHalfDeg);
                double a3 = Math.toRadians(centerDeg + toothTipHalfDeg);
                double a4 = Math.toRadians(centerDeg + toothBaseHalfDeg);

                float x1 = cx + (float) Math.cos(a1) * rInner;
                float y1 = cy + (float) Math.sin(a1) * rInner;
                float x2 = cx + (float) Math.cos(a2) * rOuter;
                float y2 = cy + (float) Math.sin(a2) * rOuter;
                float x3 = cx + (float) Math.cos(a3) * rOuter;
                float y3 = cy + (float) Math.sin(a3) * rOuter;
                float x4 = cx + (float) Math.cos(a4) * rInner;
                float y4 = cy + (float) Math.sin(a4) * rInner;

                if (i == 0) {
                    path.moveTo(x1, y1);
                } else {
                    path.lineTo(x1, y1);
                }
                path.lineTo(x2, y2);
                path.lineTo(x3, y3);
                path.lineTo(x4, y4);
            }
            path.close();

            canvas.drawPath(path, gearStrokePaint);
            canvas.drawCircle(cx, cy, 3.15f * s, iconStrokePaint);
        }
    }
}
