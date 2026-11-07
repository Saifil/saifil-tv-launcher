package com.saifil.tvlauncher;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CleanYourTV / Apple TV-Style Single-Line Bottom Dock:
 * - Pulls and renders the REAL official 320x180 (16:9) Leanback Banner images directly from the installed
 *   Android TV / Fire TV APKs on the TV via PackageManager (Apple TV, Disney+, YouTube, Netflix, Prime Video,
 *   Hulu, Spotify, Crunchyroll, Willow, Peacock, Fubo, ZEE5, NBC Sports, Appstore)
 * - Uses CleanYourTV's exact 16:9 TV Banner aspect ratio (110dp x 62dp with 12.5dp corners)
 * - Soft pure-white glow strictly BEHIND the focused 16:9 tile (no bottom dot, zero size shift)
 * - Strictly clipped inside the rounded glass dock shelf so nothing ever overflows the right edge
 */
public class AppDockView extends LinearLayout {

    private static final String TAG = "SaifilAppDock";
    private static final String PREFS_NAME = "saifil_launcher_prefs";
    private static final String KEY_ENABLED_PREFIX = "dock_item_enabled_";

    public static final int TYPE_APP = 1;
    public static final int TYPE_HDMI = 2;
    public static final int TYPE_SETTINGS = 3;

    public static class CatalogItem {
        public final String id;
        public final String label;
        public final String pkg;
        public final int itemType;
        public final int hdmiIndex; // 1, 2, 3 for HDMI items
        public final boolean defaultEnabled;
        public boolean enabled;

        public CatalogItem(String id, String label, String pkg, int itemType, int hdmiIndex, boolean defaultEnabled) {
            this.id = id;
            this.label = label;
            this.pkg = pkg;
            this.itemType = itemType;
            this.hdmiIndex = hdmiIndex;
            this.defaultEnabled = defaultEnabled;
            this.enabled = defaultEnabled;
        }
    }

    private final List<CatalogItem> catalog = new ArrayList<>();
    private final Map<String, Drawable> bannerCache = new HashMap<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final List<DockTileView> dockTileViews = new ArrayList<>();
    private final TextView focusedTitleText;
    private final DockShelfContainer shelfContainer;
    private final HorizontalScrollView dockScrollView;
    private final LinearLayout dockRow;
    private View firstTileView = null;
    private View topRightTargetView = null;

    public AppDockView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);

        initCatalog();
        loadPreferences();

        // Focused App/Input Title Label above the Dock
        focusedTitleText = new TextView(context);
        focusedTitleText.setText("YouTube");
        focusedTitleText.setTextColor(Color.argb(242, 255, 255, 255));
        focusedTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        focusedTitleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        focusedTitleText.setLetterSpacing(0.04f);
        focusedTitleText.setGravity(Gravity.CENTER);
        focusedTitleText.setShadowLayer(dpF(8f), 0f, dpF(2f), Color.argb(215, 0, 0, 0));

        LayoutParams titleLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        titleLp.bottomMargin = dp(6);
        addView(focusedTitleText, titleLp);

        // macOS / CleanYourTV Translucent Glass Shelf Container
        shelfContainer = new DockShelfContainer(context);

        dockScrollView = new HorizontalScrollView(context);
        dockScrollView.setHorizontalScrollBarEnabled(false);
        dockScrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        dockScrollView.setClipChildren(true);
        dockScrollView.setClipToPadding(false);
        dockScrollView.setFillViewport(false);

        dockRow = new LinearLayout(context);
        dockRow.setOrientation(HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(8), dp(2), dp(8), dp(2));

        dockScrollView.addView(dockRow, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        scrollLp.leftMargin = dp(2);
        scrollLp.rightMargin = dp(2);
        shelfContainer.addView(dockScrollView, scrollLp);

        LayoutParams shelfLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        shelfLp.leftMargin = dp(24);
        shelfLp.rightMargin = dp(24);
        addView(shelfContainer, shelfLp);

        rebuildDock(null);
    }

    public void setTopRightTargetView(View view) {
        this.topRightTargetView = view;
    }

    private void initCatalog() {
        catalog.clear();
        // Streaming apps first
        catalog.add(new CatalogItem("youtube", "YouTube", "com.amazon.firetv.youtube", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("netflix", "Netflix", "com.netflix.ninja", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("prime", "Prime Video", "com.amazon.avod", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("disney", "Disney+", "com.disney.disneyplus", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("appletv", "Apple TV", "com.apple.atve.amazon.appletv", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("hulu", "Hulu", "com.hulu.plus", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("spotify", "Spotify", "com.spotify.tv.android", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("crunchyroll", "Crunchyroll", "com.crunchyroll.crunchyroid", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("willow", "Willow", "tv.willow", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("peacock", "Peacock", "com.peacock.peacockfiretv", TYPE_APP, 0, true));
        catalog.add(new CatalogItem("fubo", "Fubo", "com.fubo.firetv.screen", TYPE_APP, 0, false));
        catalog.add(new CatalogItem("zee5", "ZEE5", "com.zee5.amazon", TYPE_APP, 0, false));
        catalog.add(new CatalogItem("nbcsports", "NBC Sports", "com.nbcuni.com.nbcsports.liveextra.firetv", TYPE_APP, 0, false));
        // Amazon Appstore
        catalog.add(new CatalogItem("appstore", "Appstore", "com.amazon.venezia", TYPE_APP, 0, true));
        // HDMIs moved to the end
        catalog.add(new CatalogItem("hdmi1", "HDMI 1", null, TYPE_HDMI, 1, true));
        catalog.add(new CatalogItem("hdmi2", "HDMI 2", null, TYPE_HDMI, 2, true));
        catalog.add(new CatalogItem("hdmi3", "HDMI 3", null, TYPE_HDMI, 3, false));
        // Always-available Launcher Settings button at the very end
        catalog.add(new CatalogItem("launchersettings", "Launcher Settings", null, TYPE_SETTINGS, 0, true));
    }

    /**
     * Loads and caches the official 320x180 high-DPI 16:9 TV banner drawable directly from the installed APK on the TV.
     */
    private Drawable getOrLoadBanner(String pkg) {
        if (pkg == null) {
            return null;
        }
        if (bannerCache.containsKey(pkg)) {
            return bannerCache.get(pkg);
        }
        Drawable banner = loadPackageBanner(pkg);
        bannerCache.put(pkg, banner);
        return banner;
    }

    private Drawable getOrLoadIcon(String pkg) {
        if (pkg == null) {
            return null;
        }
        if (iconCache.containsKey(pkg)) {
            return iconCache.get(pkg);
        }
        Drawable icon = loadPackageIcon(pkg);
        iconCache.put(pkg, icon);
        return icon;
    }

    private Drawable loadPackageBanner(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            int targetDensity = Math.max(DisplayMetrics.DENSITY_XHIGH, getResources().getDisplayMetrics().densityDpi);

            // 1. Check Leanback launch activity banner first (just like CleanYourTV's AppAdapter.loadBanner)
            Intent lbIntent = new Intent(Intent.ACTION_MAIN);
            lbIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            lbIntent.setPackage(pkg);
            List<ResolveInfo> resolves = pm.queryIntentActivities(lbIntent, 0);
            if (resolves == null || resolves.isEmpty()) {
                Intent mainIntent = new Intent(Intent.ACTION_MAIN);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                mainIntent.setPackage(pkg);
                resolves = pm.queryIntentActivities(mainIntent, 0);
            }

            if (resolves != null && !resolves.isEmpty()) {
                ActivityInfo ai = resolves.get(0).activityInfo;
                if (ai != null) {
                    int bannerResId = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
                    if (bannerResId != 0) {
                        try {
                            Resources appRes = pm.getResourcesForApplication(ai.applicationInfo);
                            Drawable d = appRes.getDrawableForDensity(bannerResId, targetDensity, null);
                            if (isBannerShaped(d)) {
                                Log.i(TAG, "Loaded official density banner for " + pkg + " (" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + ")");
                                return d;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    Drawable d = ai.loadBanner(pm);
                    if (isBannerShaped(d)) {
                        Log.i(TAG, "Loaded activity banner for " + pkg + " (" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + ")");
                        return d;
                    }
                }
            }

            // 2. Fallback to ApplicationInfo banner
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            if (appInfo.banner != 0) {
                try {
                    Resources appRes = pm.getResourcesForApplication(appInfo);
                    Drawable d = appRes.getDrawableForDensity(appInfo.banner, targetDensity, null);
                    if (isBannerShaped(d)) {
                        Log.i(TAG, "Loaded appInfo density banner for " + pkg + " (" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + ")");
                        return d;
                    }
                } catch (Exception ignored) {
                }
            }
            Drawable appBanner = pm.getApplicationBanner(appInfo);
            if (isBannerShaped(appBanner)) {
                Log.i(TAG, "Loaded pm.getApplicationBanner for " + pkg + " (" + appBanner.getIntrinsicWidth() + "x" + appBanner.getIntrinsicHeight() + ")");
                return appBanner;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed loading banner for " + pkg, e);
        }
        return null;
    }

    private Drawable loadPackageIcon(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            int targetDensity = Math.max(DisplayMetrics.DENSITY_XHIGH, getResources().getDisplayMetrics().densityDpi);
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            if (appInfo.icon != 0) {
                try {
                    Resources appRes = pm.getResourcesForApplication(appInfo);
                    Drawable d = appRes.getDrawableForDensity(appInfo.icon, targetDensity, null);
                    if (d != null) {
                        return d;
                    }
                } catch (Exception ignored) {
                }
            }
            return pm.getApplicationIcon(appInfo);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isBannerShaped(Drawable d) {
        if (d == null) {
            return false;
        }
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        return w <= 0 || h <= 0 || w >= h * 1.35f;
    }

    private void loadPreferences() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (CatalogItem item : catalog) {
            if (item.itemType == TYPE_SETTINGS) {
                item.enabled = true;
            } else {
                item.enabled = prefs.getBoolean(KEY_ENABLED_PREFIX + item.id, item.defaultEnabled);
            }
        }
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        for (CatalogItem item : catalog) {
            if (item.itemType != TYPE_SETTINGS) {
                editor.putBoolean(KEY_ENABLED_PREFIX + item.id, item.enabled);
            }
        }
        editor.apply();
    }

    public void requestInitialFocus() {
        if (firstTileView != null) {
            firstTileView.requestFocus();
        }
    }

    private void rebuildDock(String preferFocusId) {
        dockRow.removeAllViews();
        dockTileViews.clear();
        firstTileView = null;
        View targetFocusView = null;

        List<CatalogItem> enabledItems = new ArrayList<>();
        for (CatalogItem item : catalog) {
            if (item.enabled || item.itemType == TYPE_SETTINGS) {
                enabledItems.add(item);
            }
        }

        // CleanYourTV 16:9 TV Banner proportion (180 : 101):
        // Actual rectangular banner size = 110dp wide x 62dp high.
        // View canvas size = 132dp wide x 84dp high (11dp halo room on all 4 sides so the brighter blur glow NEVER hits the view rectangle edge).
        int viewWidth = dp(132);
        int viewHeight = dp(84);
        int tileMarginH = -dp(4);

        boolean addedHdmiDivider = false;

        for (int i = 0; i < enabledItems.size(); i++) {
            final CatalogItem item = enabledItems.get(i);

            // macOS Dock vertical separator line before the first HDMI input
            if (item.itemType == TYPE_HDMI && !addedHdmiDivider && i > 0) {
                addedHdmiDivider = true;
                dockRow.addView(createMacDockDivider());
            }

            // macOS Dock vertical separator line before the Launcher Settings button at the very end
            if (item.itemType == TYPE_SETTINGS && i > 0) {
                dockRow.addView(createMacDockDivider());
            }

            int tileIdx = dockTileViews.size();
            DockTileView tile = new DockTileView(getContext(), item, tileIdx);
            LayoutParams tileLp = new LayoutParams(viewWidth, viewHeight);
            tileLp.leftMargin = tileMarginH;
            tileLp.rightMargin = tileMarginH;
            dockRow.addView(tile, tileLp);
            dockTileViews.add(tile);

            if (firstTileView == null) {
                firstTileView = tile;
            }
            if (preferFocusId != null && preferFocusId.equals(item.id)) {
                targetFocusView = tile;
            }
        }

        if (targetFocusView != null) {
            targetFocusView.requestFocus();
        }
    }

    private View createMacDockDivider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(Color.argb(60, 255, 255, 255));
        LayoutParams divLp = new LayoutParams(dp(1), dp(46));
        divLp.leftMargin = dp(6);
        divLp.rightMargin = dp(6);
        divLp.gravity = Gravity.CENTER_VERTICAL;
        divider.setLayoutParams(divLp);
        return divider;
    }

    private boolean handleTileKeyNavigation(int index, int keyCode) {
        int size = dockTileViews.size();
        if (size == 0) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (index + 1 < size) {
                dockTileViews.get(index + 1).requestFocus();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (index - 1 >= 0) {
                dockTileViews.get(index - 1).requestFocus();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (topRightTargetView != null) {
                topRightTargetView.requestFocus();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return true;
        }
        return false;
    }

    private void onItemClicked(CatalogItem item) {
        if (item.itemType == TYPE_SETTINGS) {
            showAppManagerDialog();
        } else if (item.itemType == TYPE_HDMI) {
            switchToHdmiInput(item.hdmiIndex, item.label);
        } else {
            launchApp(item);
        }
    }

    private void launchApp(CatalogItem item) {
        Context ctx = getContext();
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent intent = pm.getLeanbackLaunchIntentForPackage(item.pkg);
            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(item.pkg);
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Standard launch failed for " + item.pkg, e);
        }

        if ("com.amazon.venezia".equals(item.pkg)) {
            try {
                Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=com.amazon.venezia"));
                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(marketIntent);
                return;
            } catch (Exception ignored) {
            }
        }

        Toast.makeText(ctx, item.label + " is not installed", Toast.LENGTH_SHORT).show();
    }

    private void switchToHdmiInput(int hdmiNumber, String label) {
        Context ctx = getContext();
        try {
            TvInputManager manager = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (manager != null) {
                List<TvInputInfo> allInputs = manager.getTvInputList();
                List<TvInputInfo> hdmiInputs = new ArrayList<>();
                if (allInputs != null) {
                    for (TvInputInfo info : allInputs) {
                        if (info.getType() == TvInputInfo.TYPE_HDMI) {
                            hdmiInputs.add(info);
                        }
                    }
                }
                Collections.sort(hdmiInputs, new Comparator<TvInputInfo>() {
                    @Override
                    public int compare(TvInputInfo a, TvInputInfo b) {
                        return a.getId().compareToIgnoreCase(b.getId());
                    }
                });

                TvInputInfo matched = null;
                for (TvInputInfo info : hdmiInputs) {
                    CharSequence customLabel = info.loadCustomLabel(ctx);
                    CharSequence stdLabel = info.loadLabel(ctx);
                    String combined = ((customLabel != null ? customLabel.toString() : "") + " "
                            + (stdLabel != null ? stdLabel.toString() : "") + " " + info.getId()).toLowerCase();
                    if (combined.contains("hdmi " + hdmiNumber) || combined.contains("hdmi" + hdmiNumber)
                            || combined.endsWith("/hw" + hdmiNumber)) {
                        matched = info;
                        break;
                    }
                }
                if (matched == null && hdmiNumber >= 1 && hdmiNumber <= hdmiInputs.size()) {
                    matched = hdmiInputs.get(hdmiNumber - 1);
                }

                if (matched != null) {
                    Uri uri = TvContract.buildChannelUriForPassthroughInput(matched.getId());
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed switching to " + label, e);
        }
        Toast.makeText(ctx, "Switching to " + label, Toast.LENGTH_SHORT).show();
    }

    /**
     * CleanYourTV / macOS Translucent Glass Dock Shelf Background.
     */
    private class DockShelfContainer extends FrameLayout {

        private final Paint shelfFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shelfBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF shelfRect = new RectF();
        private final Path shelfClipPath = new Path();

        DockShelfContainer(Context context) {
            super(context);
            setWillNotDraw(false);
            shelfFillPaint.setStyle(Paint.Style.FILL);
            shelfFillPaint.setColor(Color.argb(150, 18, 20, 26));

            shelfBorderPaint.setStyle(Paint.Style.STROKE);
            shelfBorderPaint.setStrokeWidth(dpF(1.1f));
            shelfBorderPaint.setColor(Color.argb(56, 255, 255, 255));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            float inset = dpF(1f);
            shelfRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float r = dpF(21f);

            canvas.drawRoundRect(shelfRect, r, r, shelfFillPaint);

            int saveCount = canvas.save();
            shelfClipPath.reset();
            shelfClipPath.addRoundRect(shelfRect, r, r, Path.Direction.CW);
            canvas.clipPath(shelfClipPath);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saveCount);

            canvas.drawRoundRect(shelfRect, r, r, shelfBorderPaint);
        }
    }

    /**
     * Individual 16:9 TV Banner Tile inside the Bottom Dock (CleanYourTV 180:101 aspect ratio):
     * - View canvas size: 128dp x 80dp
     * - Actual 16:9 Rectangular Banner size: 110dp wide x 62dp high (centered, with 9dp halo room on all 4 sides)
     * - Soft pure white glow drawn strictly BEHIND the 16:9 icon when focused
     */
    private class DockTileView extends View {

        private final CatalogItem item;
        private final int tileIndex;

        private final Paint backHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dropShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF haloRect = new RectF();
        private final RectF iconRect = new RectF();
        private final Path clipPath = new Path();

        DockTileView(Context context, final CatalogItem item, int tileIndex) {
            super(context);
            this.item = item;
            this.tileIndex = tileIndex;

            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setFocusable(true);
            setFocusableInTouchMode(false);
            setClickable(true);

            // Pure white blurred halo strictly BEHIND the icon — tuned to stand out clearly even behind pure-white tiles (YouTube/Netflix)
            backHaloPaint.setStyle(Paint.Style.FILL);
            backHaloPaint.setColor(Color.argb(225, 255, 255, 255));
            backHaloPaint.setMaskFilter(new BlurMaskFilter(dpF(6.8f), BlurMaskFilter.Blur.NORMAL));

            dropShadowPaint.setStyle(Paint.Style.FILL);
            dropShadowPaint.setColor(Color.argb(145, 0, 0, 0));
            dropShadowPaint.setMaskFilter(new BlurMaskFilter(dpF(4.5f), BlurMaskFilter.Blur.NORMAL));

            borderPaint.setStyle(Paint.Style.STROKE);

            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        focusedTitleText.setText(item.label);
                        dockScrollView.post(new Runnable() {
                            @Override
                            public void run() {
                                int scrollCenter = (getLeft() + getRight()) / 2 - dockScrollView.getWidth() / 2;
                                dockScrollView.smoothScrollTo(Math.max(0, scrollCenter), 0);
                            }
                        });
                    }
                    invalidate();
                }
            });

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onItemClicked(item);
                }
            });
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                performClick();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (handleTileKeyNavigation(tileIndex, keyCode)) {
                    return true;
                }
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float iconW = dpF(110f);
            float iconH = dpF(62f);
            float left = (getWidth() - iconW) / 2f;
            float top = (getHeight() - iconH) / 2f;
            iconRect.set(left, top, left + iconW, top + iconH);
            float radius = dpF(12.5f);

            boolean focused = isFocused();

            if (focused) {
                haloRect.set(iconRect.left - dpF(3.2f), iconRect.top - dpF(3.2f),
                        iconRect.right + dpF(3.2f), iconRect.bottom + dpF(3.2f));
                canvas.drawRoundRect(haloRect, radius + dpF(2.5f), radius + dpF(2.5f), backHaloPaint);
            } else {
                haloRect.set(iconRect.left, iconRect.top + dpF(2f), iconRect.right, iconRect.bottom + dpF(2f));
                canvas.drawRoundRect(haloRect, radius, radius, dropShadowPaint);
            }

            int saveCount = canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(iconRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);
            drawBannerTileArtwork(canvas, iconRect, item);
            canvas.restoreToCount(saveCount);

            if (focused) {
                borderPaint.setStrokeWidth(dpF(2.2f));
                borderPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(iconRect, radius, radius, borderPaint);
            } else {
                borderPaint.setStrokeWidth(dpF(0.8f));
                borderPaint.setColor(Color.argb(42, 255, 255, 255));
                canvas.drawRoundRect(iconRect, radius, radius, borderPaint);
            }
        }
    }

    /**
     * Draws the 16:9 TV Banner Tile Artwork inside `bounds`:
     * 1. FIRST checks for the official 320x180 Leanback Banner inside the installed APK on the TV (`getOrLoadBanner(item.pkg)`)
     *    and center-crops it edge-to-edge (just like CleanYourTV's `AppAdapter`), so Apple TV, Disney+, YouTube, Netflix,
     *    Prime Video, Hulu, Spotify, Crunchyroll, Willow, Peacock, etc. use their REAL official brand graphics!
     * 2. If an app has no wide banner (e.g. a system app that only ships a square icon), draws its official app icon on a dark plate.
     * 3. For HDMI 1/2/3 and Launcher Settings (or any uninstalled app), renders clean vector artwork.
     */
    private void drawBannerTileArtwork(Canvas canvas, RectF bounds, CatalogItem item) {
        // 1. Official 16:9 TV Banner from the installed APK on the Fire TV
        if (item.pkg != null) {
            Drawable officialBanner = getOrLoadBanner(item.pkg);
            if (officialBanner != null) {
                drawCenterCropDrawable(canvas, bounds, officialBanner);
                return;
            }
            // 2. Official App Icon fallback (if the package is installed but only ships a square icon, e.g. system Appstore)
            Drawable officialIcon = getOrLoadIcon(item.pkg);
            if (officialIcon != null && !"appstore".equals(item.id)) {
                Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgPaint.setColor(Color.parseColor("#181C24"));
                canvas.drawRect(bounds, bgPaint);
                float side = bounds.height() * 0.65f;
                int l = Math.round(bounds.centerX() - side / 2f);
                int t = Math.round(bounds.centerY() - side / 2f);
                officialIcon.setBounds(l, t, Math.round(l + side), Math.round(t + side));
                officialIcon.draw(canvas);
                return;
            }
        }

        // 3. Vector artwork for HDMI inputs, Launcher Settings, and Appstore
        String id = item.id;
        int hdmiIndex = item.hdmiIndex;

        float left = bounds.left;
        float top = bounds.top;
        float w = bounds.width();
        float h = bounds.height();
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float s = Math.min(w / 110f, h / 62f);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextAlign(Paint.Align.CENTER);

        RectF r = new RectF();

        if ("appstore".equals(id)) {
            fill.setShader(new LinearGradient(left, top, left, bounds.bottom,
                    Color.parseColor("#253346"), Color.parseColor("#121A24"), Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, fill);
            fill.setShader(null);

            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            text.setTextSize(13.5f * s);
            canvas.drawText("appstore", cx, cy - 1.2f * s, text);

            stroke.setColor(Color.parseColor("#FF9900"));
            stroke.setStrokeWidth(2.5f * s);
            r.set(cx - 19f * s, cy - 2.0f * s, cx + 19f * s, cy + 12.5f * s);
            canvas.drawArc(r, 25f, 130f, false, stroke);

        } else if (id != null && id.startsWith("hdmi")) {
            fill.setShader(new LinearGradient(left, top, left, bounds.bottom,
                    Color.parseColor("#2B3140"), Color.parseColor("#161922"), Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, fill);
            fill.setShader(null);

            stroke.setColor(Color.argb(240, 255, 255, 255));
            stroke.setStrokeWidth(2.0f * s);
            r.set(cx - 13.5f * s, cy - 15.0f * s, cx + 13.5f * s, cy + 0.5f * s);
            canvas.drawRoundRect(r, 2.8f * s, 2.8f * s, stroke);
            canvas.drawLine(cx - 6.5f * s, cy + 4.2f * s, cx + 6.5f * s, cy + 4.2f * s, stroke);
            canvas.drawLine(cx, cy + 0.5f * s, cx, cy + 4.2f * s, stroke);

            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            text.setLetterSpacing(0.08f);
            text.setTextSize(11.2f * s);
            canvas.drawText("HDMI " + hdmiIndex, cx, cy + 17.5f * s, text);

        } else {
            // macOS Launchpad / App Manager 16:9 Tile (3x3 Colorful App Grid Icon)
            fill.setShader(new LinearGradient(left, top, left, bounds.bottom,
                    Color.parseColor("#3B4358"), Color.parseColor("#1D222E"), Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, fill);
            fill.setShader(null);

            int[] dotColors = {
                    Color.parseColor("#FF5F56"), Color.parseColor("#38BDF8"), Color.parseColor("#22C55E"),
                    Color.parseColor("#FACC15"), Color.parseColor("#A855F7"), Color.parseColor("#FB923C"),
                    Color.parseColor("#EC4899"), Color.parseColor("#60A5FA"), Color.parseColor("#E2E8F0")
            };
            float spacingX = 12.5f * s;
            float spacingY = 10.5f * s;
            float dotW = 4.2f * s;
            float dotH = 3.4f * s;
            int idx = 0;
            for (int row = -1; row <= 1; row++) {
                for (int col = -1; col <= 1; col++) {
                    fill.setColor(dotColors[idx++]);
                    float dx = cx + col * spacingX;
                    float dy = cy + row * spacingY;
                    r.set(dx - dotW, dy - dotH, dx + dotW, dy + dotH);
                    canvas.drawRoundRect(r, 1.8f * s, 1.8f * s, fill);
                }
            }
        }
    }

    /**
     * Draws a Drawable using center-crop scaling to completely fill `bounds` without distortion.
     */
    private void drawCenterCropDrawable(Canvas canvas, RectF bounds, Drawable d) {
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            d.setBounds(Math.round(bounds.left), Math.round(bounds.top),
                    Math.round(bounds.right), Math.round(bounds.bottom));
            d.draw(canvas);
            return;
        }
        float viewW = bounds.width();
        float viewH = bounds.height();
        float scale = Math.max(viewW / dw, viewH / dh);
        float scaledW = dw * scale;
        float scaledH = dh * scale;
        int left = Math.round(bounds.centerX() - scaledW / 2f);
        int top = Math.round(bounds.centerY() - scaledH / 2f);
        int right = Math.round(left + scaledW);
        int bottom = Math.round(top + scaledH);
        d.setBounds(left, top, right, bottom);
        d.draw(canvas);
    }

    /**
     * Opens the in-launcher App Manager Modal where the user can check/uncheck which apps/inputs
     * appear on the Home Screen.
     */
    public void showAppManagerDialog() {
        final Context ctx = getContext();
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout overlay = new FrameLayout(ctx);
        overlay.setBackgroundColor(Color.argb(195, 6, 8, 13));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(242, 18, 22, 31));
        cardBg.setCornerRadius(dpF(22f));
        cardBg.setStroke(dp(1), Color.argb(45, 255, 255, 255));
        card.setBackground(cardBg);
        card.setPadding(dp(26), dp(22), dp(26), dp(22));

        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(VERTICAL);
        TextView titleView = new TextView(ctx);
        titleView.setText("Manage Home Screen Apps");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleCol.addView(titleView);

        TextView subView = new TextView(ctx);
        subView.setText("Select which pre-installed apps and HDMI inputs appear in your bottom dock");
        subView.setTextColor(Color.argb(145, 255, 255, 255));
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subView.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subView);

        LayoutParams titleColLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        headerRow.addView(titleCol, titleColLp);

        final TextView amazonBtn = new TextView(ctx);
        amazonBtn.setText("Exit to Amazon TV");
        amazonBtn.setTextColor(Color.parseColor("#FDE68A"));
        amazonBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        amazonBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        amazonBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        amazonBtn.setFocusable(true);
        amazonBtn.setClickable(true);
        final GradientDrawable amzBgNormal = new GradientDrawable();
        amzBgNormal.setColor(Color.argb(40, 245, 158, 11));
        amzBgNormal.setCornerRadius(dpF(10f));
        amzBgNormal.setStroke(dp(1), Color.argb(90, 251, 191, 36));

        final GradientDrawable amzBgFocused = new GradientDrawable();
        amzBgFocused.setColor(Color.argb(95, 245, 158, 11));
        amzBgFocused.setCornerRadius(dpF(10f));
        amzBgFocused.setStroke(dp(2), Color.WHITE);

        amazonBtn.setBackground(amzBgNormal);
        amazonBtn.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                amazonBtn.setBackground(hasFocus ? amzBgFocused : amzBgNormal);
            }
        });
        amazonBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                HomeInterceptorService.switchToAmazonLauncher(ctx);
            }
        });
        LayoutParams amzLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        amzLp.rightMargin = dp(8);
        headerRow.addView(amazonBtn, amzLp);

        final TextView tvSettingsBtn = new TextView(ctx);
        tvSettingsBtn.setText("TV System Settings");
        tvSettingsBtn.setTextColor(Color.WHITE);
        tvSettingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvSettingsBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvSettingsBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        tvSettingsBtn.setFocusable(true);
        tvSettingsBtn.setClickable(true);
        final GradientDrawable tvSetBgNormal = new GradientDrawable();
        tvSetBgNormal.setColor(Color.argb(35, 255, 255, 255));
        tvSetBgNormal.setCornerRadius(dpF(10f));
        tvSetBgNormal.setStroke(dp(1), Color.argb(60, 255, 255, 255));

        final GradientDrawable tvSetBgFocused = new GradientDrawable();
        tvSetBgFocused.setColor(Color.argb(75, 255, 255, 255));
        tvSetBgFocused.setCornerRadius(dpF(10f));
        tvSetBgFocused.setStroke(dp(2), Color.WHITE);

        tvSettingsBtn.setBackground(tvSetBgNormal);
        tvSettingsBtn.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                tvSettingsBtn.setBackground(hasFocus ? tvSetBgFocused : tvSetBgNormal);
            }
        });
        tvSettingsBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (topRightTargetView instanceof LiquidGlassStatusBar) {
                    ((LiquidGlassStatusBar) topRightTargetView).openSystemSettings();
                }
            }
        });
        LayoutParams tvSetLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tvSetLp.rightMargin = dp(8);
        headerRow.addView(tvSettingsBtn, tvSetLp);

        final TextView doneBtn = new TextView(ctx);
        doneBtn.setText("Done");
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        doneBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        doneBtn.setPadding(dp(18), dp(8), dp(18), dp(8));
        doneBtn.setFocusable(true);
        doneBtn.setClickable(true);
        final GradientDrawable doneBgNormal = new GradientDrawable();
        doneBgNormal.setColor(Color.argb(35, 255, 255, 255));
        doneBgNormal.setCornerRadius(dpF(10f));
        doneBgNormal.setStroke(dp(1), Color.argb(60, 255, 255, 255));

        final GradientDrawable doneBgFocused = new GradientDrawable();
        doneBgFocused.setColor(Color.argb(75, 255, 255, 255));
        doneBgFocused.setCornerRadius(dpF(10f));
        doneBgFocused.setStroke(dp(2), Color.WHITE);

        doneBtn.setBackground(doneBgNormal);
        doneBtn.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                doneBtn.setBackground(hasFocus ? doneBgFocused : doneBgNormal);
            }
        });
        doneBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        headerRow.addView(doneBtn, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        card.addView(headerRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout gridContainer = new LinearLayout(ctx);
        gridContainer.setOrientation(VERTICAL);
        LayoutParams gridContainerLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        gridContainerLp.topMargin = dp(16);

        List<CatalogItem> toggleableItems = new ArrayList<>();
        for (CatalogItem item : catalog) {
            if (item.itemType != TYPE_SETTINGS) {
                toggleableItems.add(item);
            }
        }

        int columns = 3;
        LinearLayout currentRow = null;
        View firstToggleCard = null;

        for (int i = 0; i < toggleableItems.size(); i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(ctx);
                currentRow.setOrientation(HORIZONTAL);
                LayoutParams rLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rLp.topMargin = (i == 0) ? 0 : dp(8);
                gridContainer.addView(currentRow, rLp);
            }

            final CatalogItem item = toggleableItems.get(i);
            ToggleCardView toggleCard = new ToggleCardView(ctx, item);
            LayoutParams cellLp = new LayoutParams(0, dp(48), 1f);
            if (i % columns > 0) {
                cellLp.leftMargin = dp(8);
            }
            currentRow.addView(toggleCard, cellLp);
            if (firstToggleCard == null) {
                firstToggleCard = toggleCard;
            }
        }

        int remainder = toggleableItems.size() % columns;
        if (remainder != 0 && currentRow != null) {
            for (int pad = remainder; pad < columns; pad++) {
                View spacer = new View(ctx);
                LayoutParams cellLp = new LayoutParams(0, dp(48), 1f);
                cellLp.leftMargin = dp(8);
                currentRow.addView(spacer, cellLp);
            }
        }

        card.addView(gridContainer, gridContainerLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(680),
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

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                rebuildDock("launchersettings");
            }
        });

        dialog.show();
        if (firstToggleCard != null) {
            firstToggleCard.requestFocus();
        }
    }

    /**
     * Toggleable App/Input Row inside the "Manage Home Screen Apps" Modal.
     */
    private class ToggleCardView extends LinearLayout {

        private final CatalogItem item;
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final CheckBoxView checkBoxView;

        ToggleCardView(Context context, final CatalogItem item) {
            super(context);
            this.item = item;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setWillNotDraw(false);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setFocusable(true);
            setFocusableInTouchMode(false);
            setClickable(true);
            setPadding(dp(10), 0, dp(12), 0);

            MiniTilePreview icon = new MiniTilePreview(context, item);
            LayoutParams iconLp = new LayoutParams(dp(48), dp(27));
            iconLp.rightMargin = dp(10);
            addView(icon, iconLp);

            TextView label = new TextView(context);
            label.setText(item.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setSingleLine(true);
            LayoutParams labelLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            addView(label, labelLp);

            checkBoxView = new CheckBoxView(context);
            addView(checkBoxView, new LayoutParams(dp(18), dp(18)));

            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    invalidate();
                }
            });

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    item.enabled = !item.enabled;
                    savePreferences();
                    checkBoxView.invalidate();
                    invalidate();
                }
            });
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                performClick();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = dpF(1.5f);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float r = dpF(11f);

            boolean focused = isFocused();
            boxPaint.setStyle(Paint.Style.FILL);
            borderPaint.setStyle(Paint.Style.STROKE);

            if (focused) {
                boxPaint.setColor(Color.argb(45, 255, 255, 255));
                boxPaint.setShadowLayer(dpF(6f), 0f, 0f, Color.argb(140, 255, 255, 255));
                canvas.drawRoundRect(rect, r, r, boxPaint);

                borderPaint.setStrokeWidth(dpF(1.6f));
                borderPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(rect, r, r, borderPaint);
            } else {
                boxPaint.setColor(item.enabled ? Color.argb(24, 255, 255, 255) : Color.argb(12, 255, 255, 255));
                boxPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
                canvas.drawRoundRect(rect, r, r, boxPaint);

                borderPaint.setStrokeWidth(dpF(1f));
                borderPaint.setColor(item.enabled ? Color.argb(60, 255, 255, 255) : Color.argb(25, 255, 255, 255));
                canvas.drawRoundRect(rect, r, r, borderPaint);
            }
            setAlpha(item.enabled || focused ? 1.0f : 0.55f);
            super.onDraw(canvas);
        }

        private class MiniTilePreview extends View {
            private final CatalogItem previewItem;
            private final RectF bounds = new RectF();
            private final Path clip = new Path();

            MiniTilePreview(Context context, CatalogItem previewItem) {
                super(context);
                this.previewItem = previewItem;
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                bounds.set(0, 0, getWidth(), getHeight());
                int sc = canvas.save();
                clip.reset();
                clip.addRoundRect(bounds, dpF(5.5f), dpF(5.5f), Path.Direction.CW);
                canvas.clipPath(clip);
                drawBannerTileArtwork(canvas, bounds, previewItem);
                canvas.restoreToCount(sc);
            }
        }

        private class CheckBoxView extends View {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF box = new RectF();
            private final Path checkPath = new Path();

            CheckBoxView(Context context) {
                super(context);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float pad = dpF(1.5f);
                box.set(pad, pad, getWidth() - pad, getHeight() - pad);
                float rad = dpF(5f);

                if (item.enabled) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.WHITE);
                    canvas.drawRoundRect(box, rad, rad, p);

                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(Color.parseColor("#0A0D14"));
                    p.setStrokeWidth(dpF(2f));
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);

                    float w = getWidth();
                    float h = getHeight();
                    checkPath.reset();
                    checkPath.moveTo(w * 0.27f, h * 0.52f);
                    checkPath.lineTo(w * 0.44f, h * 0.69f);
                    checkPath.lineTo(w * 0.74f, h * 0.33f);
                    canvas.drawPath(checkPath, p);
                } else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dpF(1.4f));
                    p.setColor(Color.argb(90, 255, 255, 255));
                    canvas.drawRoundRect(box, rad, rad, p);
                }
            }
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
}
