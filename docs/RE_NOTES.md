# X360 Reborn: RE notes

Source: Xbox 360 system update 2.0.17559 (final Metro dash), the `$SystemUpdate` folder of the USB update package.
Everything extracted lands in `re/out` (gitignored, reference only, never shipped).

## Pipeline (re/tools, run with `re/.venv/Scripts/python -I`)
1. `xex2.py <xex> <out>`: XEX2 to PE + resources. HDD modules (`dash.xex`, `*.lex`, Guide.*) are unencrypted with basic compression: OK.
   Encrypted modules need the retail XEX key, which is not shipped here: set `XEX_RETAIL_KEY` (32 hex chars) yourself.
   Flash modules in the SU package (`xam`, `hud`, `huduiskin`, `bootanim`, ...) use LZX (comp=2). **TODO: `lzx.py` decoder.**
2. `xzp.py <XUIZ> <out>`: XUIZ archives (u32 toc_size @0x10, u16 count @0x14, entries {u32 size, u32 off, u8 len, name}, data base 0x16+toc).
   controlp names use `..\`, mapped to `_parent/`.
3. `stfs.py <pkg> <out>`: PIRS/LIVE use block shift 0 (only CON doubles hash tables).
4. XUR v8 to XUI XML: SGCSam/XUIHelper CLI (GPL, built separately and not included here; needs `DOTNET_ROLL_FORWARD=Major`, **absolute paths only**).
5. Lua (.lub = stock Lua 5.1 big-endian): `java -jar re/bin/unluac.jar` (2023_12_24). 354/355 decompile with names.

## What is where
- Home hub: `dash.hubui/hubscene.xur`, `hubhomepage.xur`; logic in `lua/dash.hubapp/HubScene.lua`, `ChannelList.lua`, `PagedHorizonManager.lua` / `ContinuousHorizonManager.lua` (horizontal scroll).
- Tiles: `dash.slots/*SlotScene.xur`, `LiveTileSlot.xur`. Shared skin: `dash.dashuisk/skin.xur`.
- Control pack (`dash.controlp`): AuraScene (background aura_background.jpg/aura_floor.png), HubSceneStandard/Homepage, LegendScene + Legend_*.png, PanelScene, nav sounds `snd_*.xma` (channelup/down, panelleft/right, buttonselect/back...).
- Root: `dash.dashmain/DashBkgnd.xur` = 1280x720 XuiPerspectiveScene (Aura, LiveVision overlay @0.1 opacity, BackgroundSound loop, DashCursor 64x64, WaitCursor 72x72 @604,324).
- Themes: DashPack `FFFE07DF00000006/Theme0..2`: parallax layers (Background/Midground/Foreground/Banner), Wallpaper1-4 are JPEGs, `parameters.ini` (SphereColor, avatar lighting).
- Font: `su20076000/$flash_SegoeXbox-Light.xtt` (MS font, can't ship; pick a free substitute).
- Other packs: ParityPack (01), AvatarAsset (02), NuiPack/Kinect (08).

## Measurements (1280x720 design space; XUI timeline frames, assumed 60 fps; verify against footage)

Tool: `python -I re/tools/xuitree.py <file.xui> [--timelines]`.

### Hub chrome (`dash.controlp/HubSceneStandard.xui`, `HubSceneHomepage.xui`)
- `_PanelsContainer`: x 0, y 181, 1280x400. Channel panels scroll horizontally inside it (`ControlPackHorizonControl`, native).
- `_Twist` (pivot tab strip): 153,97, 900x84 (937 on the homepage). `_SceneTitle` 304,60 880x64; subtitle 304,116.
- Hover chrome (Kinect): back 55,27; home 610,27; search 1165,27; 60x60 each.
- `PreviewText` 90,605 1097x41. Legend buttons 420x47 (A 427,-57 · X 0,-57 · Y 19,-85; bottom-anchored).
- `Aura` background: `BackgroundImagePanScale` 1.3 (parallax pan as the channel changes).

### Twist (`skin.xui` → `HubScene_Twist`, `HubScene_TwistButton`, `HubScene_TwistSelButton`)
- Items 152x84 on a **188 px pitch**, selected item at x=0 of the grid, ±9 neighbours.
- Text colour #EBEBEB, 24 pt, Segoe Light. Unselected opacity **0.5**, selected 1.0.
- Hover/focus: text fades 0.5→0 while a bold copy fades 0→0.92 and scale x 0.9→1.0 (20 frames). Press: scale 0.9 at f+7, back by f+15.
- Selected glow (nine-grid 640x54 at -20,18): opacity 0 → 0.9 over 12 f, → 0 by f63, then loops 0 → 0.7 → 0 (90 f each half).
- Sounds: `roll_on` on hover, `snd_buttonselect` on press.

### Channel templates (`dash.epix/*.xui`). Slot rects as x,y,w,h. Gutter **4 px**; rows at y 0/134/268.
- `Template1HD` (online Home) 822x398: 1:0,0,174,130 · 2:178,0,466,264 (hero) · 3:648,0,174,130 · 4:0,134,174,130 · 5:648,134,174,130 · 6:0,268,174,130 · 7:178,268,231,130 · 8:412,268,231,130 · 9:648,268,174,130.
- `TemplateOffline1HD` 818x398: 1:0,0,174,130 · 2:178,0,640,398 · 3:0,134,174,130 · 4:0,268,174,130.
- `Template3HD` 943x398: 263x197 media tiles, 231x130 wide, 174x130 small.
- Offline Home definition: `dash.epix/dashhome-offline.xml`. Channels in order: HOME, social, GAMES, VIDEO, APPS, SETTINGS. Home slots: disc tray, Connect to Live, Pins, Recent.

### Tiles (`skin.xui`)
- `LiveTile` 148x148: fill **#008A00** over the glass texture `postcard_placeholder_Glass.png`; icon 48x48 at 50,40; label 128x47 at 10,97, #EBEBEB; live number 36 pt.
- `LiveTileSmall` 148x97: icon 28x28; label at 10,46.
- `mobyslot` (media slot): fill #0F0F0F; bottom gradient from y 170; primary text 17 pt #EBEBEB at 20,258; secondary 16 pt #8C8C8C at 20,284.
- `HubSlotContainer` (every hub slot) focus = frames 19→35 (16 f ≈ 267 ms, linear):
  `_slotGroup` scale 1.0→**1.08**; `Shadow` nine-grid (-31,-31, +61 px) opacity 0→0.8 at the same scale;
  `figFlash` white 0→0.08 (f+7) →0 (f+16) while scaling 1.05→1.08; `imgCornerHighlight` (`focus_state_white.png`) 0→0.44.
  Press 35→55: scale 1.08→1.0 (8 f)→1.08 (12 f), flash 0.08 at f43. KillFocus 56→70: back to 1.0, everything to 0.
  Disabled: group opacity 0.3. Sounds `slot_roll_on` (focus), `snd_buttonselect` (press).
- `LightweightContainerScene` "peek": shade 0→0.6 over 29 f.

## App v0.1 (2026-10-21)
- XUI batch: 779/846 converted; the 67 failures are Zune/NUI/biometric scenes, none hub-related.
- `app/`: Kotlin custom View in 1280x720 design space (fit + centre). Channels: home (Template1HD; pins → launcher recents → A-Z; hero = first), games / tv & movies / music (by `ApplicationInfo.category`, hidden when empty), apps (5x3 column-major pages), settings (system intents).
- Deviations: Roboto Light for Segoe; procedural aura, glass and focus sheen (no MS bitmaps); tile icons 56 design px (dash used 48) for phone legibility; horizon scroll 450 ms ease-out cubic (native control, not measured); twist items spaced by text width + 36 instead of a fixed 188 pitch.
- Input: d-pad / L1 R1 / A / B / X (options), swipe pages, tap, long-press (pin, remove recent, app info, uninstall).
- Verified on emulator-5554: load, d-pad focus anim, swipe + twist slide, R1, tap-launch (Chrome), portrait.

- Portrait (`Layout.portrait`, our own design; the dash is landscape-only): 420-wide design space (wider on short screens),
  Template1HD reflowed to 2 columns under a 352x198 hero (`Metrics.HOME_PORTRAIT`, same slot indices), list pages 2x5 row-major,
  twist + panels block centred vertically. The hub is rebuilt on rotation (`HomeView.onLayout`). Verified on the emulator and the phone.
- Tile size (`TileSize` large/medium/small, saved in prefs): portrait 2/3/4 columns with the hero capped at two rows and shortcuts
  following the last app (`Template.compact`); landscape zooms the design space 1.0/1.25/1.5 and widens it to the screen, with
  extra Template1HD columns on home and bigger list pages. Changed by pinch (live preview, snaps at <0.85x / >1.18x) or the
  "tile size" tile in settings.

### Scene transitions (`dash.hubui/hubscene.xur`, timeline on the `Hub` element, pivot 640,360)
| Named frames | Use | Hold | Ramp (linear) |
|---|---|---|---|
| TransTo 1-46 | cold start | 4 f at 0.8x, alpha 0 | 41 f to 1.0x, alpha 1 |
| TransFrom 47-75 | leaving for an app | none | 28 f to 1.5x, alpha 0 |
| TransBackTo 76-126 | returning from an app | 8 f at 1.5x, alpha 0 | 42 f to 1.0x, alpha 1 |
| TransBackFrom 127-156 | (backing out of the hub) | none | 29 f to 0.7x, alpha 0 |

The app plays TO on start, FROM after the press dip (the app opens 12 frames in with a plain fade), and BACK_TO
on resume after a launch. The system splash is blanked (aura-coloured, no icon) so TO is what you see.

### Flash modules (LZX)
`re/tools/lzx.py` decodes XEX "normal" compression (CAB-style LZX: 32 KiB frames, 24-bit block sizes,
16-bit realignment per frame). Verified on bootanim, xam (6 MB, ~2 s), hud, huduiskin.
- `bootanim.dll` ("Xbox360 Startup Animation") renders the boot animation in code (D3D), so there are no frames
  to measure. Its `audio` resource is the startup sound (XMA2, 48 kHz): Microsoft's, never shipped.
- `xam` holds the Guide XUI (`skin`, `xam`, `shrdres`, `gamercrd`…); `huduiskin` is resource-only (no PE header).

## App v0.4.0 (2026-11-04): themes, customization, E2E pass
- Theme system (`home/Theme.kt`): a Theme carries the tile fill, the five aura stops, the floor
  colour, text/text-dim and the hero slot fill; the `Metrics` colour properties delegate to
  `Themes.active`, so every hub drawing path (tiles, hero, glyph cut-outs, twist, clock) follows
  the theme. Five built-ins: Xbox Green (the measured skin.xui constants above; still the default,
  look unchanged), Midnight, Crimson, Violet, Carbon. The white sheen/shadow alphas stay fixed
  (they are light, not colour). Picked from the settings channel's "theme:" tile, which opens a
  radio-list dialog; the choice applies immediately and persists in prefs (`theme`).
- More settings tiles, all internal actions, persisted and applied on tap: "background:" cycles
  the aura presentation (gradient = the measured ramp, solid = the ramp's mid colour flat,
  dimmed = the ramp darkened to 55%); "clock:" toggles the hub clock between 12-hour (dash style)
  and 24-hour; "tv & movies:" / "music:" show or hide those two channels in the pivot
  (`HubBuilder.hiddenChannels`; home, games, apps, folder channels and settings always stay).
- A palette glyph (white disc, tile-colour dots) marks the theme tile; the other new tiles use
  the standard gear glyph.
- Unit tests: 29 total (HubTest 21, including theme registry, BgStyle round-trip and hidden
  channels; GamesTest 8), all passing.
- End-to-end pass on the emulator (Medium_Phone_API_36.1) before shipping, baseline flows on
  0.3.1 and the new features on the 0.4.0 build: cold boot and every channel; search by tile,
  Search key and typed letter, with a result launch; the full folder flow (create, add, rename,
  remove member, last-member auto-delete, delete with confirmation); pin, unpin and
  remove-from-recent; seeded Xbox 360 games (ISO/ZAR/GOD/XEX) with the Xendroid+ not-installed
  dialog and its link; gamepad D-pad/A/B/X; persistence of pins, folders and every new setting
  across force-stop; every settings tile's target screen; the tile-size cycle through all three
  layouts; landscape rotation; long-list paging; and the no-all-files-access empty state (games
  channel disappears, the settings tile becomes the grant prompt) plus recovery on re-grant.
  No functional defects found. Cosmetic notes: the settings labels for the xbox entry and for
  "background: gradient" ellipsize on the portrait large tile (the toast on tap confirms the
  value); on this heavily-loaded AVD the aura-coloured starting window can sit for several
  seconds before the first hub frame.

## App v0.4.1 (2026-11-05): larger tile icons

- Matt's feedback on 0.4.0: app icons filled only about a third of their tile (Chrome
  measured 32% of tile width on the emulator's medium grid; the portrait hero's icon was
  ~17-21% of the panel). Icon sizing now lives in two pure functions in `Metrics`:
  `gridIconSize(w, h, hasSub)` = 58% of tile width, capped by the height above the label
  block (34, or 50 with a sub line), and `heroIconSize(w, h, hasSub)` = the largest edge
  that fits above the hero's label block (44% of width / 78% of height caps). Standard
  174x130 grid tiles draw at 96 (55% of width; 80 / 46% on two-line tiles where the label
  block caps the height); the portrait phone hero goes 121 -> 194. A literal doubling of
  the hero icon would collide with its label text, so the label block is the hard limit.
- `HomeView.drawSmall` / `drawHero` use the new sizes; the code-drawn glyphs (gamepad,
  gear, search, folder, palette, tile-size) scale off the same size, proportions unchanged.
  Verified on the emulator in all three tile sizes plus the Midnight theme; measured
  after-ratio on the medium apps grid: 55.1% (was 32.2%).
- Icon bitmaps are rasterised larger in `Apps.loadIcons` (density x 96, clamped to
  192..288 px, up from density x 64) so the bigger draws stay sharp; rendering stays on
  the loader thread.
- Unit tests: 31 total (HubTest 23, including grid/hero icon sizing; GamesTest 8), all
  passing.

## Next
- Twist focus glow fine-tuning and channel-switch timing against real footage (the glow and the
  transitions are implemented from the measured scene timelines, but never compared side-by-side
  with a real dash); Kinect/hover chrome not built.
- STFS title parsing, so GOD/XBLA games show their real names instead of content-ID file names.
