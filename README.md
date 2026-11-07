# Saifil TV Launcher

Custom macOS / Apple TV Liquid Glass 4K launcher for Insignia Fire TV (`com.saifil.tvlauncher`).

## Features
- **Fullscreen Hardware-Accelerated Video Wallpaper**: Loops 1080p/4K `.mp4` wallpapers from `bg/` at `0.75x` cinematic speed with a hardware decoder sleep/wake watchdog.
- **macOS Single-Line Bottom Dock (`AppDockView`)**: Loads official `16:9` widescreen Leanback TV banners directly from installed APKs with pure-white blurred halo focus glow, concentric rounded shelf clipping, and built-in HDMI 1 / 2 / 3 switching.
- **Top-Right Liquid Glass Status Bar (`LiquidGlassStatusBar`)**: Live weather indicator, Wi-Fi signal bars, Bluetooth status, 1-click native Fire TV Settings (`MainSettingsActivity`), and date/time clock.
- **Instant Home Button Interception & Fail-Safe (`HomeInterceptorService`)**:
  - Intercepts `KEYCODE_HOME` and `com.amazon.tv.launcher` with a `1×1px` `TYPE_APPLICATION_OVERLAY` exemption to bypass Fire OS's 5-second `stopAppSwitches()` delay.
  - Includes an **"Exit to Amazon TV"** button in Launcher Settings and an emergency **5× Quick `BACK`** remote fail-safe to switch back to the stock Fire TV launcher anytime without a laptop.

## Build & Deploy
```bash
./build_and_deploy.sh
```
