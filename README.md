# TilePivot

Formerly X360Reborn (renamed in v0.7.0 for Google Play trademark hygiene; see docs/RE_NOTES.md).

An Android home-screen launcher in the style of the Xbox 360 "Metro" dashboard (2.0.17559).

It is a clean-room rebuild. Layout sizes, colours and animation timings were measured from the
dashboard's own UI scenes (see [docs/RE_NOTES.md](docs/RE_NOTES.md)), but no Microsoft code, images,
sounds or fonts are included. All graphics are drawn in code.

## Install

1. Download `TilePivot-<version>.apk` from [Releases](../../releases).
2. Open it on your phone and allow installing from that source when Android asks.
3. Press Home and choose **TilePivot**. To change it later, go to Settings → Apps → Default apps → Home app.

Requires Android 13 or newer. Release APKs are signed with the Reborn release key, so a release won't
install over a build you made yourself with the debug key; uninstall that one first.

## Features

- Pivot strip ("home / games / tv & movies / music / apps / settings") over swipeable channel pages
- Home grid from the dashboard's Template1HD layout, with a hero tile for your pinned or most recent app
- Green live tiles with the dashboard's focus animation (1.08x scale, flash, corner sheen, shadow)
- Phone portrait layout, plus three tile sizes (pinch, or the "tile size" tile in settings)
- D-pad / gamepad support (L1/R1 switch channels, A launch, B home, X options)
- Long-press a tile to pin it, remove it from recents, open app info or uninstall
- App search: the search tile at the head of the apps channel, the Search key, or typing a letter opens a live-filtered app list (starts-with matches first)
- Folders: long-press an app to start a folder or add it to one; each folder gets its own channel and a tile in apps, and can be renamed or deleted from its tile's long-press menu (apps are never uninstalled)
- Themes: five built-in colour themes (Xbox Green, Midnight, Crimson, Violet, Carbon) picked from the "theme" tile in settings; Xbox Green is the original measured dashboard look and stays the default
- More customization in settings: background style (gradient, solid or dimmed), a 12/24-hour hub clock, and show/hide toggles for the tv & movies and music channels
- Game cover art: Games on Demand / XBLA packages show the thumbnail from their own header. For any game you can add your own cover: an image with the same name beside the game file (`Skate 3.iso` + `Skate 3.png`/`.jpg`/`.webp`), or `cover.png` / `boxart.jpg` / `folder.jpg` inside a `default.xex` game folder
- Screen-reader support: TalkBack reads every tile and channel, double-tap launches, double-tap-and-hold opens a tile's options, and a two-finger swipe pages the hub
- Settings backup: "back up settings" in the settings channel saves pins, folders, recents, theme and layout to a file; "restore settings" brings them back (for example when switching between a self-built copy and a release, which needs an uninstall)
- Work-profile apps are listed alongside personal ones with their briefcase badge, and launch and app-info act on the right profile; Android 15 Private Space apps stay hidden
- Xbox 360 games via Xendroid+: your own game dumps (ISO, ZAR, Games on Demand/XBLA containers, or folders containing default.xex) in a folder you pick once from the settings channel ("xbox 360 games: pick a games folder"; several folders are fine) appear in the games channel and launch straight into the Xendroid+ emulator. TilePivot asks for no permissions: it only gets read access to the folders you pick. Xendroid+ is a separate app you install yourself, and it needs a real file path, so pick folders from device storage or an SD card (Downloads-provider and cloud folders show their games but cannot launch them)

## Build

Needs the Android SDK and JDK 17.

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then pick TilePivot under Settings → Apps → Default apps → Home app.

## Reverse-engineering tools

`re/tools` holds the Python scripts used to read the update package (XEX2, XUIZ archives, STFS packages,
XUI scene trees). They operate on files you supply yourself; no firmware is distributed here.

## Licence

MIT, see [LICENSE](LICENSE). This covers this repository's own code only; Xbox and the dashboard design belong to Microsoft.
