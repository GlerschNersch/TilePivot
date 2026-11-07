# TilePivot: RE notes

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

## App v0.4.2 (2026-11-05): full square icons

- Matt's call: drop the OS circle mask so tiles show each app's full square icon art,
  like the real dash's square box art. The circles turned out to be applied twice over:
  `LauncherActivityInfo.getIcon()` hands back the system-shaped icon, and an
  `AdaptiveIconDrawable` clips ITSELF to the system mask path when drawn — so swapping
  the source API alone (tried first) changed nothing on screen.
- `Apps.render` now loads the raw drawable via `PackageManager.getApplicationIcon`
  (LauncherApps icon as fallback for packages visible only to another profile) and
  draws it with `drawFullSquare`: for adaptive icons the background/foreground layers
  are drawn directly at full bounds, skipping the mask; legacy drawables draw as-is.
  The work-profile badge is applied afterwards to a BitmapDrawable of the finished
  art, so badged work icons keep their briefcase without re-introducing the mask.
  Raster size and the 0.4.1 `Metrics` sizing are unchanged; the icon cache is
  in-memory per process, so nothing persistent needed invalidating.
- Verified on the emulator against the 0.4.1 build at identical states (home, games,
  apps at medium tiles): BatHome and Apex Horizon render as full squares; C64U Reborn's
  art is full-bleed; Orbital's own art sits on its designed black square; Chrome keeps
  its round glyph, now on its white adaptive background square instead of a white
  circle. Google's apps show their white background layer in the corners — that is
  the designed full square, previously hidden by the mask. Games channel (drawn
  gamepad glyphs) unchanged.
- Unit tests: 31 total (HubTest 23, GamesTest 8), all passing.

## App v0.4.3 (2026-11-05): filled grid tiles, no grid text

- Matt's call: grid tile artwork fills the whole tile and the app-name text goes.
  `HomeView.drawSmall` now dispatches: settings-channel tiles and the search tile
  keep the classic layout (`drawLabeled` — their labels carry state or name the
  action); everything else goes through `drawFilled`.
- `drawFilled` draws the app's icon over the entire tile rect with cover semantics —
  `Metrics.coverSrc` computes the centred source window (scale to cover, crop the
  overflow, never stretch; a square icon into 174x130 keeps the middle 74.7% of its
  height). No label and no sub are drawn: app names, folder counts and 360-game
  format subs are all gone from grid tiles (the model still carries them; the hero,
  search dialog and long-press menus still use them). Glyph tiles — 360 games,
  folders, the home "all apps"/"settings" shortcuts — keep their drawn glyph on the
  themed tile, now large and centred (`Metrics.gridGlyphSize`, 85% of the short
  side). The hero is unchanged, name and sub included.
- The work-profile badge is no longer baked into the icon in `Apps.render` (the
  cover crop would slice it). `Apps` renders it separately on a transparent canvas
  (`renderBadge` / `Apps.badge`) and `HomeView.drawBadge` overlays it at the filled
  tile's bottom-right corner, and at the hero icon's corner as before. The emulator
  has no work profile, so the badge path is code-verified only, as in 0.4.0.
- Icon raster raised in `Apps.iconPx` (density x 160, clamped 288..448, from
  density x 96 / 192..288): a filled LARGE portrait tile is ~450 device px on the
  emulator and ~530 on Matt's phone. The 448 cap bounds the cache at ~0.8 MB/icon.
- Unit tests: 34 total (HubTest 26, including coverSrc square/tall/wide/same-aspect
  and the filled-tile glyph size; GamesTest 8), all passing.

## App v0.5.0 (2026-11-05): backlog burn-down

Four items, one commit each, in order:

- **Readable settings labels.** The "xbox 360 games: N found" tile ellipsized at every tile
  size (found by the 0.4.3 verification suite). `HomeView.drawLabeled` now shrink-to-fits
  label text (`Metrics.labelScale`, floored at 0.62x) and wraps at the ": " state colon
  (`Metrics.splitLabel`) when even the floor cannot fit: "xbox 360 games:" / "grant folder
  access" renders on two lines at full size. Verified on the emulator at small, medium and
  large, and for the wrapped no-access state.
- **STFS title parsing.** `GameScanner.stfsTitle` reads the display name from the STFS
  header: UTF-16BE at 0x411, nine language slots of 0x200 bytes stride (name field 0x80),
  first non-blank slot wins. Layout validated against four real PIRS packages from the
  17559 system update (names at slot 0; publisher lands exactly at 0x411 + 9x0x200 =
  0x1611). Any failure (bad magic, truncated header, blank/control-char title) falls back
  to the cleaned file name, so hex content-ID files show their real titles while odd
  files behave exactly as before. The parsed name is `GameInfo.name`, so it flows to the
  tile model, the hero, and the Xendroid+ `game_name` extra unchanged. Emulator proof: a
  synthetic LIVE package named `58410A3F` shows as "Apex Velocity" in the games hero.
- **Kinect/hover chrome.** Interpretation, since the notes scope only the geometry: the
  three 60x60 hover targets at back (55,27), home (610,27), search (1165,27) are pointer
  chrome, like the dash's Kinect buttons. `HomeView` draws them (theme colours, glass
  face, the hot one lit) only while a mouse or stylus hovers the hub (`onHoverEvent`,
  150 ms fade in `stepChrome`); clicks there call the same back / home-channel / search
  paths. Touch and gamepad never see them, so the verified focus/press/twist behaviour
  is untouched. Portrait keeps the row at 48 px along the top (`Metrics.hoverChromeBoxes`).
  Verification limit: the test AVD has no pointing device at all (getevent shows only
  multitouch + keyboards; injected mouse events never become a cursor, sendevent is
  permission-denied, adbd cannot root on this production image), so the visible hover
  state is unit- and code-verified only; first live check needs pointer hardware.
- **Animation timing vs real footage.** Measured from a 720p60 capture of a real 17559
  dash ("Xbox 360 Dashboard Update 2025", youtube yEJQg8ygrdQ; 480p30 dash tour
  EAJfPNBprBw as cross-check) with a frame-diff motion curve. Channel switch, three
  clean samples: ~417 ms, ~417 ms, ~467 ms (mean ~434 ms) against the app's 450 ms
  horizon scroll - about 4% off, inside the 15% band, and the velocity profile (peak
  within ~4 frames, smooth decay) matches the implemented ease-out cubic. No isolated
  hub focus step occurs in either capture, so the focus twist (16 frames from the
  HubSlotContainer timeline) is neither confirmed nor refuted by footage and stays as
  measured from the XUR. No animation constants changed.

- Unit tests: 42 total (HubTest 30, GamesTest 12), all passing.

## App v0.5.1 (2026-11-05): channel-entry tile cascade

Matt asked for the tiles to "do something fun" when a channel page arrives. When a
channel becomes active, its landing page's tiles now pop in on a diagonal wave from
the top-left: each tile scales 0.85 -> 1 with a small easeOutBack overshoot (composed
scale peaks ~1.015) and rises 16 design px into place. No alpha fade -- Metro tiles
are opaque and stay solid.

- **Math** is pure in `home/Anim.kt`: delay = (row + column) x 28 ms from the tile's
  grid ranks in its template (the hero always goes first, delay 0), 260 ms per tile,
  so a page settles in roughly 450-600 ms. `HomeView` composes the entry scale/rise
  into the same centre transform as the focus scale, so the focus shadow/sheen/flash
  ride along and stay aligned; input is never gated on the animation.
- **Triggers** (and only these): a navigation that changes the active channel --
  pivot swipe across a channel boundary, L1/R1 or keyboard channel step, twist tap,
  channel shortcut tiles, B returning home -- starting with the horizon scroll, so
  tiles assemble as the page slides in. Within-channel paging never re-triggers.
  A fresh hub rebuild counts as an entry only when the landing page's content
  actually changed (first build, recents reordering after a launch, a pin/folder
  edit); pure relayouts (rotation, tile-size changes) and same-content rebuilds
  (theme changes) do not cascade. A new channel switch mid-cascade restarts the
  wave cleanly on the new channel.
- **Frame budget**: redraws run only while the wave runs (~600 ms max); nothing
  invalidates at idle. The system animator duration scale is honoured -- at 0 the
  tiles simply appear.
- Unit tests: 47 total (HubTest 35, including the cascade delay/progress/bounds;
  GamesTest 12), all passing. Emulator evidence (frame sequences of two channel
  entries) in `~/workspace/x360reborn/shots-phase51/`.

## App v0.6.0 (2026-11-06): games-channel polish

Three improvements from a review of Matt's phone games channel: 13 tiles filling
half the screen, his one 360 game reduced to a generic glyph, and a hero panel
that was mostly empty black. (0.5.2-0.5.4 shipped without notes sections; this
release resumes the habit.)

### Box art for Xbox 360 games, completed

0.5.4 added sidecar art under the game's own file name, plus STFS header
thumbnails. 0.6.0 completes the convention (`GameScanner.artCandidates` /
`resolveArt`, unit-tested):

- Candidate names, in order: the artifact's base name ("Skate 3" for
  Skate 3.iso, or a folder game's folder name), then `cover`, `boxart`,
  `folder` -- each with .jpg, .png, .jpeg, .webp, matched case-insensitively.
- Folders searched, in order: ISO/ZAR look beside the file only. STFS packages
  look beside the package, then one folder up. Folder (default.xex) games look
  inside their own folder, then beside it. A game's own folder always beats
  its parent. With no matching file, an STFS header thumbnail is the fallback.
- Grid tiles cover-fill the art like app icons (0.4.3). The hero now frames
  the WHOLE cover (`Metrics.fitSize`): fit inside the hero's icon area,
  centred, aspect untouched -- the old square draw stretched a 3:4 cover by
  a third. Decoding stays in `Covers`: off-thread, subsampled toward the
  largest drawn edge, 24 MB LRU; any failure leaves the drawn gamepad glyph.

### Sparse channels get bigger tiles

Tile geometry used to be uniform, so the 13-tile games channel filled
three quarters of the panel at Medium (and under half at Small on the
phone). Now a content channel (games / tv & movies / music) or a folder
channel renders at an EFFECTIVE size stepped up from the user's setting by
tile count (`Metrics.boostStepsFor` / `effectiveSize`): 1-6 tiles boost two
steps, 7-18 one step, 19+ none -- capped at LARGE, so a sparse channel at
LARGE is unchanged and a 1-tile folder tops out at hero scale, not beyond.
Home, apps and settings always use the layout's own templates.

`Layout.channelTemplates(size)` builds the boosted templates: the bigger
size's templates scaled so the tiles grow but the page stays inside the
panel -- rows are added until the next would overflow the panel height,
and the scale is capped so a tile never takes more of the screen than it
would at LARGE. Focus geometry, the entry cascade's row/column delays and
hit-testing all read the page's own template, so they follow for free.

Emulator proof (1080x2400, Medium, the seeded 13 games): panel fill went
74.9% -> 98.9% (pixel-measured), spilling 4 games to a second page. The
dense apps channel is pixel-identical to 0.5.4 (0 differing pixels outside
the clock; a first-boot icon-raster wobble on sideloaded test apps was
chased down and reproduced with a pristine 0.5.4 build, so it is the AVD,
not the change).

### Hero ambient backdrop

The hero panel used to be flat dark with the icon floating in the middle
of it. `HomeView.drawAmbience` now fills the panel first with the focused
item's own art, softened: the source bitmap (app icon, box art, or the
glyph rendered on its tile body) is stored at ~1/12 size and drawn back up
cover-style through the filtering bitmap paint, which reads as a blur,
then darkened with a ~60% black scrim; the existing bottom shade and the
crisp art go on top, so the name and sub stay legible even over a white
icon (verified with the Calendar hero). The softened bitmap is cached per
item and theme and recomputed only when its source bitmap changes (a
cover finishing its decode replaces the glyph that stood in for it), so a
settled hero costs one drawBitmap. Because the backdrop is drawn inside
drawHero, the focus twist and the entry cascade carry it with the tile.

Emulator evidence in `~/workspace/x360reborn/shots-phase60/`: the Skate
cover hero (ghosted cover wash behind the framed art), a dark-icon hero
(the wash is near-black there, as it should be), and the white Calendar
hero with fully legible text.

## App v0.6.1 (2026-11-06): one hero geometry for every channel

### The census

The hero panel (the big feature tile at the top of a channel's first page,
`HomeView.drawHero`) did not have one geometry. It was whatever the channel's
first-page template happened to produce, and the sparse-channel boost made
those templates diverge. Measured on the phone (0.6.0): home's hero was
full-width but short (a banner), the games hero was full-width and tall, the
tv & movies and music heroes were tall but inset with side margins and a
lower top edge, and apps/settings had no hero at all (decided: correct, they
are utility grids). The same census reproduced on the AVD at SMALL
(1080x2400, focused hero rects, device px): home (8, 233) to (1073, 629);
games (8, 255) to (1073, 788); tv & movies and music (52, 306) to (1029, 856).

Root cause, in layout terms: the portrait home template's hero is 264 design
units tall at every tile size, the boosted games template scales it by the
boost factor (x1.336 at SMALL, so 352.7), and a +2 boost lands on the LARGE
template (650 wide) scaled by the LARGE cap (x1.848), which is narrower than
the 708-wide panel, so its hero renders inset and, because pages centre
vertically in the panel, lower.

### The unification

`Layout.heroBox` is now the single hero definition every hero-bearing channel
consumes. In portrait it is the games channel's existing hero: full content
width at the +1 boost step's height (the 7-18 tile band). At SMALL on a
1080-wide phone that is Box(0, 0, 708, 352.7) in template space; at LARGE,
where nothing boosts past it, it is the portrait template's own 352x198.
First pages are composited onto it with `Template.withHero`: the hero slot is
replaced by the canonical box, the tile slots keep their sizes, centred in
the page width and shifted so the first row still starts one gutter under
the hero, and the page height becomes the layout's panel height, so the
hero's top edge is the panels' top edge on every channel. The portrait
factory and `Layout.channelTemplates` both route through it, so the dense
path, the boosted path and the home page resolve to the same rect by
construction (unit tests pin the rect identical across home, games,
tv & movies, music and a folder channel at all three tile sizes).

Home composes by construction: its grid simply starts lower (one fewer tile
row on the AVD at SMALL, 9 to 8; paging absorbs the rest). Focus geometry
and hit-testing read the page's own template, so they follow; the entry
cascade's delays derive from slot row/column ranks and are unchanged (hero
is still rank 0).

Folder channels gain a hero: they were the one hero-shaped channel still
built from grid pages. They now build through the same content pages as the
media channels, first member promoted to hero, which also gives the folder
hero the canonical rect. The hero tile carries the member's long-press
options, verified live on the emulator.

Landscape is deliberately not composited: every landscape hero derives from
the single Template1HD slot and scales uniformly with its page, so landscape
heroes already share one definition. Forcing the unscaled canonical box into
a boosted landscape template would overlap the slots beside the hero (the
landscape boost scale can exceed the hero slot's x-offset ratio). If
landscape hero sizes ever diverge per channel, revisit.

### Ambience containment

On the phone, home's ambient wash read as bleeding over the first tile row.
Measured on the AVD: the wash's extent is exactly the hero panel's own extent
(the softened bitmap is drawn into the hero rect, under the panel's
transform). What crosses onto the tile row is the whole focused panel, grown
1.08x like every focused tile, wash included (about 15 px of overhang past
the slot's bottom edge at SMALL). The wash never extends past the panel body
in either build. In 0.6.1 `drawAmbience` additionally clips the bitmap and
scrim to the hero rect explicitly, so the containment is structural rather
than incidental; column profiles through the wash confirm the colour stops
at the panel's bottom edge.

### Emulator proof

AVD 1080x2400 at SMALL; hero rects measured by luminance gradient-peak edge
detection on settled screenshots, within +/-45 px of the layout prediction,
cross-checked against an unfocused hero, which matched its predicted slot
rect to 2 px. After 0.6.1 the focused hero rect is (8, 227) to (1073, 758)
on all five hero-bearing channels (home, games, tv & movies, music, and a
purpose-built folder channel), identical within 1 px on the left, right and
bottom edges and 3 px on the top (the panel's soft shadow). The slot rect
itself is (47, 247) at 985x491 px, i.e. design Box(34, 177.2, 708, 352.7) at
the SMALL layout. Screenshots: `~/workspace/x360reborn/shots-phase61/`
(before/after per channel). Spot checks: DPAD focus movement and
tap-to-launch on home page 1 align with the new geometry; the boosted games
page fills its panel and its tiles hit-test exactly (the tapped corner tile
is the one that answered). Tests 78/78: 3 new (canonical rect across
channels, canonical height values, apps/settings stay hero-less) and two
folder tests reworked for hero-bearing folder pages.

## App v0.7.0 (2026-11-06): renamed to TilePivot

- The app is renamed X360Reborn -> TilePivot for Google Play trademark hygiene:
  the old name leaned on Microsoft's Xbox 360 brand. The app label and all
  user-visible strings now say TilePivot. Nothing about the UI's measured
  homage changes in this chunk; the store listing and its not-affiliated
  wording are a later chunk.
- applicationId changed dev.x360reborn -> dev.tilepivot (build.gradle.kts
  only, following the Orbital rename precedent). The code namespace and the
  Kotlin packages stay dev.x360reborn, no source moved, and the release
  signing key is unchanged.
- Because the applicationId changed, TilePivot installs as a NEW app alongside
  the old one; Android will not update an existing X360Reborn install in
  place. Migration path for existing installs: in the old app, use the
  settings channel's "back up settings"; install TilePivot; then use its
  "restore settings" and pick the exported file. Two deliberate compat
  choices make that work: the backup JSON "format" field keeps its legacy
  value ("x360reborn-settings") so pre-rename backups restore unchanged, and
  the default export filename is now tilepivot-settings.json. The Xbox 360
  game library needs no migration: the scanner re-reads the same shared
  folders on first launch. All-files access is granted per app, so it must be
  granted once for TilePivot from its settings channel.
- REQUEST_DELETE_PACKAGES removed from the manifest: it was never used -
  uninstall has always gone through the system ACTION_DELETE confirmation.
- MANAGE_EXTERNAL_STORAGE is KEPT in this chunk on purpose: the game scanner
  still reads the shared game folders directly and needs it. Removing it
  belongs to the later SAF (folder-picker) migration chunk, not this rename.

## App v0.8.0 (2026-11-06): picked-folder game library, no permissions

TilePivot now reads Xbox 360 games only from folders the user picks through the Storage
Access Framework. MANAGE_EXTERNAL_STORAGE is gone; the app declares no permissions at all
(`aapt dump permissions` on the release APK lists none).

### SAF design

- **Library.** Settings › "xbox 360 games" opens a manager dialog: the picked folders (by
  host path, or folder name when there is none), Add folder, Re-scan, and tap-a-folder to
  Remove. Add fires ACTION_OPEN_DOCUMENT_TREE with read + persistable flags and calls
  `takePersistableUriPermission(READ)`; Remove releases the grant. Multiple folders are
  fine; duplicates are ignored (`PickedFolders`, unit-tested).
- **Storage of the grants.** Tree URIs live in their own prefs file, `game_library`, not
  in `hub`, so the settings backup export (which serialises `hub`) never contains them: a
  grant is local to one install on one device. `res/xml/data_extraction_rules.xml` also
  keeps the file out of Android cloud backup and device transfer.
- **Scanner.** `GameScanner` now walks `ScanNode` trees. Production nodes are `DocNode`s:
  one ContentResolver children query per folder (id, name, MIME, size), cached for the
  scan; bytes come from `openInputStream`. Tests walk temp dirs through a java.io
  `FileNode`, so every rule stays JVM-tested. Semantics are unchanged but relative to
  each picked root: files up to two levels down, folders at depth two or less checked for
  default.xex, hidden entries skipped, ISO/ZAR under 1 KiB skipped (a provider that
  reports no size skips that floor), STFS by LIVE/CON/PIRS magic read from a stream.
  One 0x971A-byte header read per package serves both the title (0x411, 9 x 0x200
  slots) and the thumbnail check. Dedupe is by provider + document id, so overlapping
  picked trees don't double a game.
- **Cover art** keeps the name-major / directory-major convention (`resolveArt`, now
  generic over the folder type), resolved inside the tree: ISO/ZAR beside the file;
  folder games inside, then beside; STFS beside the package, then one folder up, but
  never above the picked root (the grant ends there). `Covers` decodes sidecars and
  header thumbnails through content streams. (The emulator pass caught a regression
  here before commit: the bounds-only `decodeStream` pass always returns null, and an
  early `?: return null` on it dropped every sidecar cover.)
- **Failure behaviour.** A revoked grant, unmounted card or deleted folder makes listing
  return nothing (SecurityException etc. are caught per query); the channel simply
  loses those games. The manager marks such a folder "unavailable" (grant missing from
  `persistedUriPermissions`, or the root document no longer resolves to a directory).
  The game cache now also rescans when the set of picked folders changes.

### Host-path mapping (`HostPaths`, pure, unit-tested)

Xendroid+'s launch contract is unchanged (action `xendroid.intent.action.xendroid`,
package `xendroid.compose.fork` then `xendroid.compose`, extras `game_uri` = absolute
path and `game_name` only), so each game's document URI is mapped to a path:

- `com.android.externalstorage.documents` ids `primary:<rel>` -> primary volume
  directory + rel; `<uuid>:<rel>` (SD card / USB) -> that volume's directory, taken from
  `StorageManager.getStorageVolumes()` (uuid matched case-insensitively).
- `raw:<abs path>` ids (any provider) -> the path itself, when absolute.
- Everything else is null: Downloads-provider `msf:` / numeric ids, media-store ids,
  cloud providers, volumes not currently mounted, malformed URIs, any `..` segment.
- Limits: the mapping relies on the AOSP external-storage provider's id convention, not
  an API guarantee; a provider using another scheme maps to null (safe, never a wrong
  path). Xendroid+ still needs its own access to that path; TilePivot can't grant it.

When a game has no host path its tile still shows with title and art, but launching
opens "Can't open this game", explaining that Xendroid+ needs a direct file path and
this folder type can't provide one. No intent is fired.

### Migration

Nothing is auto-migrated. Existing installs show the games channel with Android games
only, and the settings row reads "xbox 360 games: pick a games folder" until the user
picks their game folder once. The old fixed roots (/sdcard/Xbox360, /sdcard/ROMs/xbox360,
/sdcard/Games/Xbox360) are no longer scanned; picking one of them brings its games back.
On update, Android drops the old all-files grant along with the permission.

### Removed

- MANAGE_EXTERNAL_STORAGE, the all-files settings flow (the
  ACTION_MANAGE_APP_ALL_FILES_ACCESS intents), the onResume grant re-check, and the
  "grant folder access" row.
- The missing-emulator dialog's "Get Xendroid+" button and its GitHub releases URL. The
  dialog now says Xendroid+ is a separate emulator app the user gets and installs
  themselves, with a single OK.

### Verification

- Unit tests: 94 in each variant (78 baseline + 16 new: HostPathsTest 7, GamesTest 9),
  all passing, counted from the test-results XML after `--rerun-tasks`.
- Release APK + AAB built; badging dev.tilepivot / TilePivot / 0.8.0 (17); no
  permissions; lintRelease 0 errors.
- Emulator (Medium_Phone_API_36.1), live: seeded /sdcard/XboxGames (an ISO with a name
  sidecar, a LIVE package in a title-id folder with a header title and PNG thumbnail, a
  default.xex folder with boxart.png, plus a tiny ISO and a hidden ISO), picked through
  DocumentsUI by adb input (storage root correctly refused, folder allowed). Result: "3
  found"; the games channel shows Apex Velocity (header title + thumbnail), Folder Quest
  and Test Racer with their sidecar art, and survives force-stop (grant persisted). The
  manager lists `/storage/emulated/0/XboxGames` (the mapping, live, on the picked tree
  URI). Launch reaches the Xendroid+ not-installed dialog (Xendroid+ is arm64-only; no
  boot on the x86_64 AVD). Moving the folder away: marked unavailable, re-scan finds 0,
  no crash; moving it back and re-scanning finds 3. Remove returns the row to "pick a
  games folder".
- Not verified live: the Xendroid+ intent itself and its `game_uri` value (no Xendroid+
  on the emulator), SD-card and raw: mappings, and the unmappable-folder dialog; these
  rest on unit tests and code review.

## App v0.8.1 (2026-11-06): store readiness (icon, release polish, privacy, listing pack)

No launcher behaviour changes except one new settings tile. Everything else is what Google
Play needs around the code.

### What shipped

- An original adaptive app icon (replacing the stock `sym_def_app_icon`).
- Release builds minified (R8) with resource shrinking; platform backup off.
- A privacy policy hosted on GitHub Pages and a "privacy policy" settings tile that opens it.
- `store-assets/`: listing copy, Play declaration drafts, feature graphic, 7 screenshots.
- Still zero permissions.

### Icon

- Design: pure geometry in the launcher's own language. One wide hero panel above a 2x2
  grid of smaller tiles, flat (corner radius 1.2 of 108 units), light tiles on a vertical
  green gradient (#128A12 to #055505, around the default theme's #008A00). The grid tiles
  are 80% opacity so the hero reads first. No sphere, controller, "X" or any Microsoft mark.
- Geometry: everything sits in a 44x44 box centred on the 108x108 adaptive canvas, so every
  corner is inside the 33-unit safe radius (farthest corner 31.1). Hero 44x16, gap 3, cells
  20.5x10.5.
- Files: `drawable/ic_launcher_background.xml` (gradient vector),
  `drawable/ic_launcher_foreground.xml` (five rounded-rect paths), `mipmap-anydpi-v26/
  ic_launcher{,_round}.xml` (adaptive, with the foreground also as the monochrome layer for
  themed icons), and legacy PNGs `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png` at
  48/72/96/144/192 px.
- How the fallbacks are generated: `python -I tools/make_store_art.py` (Pillow). The script
  defines the rectangles once and writes BOTH the vector XML and every raster from them, so
  the PNGs can't drift from the vectors. Legacy icons render the centre 72 of 108 units
  (what a launcher mask shows) into a rounded square with a 1.5 dp margin, or a
  full-bleed circle (a margin made lint's IconLauncherShape check flag the round icon),
  supersampled 8x and downscaled with Lanczos. Output is deterministic (two runs gave
  byte-identical PNGs). With minSdk 33 the anydpi-v26 adaptive icon is always used; the
  PNGs are belt-and-braces.
- Store icon: `store-assets/icon-512.png`, the same art flattened full-bleed (Play applies
  its own mask). `aapt dump badging` now reports `application-icon-*: 'res/BW.xml'` (the
  adaptive icon; release resource-path shortening renames the file), not
  sym_def_app_icon, and the old "ERROR getting 'android:icon'" line is gone.

### Release polish

- `isMinifyEnabled = true`, `isShrinkResources = true`, default
  `proguard-android-optimize.txt` plus an empty `app/proguard-rules.pro`. Outcome: no keep
  rules were needed. Release APK 2.3 MB -> about 235 KB. The MINIFIED build passed an
  emulator smoke: launch, home / games / tv & movies / apps / settings channels render,
  settings dialogs, SAF folder pick + scan (3 games), sidecar and STFS header covers,
  launch reaching the Xendroid+ dialog, the privacy tile opening Chrome on the live
  policy; no FATAL or missing-class errors in logcat.
- `android:allowBackup="false"`. Rationale: the app has its own settings backup/restore,
  and the picked-folder grants are device-local by design (deliberately excluded from the
  settings backup), so a platform backup restore would bring back an inconsistent state
  (settings from one device, no valid folder grants). `dataExtractionRules` stays: on
  Android 12+ `allowBackup="false"` disables cloud backup but NOT device-to-device
  transfer, and the rules keep `game_library.xml` out of that transfer.

### Privacy policy

- `docs/privacy-policy.html`: one self-contained page (light/dark aware), plain language,
  TilePivot-specific: no personal data collected, no permissions (so no network), no
  accounts/ads/analytics/SDKs, on-device only, the installed-app list for showing tiles,
  local settings exported only through the in-app backup, the picked games folder
  (read-only, system picker, revocable), the game hand-off to the separate emulator,
  "TilePivot is not affiliated with Microsoft or Xbox.", GitHub issues as contact, last
  updated November 6, 2026. `docs/.nojekyll` makes Pages serve `docs/` verbatim.
- GitHub Pages enabled via `gh api` (source `main` / `/docs`, like Orbital). URL:
  https://glerschnersch.github.io/TilePivot/privacy-policy.html. **Verified live:** 404
  for about 30 s after enabling, then HTTP 200 with the right title, date and
  non-affiliation line; also loaded in Chrome on the emulator from the app's tile.
- In-app: the settings channel's "about" entry is Android's own device-info screen (no app
  About dialog exists), so a new tile "privacy policy" sits right after it, on the
  settings channel's second page. It is an external `Target.Action`
  (`Apps.ACTION_PRIVACY`) that MainActivity turns into `ACTION_VIEW` on
  `Apps.PRIVACY_POLICY_URL`. HubTest gains 2 tests (entry shape and URL; tile placement
  after "about").

### Store pack (`store-assets/`)

- `listing.md`: title "TilePivot: Retro Tile Launcher" (30/30), short description 78/80,
  full description 1953/4000 (all counted by script), Personalization, content-rating
  notes, screenshot notes.
- `play-declarations.md`: data safety (nothing collected or shared), privacy URL,
  permissions (none; SAF folder), audience, and OPEN ITEMS: the closed-test requirement for
  the "Vincus" account is unconfirmed; residual IP risk is the publisher's decision.
- `feature-graphic.png`: 1024x500, the icon motif at scale plus "TilePivot" and "A retro
  tile launcher" in Roboto Regular (Apache 2.0, from the Windows font folder or
  `ROBOTO_TTF`), generated by the same `tools/make_store_art.py`.
- Screenshots: what they actually show. Taken on the emulator in a TEMPORARY second
  Android user (`pm create-user`, setup marked complete for that user only, TilePivot
  installed with `pm install-existing`), so only the AVD's stock Google apps appear rather
  than the other launcher projects installed for user 0; default theme, fresh settings,
  no games folder. The user was removed afterwards and `stay_on_while_plugged_in` restored
  to 2. Seven shots: home (Calendar hero over the app grid), tv & movies (only YouTube on
  this AVD, so one hero tile; honest but sparse), apps with search, settings page 1,
  settings page 2 (system shortcuts and privacy policy), the "pick a games folder"
  invitation, and the theme picker. No games, covers or Microsoft marks are pictured, but
  the in-app strings "xbox 360 games", "theme: xbox green", "Xbox 360 game folders" and the
  theme name "Xbox Green" are visible in shots 4, 6 and 7; stock Google app icons appear
  as launcher content. Captured at 1080x2400 and cropped (40 px top, 200 px bottom, no UI
  removed) to 1080x2160, because Play rejects screenshots longer than 2:1.

### Verification

- Unit tests: 96 in each variant (BackupTest 4, GamesTest 38, HostPathsTest 7, HubTest 47),
  counted from the test-results XML after `--rerun-tasks`, zero failures.
- Release APK + AAB built; `aapt dump badging`: dev.tilepivot, TilePivot, 0.8.1 (18),
  application-icon not sym_def_app_icon; `aapt dump permissions`: none. lintRelease:
  0 errors, 3 warnings: two pre-existing (org.json test dependency version, fixed
  orientation) and ObsoleteSdkInt on `mipmap-anydpi-v26`, kept because the brief specifies
  that folder (with minSdk 33 it could be plain `mipmap-anydpi`).
- Emulator smoke of the final minified 0.8.1 release APK: see the release-polish list above
  (launch, channels, settings, no crash).

## Next
- Hover chrome: first live visual check on hardware with a real pointing device (the
  emulator AVD has none; see the v0.5.0 note above).
- Work-profile badge overlay still awaits a glance on a device with a work profile
  (carried from 0.4.3).

## App v0.9.0 (2026-11-06): vertical scrolling inside channels, settings gear

Tester feedback drove both changes: people tried to swipe up through their
apps and games and disliked paging sideways inside a channel, and settings --
the last channel in the pivot -- took a long sideways trip to reach.

### Vertical scroll pages

- Every channel is now ONE page: the hero in its canonical Layout.heroBox
  geometry at the top, fixed, and every other tile in a uniform grid under it
  that scrolls vertically (Template.gridTop marks the split; the grid region
  below the hero is the viewport, the stacked rows the content). Channels
  without a hero (apps, settings) scroll their full grid the same way.
  HubBuilder assembles the page from the channel's existing templates -- the
  hero slot keeps its legacy index in the slot list, the grid takes the grid
  template's tile size and column count (boost included), centred under the
  hero -- so slot indices mean what they meant on the old first page.
- Between channels nothing changed: horizontal pivot (strip swipe,
  PAGE_UP/PAGE_DOWN, L1/R1, twist taps, channel/folder shortcut tiles), the
  channel-slide animation, and the entry cascade on channel change only. The
  cascade wave now runs over the rows visible at the landing scroll offset;
  tiles below the fold are at rest until scrolled to.
- Gestures use dominant-axis disambiguation: a vertical-dominant drag scrolls
  the grid and releasing with velocity flings it (exponential decay, hard stop
  at the edges, no rubber band); a horizontal-dominant drag pivots channels
  as before. Taps are unchanged (first tap focuses, second activates).
- DPAD moves through rows/columns geometrically as before, and focus moving
  to a partially off-screen row scrolls the minimum amount to reveal it in
  full (animated). BEHAVIOUR CHANGE: DPAD left/right at a row edge no longer
  pages or crosses channels -- it wraps through the reading order to the
  previous row's last tile / next row's first tile. Channel changes via keys
  are PAGE_UP/PAGE_DOWN and the bumpers.
- Each channel remembers its focused tile and its scroll offset across
  visits (in-memory, like the old page/focus memory); positions are clamped
  when a tile-size change shrinks the content. No page state was ever stored
  in preferences (only channel-hidden flags), so there is nothing to migrate.
- Home channel: the two wide shortcuts now close the scroll grid in both
  orientations (the old portrait rule, generalised); the landscape
  Template1HD mosaic with its fixed wide cells no longer shapes the home
  page, though the hero still derives from its slot.
- Scroll math is pure in home/Scroll.kt (rows, content height, max offset,
  clamping, reveal offset, visible row range) and unit-tested in ScrollTest.
- TalkBack: accessibility focus on a tile scrolls it into view; the host's
  scroll forward/backward scrolls the grid by most of a viewport and falls
  through to the next channel at the grid's end, as the old page scroll did.

### Settings gear

- A gear glyph sits in the clock strip on every channel, ending 16 design px
  left of the clock text (Metrics.settingsGearBox), in the same stroked-gear
  language as the settings tiles. Its hit box is 108 design px = 81dp,
  comfortably over the 48dp floor, and it clears the pivot names, which live
  in the twist band below the clock band. Tapping it jumps straight to the
  settings channel as a normal channel change (slide + cascade).
- On the settings channel itself the gear is drawn dimmed (alpha 90) and is
  inert. It is not in the DPAD focus order; TalkBack exposes it as a
  "Settings" button (disabled while settings is showing). One interaction to
  know: at tile size LARGE in portrait, the hover chrome's home button can
  overlap the gear's box, but that chrome exists only under a pointer, is
  hit-tested first, and draws above the gear.

### Test changes

- New ScrollTest (11 tests): rows/content height/max offset/clamping, the
  reveal cases (already visible, above, below, oversized, clamped at both
  ends), and visible-row ranges including an offset landing in a gutter.
- HubTest: appsChannelPagesHoldFifteen became appsChannelIsOneScrollPage
  (the apps channel is one 31-tile scroll page of 7 rows, not 3 pages of
  15); denseGamesChannelKeepsTheLayoutTemplates now asserts geometry
  equivalence on the single page instead of template identity across two
  pages; appsAndSettingsChannelsAreNeverBoosted asserts hero-less pages with
  layout-sized cells instead of template identity; sparseGamesChannel
  FillsThePanelAtMedium measures the fill over the first viewport of the
  scroll grid instead of a whole page; heroRectIsCanonicalAcrossChannels
  places pages at the panels' top (no more vertical centring);
  portraitHubKeepsHeroAndShortcuts expects all 31 apps-channel tiles on the
  one page. New: everyChannelHasExactlyOneScrollPage,
  gridRowsStackUnderTheHero, settingsGearBoxClearsTheClockAndClears48dp.
- Totals: 110 per variant (BackupTest 4, GamesTest 38, HostPathsTest 7, HubTest 50,
  ScrollTest 11), 0 failures, debug and release, forced --rerun-tasks,
  counted from the test-results XML (0.8.1 baseline: 96).

### Emulator evidence

Emulator only (Medium_Phone_API_36.1, 1080x2400 portrait, release build;
  screenshots in ~/workspace/x360reborn/shots-vscroll/):
- Apps channel: a vertical swipe scrolled the grid (v3_apps_top.png ->
  v4_apps_scrolled.png: the search row leaves the top, Camera/Chrome rise,
  Drive/Gmail rows enter from below).
- A horizontal swipe on the scrolled apps grid pivoted to the settings
  channel instead of scrolling (v5_pivot.png).
- Six DPAD_DOWN presses from the search tile walked focus down the rows and
  the view scrolled to keep the focused tile (Files) fully visible at the
  bottom of the panel (v6_dpad.png).
- The home channel overflows on the AVD, so the fixed hero is directly
  evidenced: after a vertical swipe the AloyMobile hero sits in exactly the
  same rect while the grid rows beneath it change (v7_home_top.png ->
  v8_home_scrolled.png). The games channel's hero follows the same code path.
- Gear: tapping it on the home channel landed on the settings channel, the
  gear drawn dimmed there (v1_home.png -> v2_gear.png).
- Logcat across the whole drive: no FATAL EXCEPTION, no dev.tilepivot crash
  lines. The physical phone was not touched.

## App v0.9.1 (2026-11-06): a code-drawn player figure in the home hero

- The home channel's hero gains a small standing player figure, evoking the
  dash's staged avatar: it stands on a soft floor ellipse at the right of
  the hero, over the art, clear of the name/sub text at bottom-left. Home
  channel only; no other hero changes.
- Clean-room: the figure is original geometry drawn in code
  (home/AvatarFigure.kt for the layout, HomeView.drawAvatarFigure for the
  shapes) -- chunky proportions, a plain featureless head, capsule limbs,
  two-tone clothing from the active theme's tile accent (a darker shade
  for legs and waistband, a lighter chest band), one neutral skin tone,
  near-black shoes. No extracted asset is used or reproduced: the
  re/out/avatar material stayed research reference for how the dash staged
  its figure (right side, standing on a floor area), nothing more.
- Presentation-only: not a tile, not focusable or clickable, absent from
  the hub model, search, folders and backups, and invisible to TalkBack.
  It is drawn in one static pass inside drawHero -- no animation loop and
  no extra invalidations (deliberate: a launcher hero should cost nothing
  while idle).
- Geometry is pure (the Scroll.kt pattern): the figure takes the hero's
  right quarter and 58% of its height; the whole footprint, floor
  included, ends LABEL_GAP (10) above a label zone of LABEL_H (64, which
  is Metrics.HERO_BLOCK_SUB), so it can never reach the hero text.
  AvatarFigureTest (6 tests) pins: inside the canonical heroes (portrait
  SMALL/MEDIUM/LARGE, the landscape Template1HD hero, 708x352.7), clear
  of the label zone with the exact gap, feet on the floor's centre line,
  parts inside the body, a plain circular head centred over the torso,
  and scale-invariant proportions (sizes scale; vertical placement is
  anchored to the fixed label height, so offsets are checked relative to
  the body). Tests: 116 per variant (was 110), 0 failures.
- Emulator evidence (Medium_Phone_API_36.1, release build; screenshots in
  the VM workspace, shots-avatar/): home-xbox-green.png -- the figure
  stands at the hero's right in Xbox Green, and the hero's centred app
  art ends before the figure begins, so the backing disc/podium the brief
  allowed for was not needed. home-carbon.png -- Carbon greys; the legs
  are low-contrast against the hero's dark flank, and the head and chest
  band carry the read. home-landscape.png -- the Template1HD hero, same
  staging. Logcat: no FATAL EXCEPTION. The physical phone was not touched.
- Process note: this build was made in a temporary git worktree off
  99ebc26, because a parallel session was mid-edit on a profile-chip
  feature in the main working copy (Metrics.profileChip, Profile.kt,
  HomeView profile code -- not part of this release, and its uncommitted
  state was left exactly as found).

## Avatar assets (2026-11-06, reference only)

- `Guide.AvatarMiniCreator.avatarmc` XZP: 12 stock avatars, each as `avatar64NN.png`
  (64x64 head-and-shoulders gamerpic on the blue sphere) + `avatarfbNN.png` (full body),
  + `avatar_metadataNN.bin`. `AvatarEditor.MEDIA` XZP: 54 `gamerpic_background_N.png`
  (large/small pairs) plus editor chrome. Collected into `re/out/avatar/` with contact sheets.
- The 3D avatar data (`FFFE07DF00000002/AvatarAssetPack*.toc`, 14.7 MB + 1.2 MB) is a
  header plus offset table followed by ~8.0 bit/byte payload, i.e. compressed or encrypted.
  It is not decoded; `*.MsLiveAvatarAsset` (magic `STRB`) is the per-asset container.
- None of this ships: it is Microsoft art. A TilePivot profile picture must be original
  or user-supplied.

## App v0.10.1 (2026-11-06): profile chip, 3D avatar, live home avatar

- Two lines merge here. From feature/profile-chip (via integration/phone-avatar):
  a profile chip in the clock strip, TilePivot's own code-built 3D avatar with
  its own screen and a procedural animator, an optional user-supplied
  avatar-pack hook, and a live 3D avatar standing in the home hero. From main:
  0.9.0's vertical channel scrolling + settings gear and 0.9.1's flat
  code-drawn hero figure, both unchanged (Scroll.kt / AvatarFigure.kt and
  their tests are main's, byte for byte). The integration branch's 0.10.0
  snapshot of the 0.9.1 work (5d9f1f2) is superseded by main's committed 0.9.1.
- Profile chip (eabdfa6): [name][picture] left of the settings gear, after the
  dash's MiniGamercard placement. Picture from the system photo picker (no
  permission), centre-cropped to 256 px, kept as a private PNG (not part of
  the JSON backup); the name lives in the hub prefs (profileName), so backups
  carry it. With no picture, the chip shows an original code-drawn silhouette
  (or the avatar headshot, below). Metrics.profileChip lays it out; narrow
  strips drop the name first. A "profile" settings tile opens the same menu.
- TilePivot's own 3D avatar (06f4b3a): an original, code-built 3D avatar
  (ellipsoids and tapered tubes: big head, simple face, five hair styles,
  top/bottoms/shoes) -- no console artwork. AvatarLook holds the choices in
  the hub prefs as "avatarLook", so backups carry it. AvatarView is an
  OpenGL ES 3 view (soft lambert + sky + rim light); AvatarActivity is the
  full-screen avatar screen, opened from the profile chip menu ("Avatar...")
  and a new "avatar" settings tile. AvatarSnapshot renders a software
  z-buffered headshot (no GL) that the profile chip shows when no photo is
  chosen, re-rendered when the look changes.
- Animation (2c38a4a): Rig / SkinnedPart skeleton with named joints and a base
  pose (up-to-4-bone skinned meshes); a procedural Animator -- idle (breathing,
  weight shift, glances), one-shot emotes (wave, cheer, nod, shrug) with fade
  in/out, smoothed body turn (lookAt) -- with forward kinematics + CPU
  linear-blend skinning. AvatarRenderer is the GL drawing shared by surfaces;
  the avatar screen is animated (tap to wave; Wave/Cheer buttons). Pack
  outfits come back as skinned parts on one shared rig.
- Avatar packs (0a43ca0): the avatar screen can render items from a pack the
  user supplies WHEN a build includes a decoder. This repo ships no decoder
  and no pack data: AvatarPacks.decoder is looked up by class name and is null
  here, so public and Play builds show only TilePivot's own avatar.
  build.gradle.kts compiles a private sibling folder in only with
  -PwithAvatarPack, and bundle tasks refuse that flag, so a Play bundle can
  never contain a decoder. (Pack material is Microsoft art and never ships;
  see the "Avatar assets" research notes above.)
- Live home avatar (20fbedf): AvatarOverlay, a transparent TextureView with
  its own EGL/GLES3 thread sharing AvatarRenderer, stands on the home hero's
  figure stage. HomeView reports the stage every frame (scroll offset,
  transition scale/fade, focused tile centre) and skips the flat 2D figure
  while the 3D avatar is on (drawHero: figure && !avatar3d) -- the two takes
  coexist by that switch. MainActivity places the overlay on the stage, turns
  the avatar toward the focused tile, waves when the home tile comes back
  into view (at most every 20 s), cheers on app launch, and loads the user's
  look on resume. Tap the avatar for a random emote; long-press opens the
  avatar screen; touches beside the figure reach the hub. Settings gains
  "home avatar: shown/hidden". AvatarRenderer.headroom keeps raised arms
  in frame.
- Merge surface: HomeView.kt (one call-site conflict, resolved for the
  !avatar3d form), Metrics.kt (profileChip alongside settingsGearBox),
  Model.kt (PROFILE glyph), MainActivity.kt (photo picker, profile menu,
  overlay hosting), build.gradle.kts (version 0.10.1 / 22 + the
  -PwithAvatarPack block).
- Tests: 129 per variant (was 116 for 0.9.1), 0 failures/errors, counted
  from test-results XML after a forced --rerun-tasks (Backup 4, Games 38,
  HostPaths 7, Hub 50, Scroll 11, AvatarFigure 6, Profile 8, Avatar 3,
  Animator 2). No dev.x360reborn.avatarpack.* suites appear in the default
  build's results: the decoder is absent, as designed.
- Emulator evidence (Medium_Phone_API_36.1, release build installed as an
  update over 0.9.1; screenshots in the VM workspace, shots-0101/):
  home.png -- the profile chip shows the avatar headshot beside the
  settings gear, and the live 3D avatar stands on the home hero (the flat
  figure is skipped, per the !avatar3d switch). avatar-screen.png -- the
  full-screen avatar reached by long-pressing the home avatar, choice
  buttons along the bottom. home-figure-2d.png -- after toggling the
  "home avatar" settings tile to hidden, the hero falls back to 0.9.1's
  flat code-drawn figure; the tile was returned to "shown" afterwards.
  Settings also shows the new profile and avatar tiles. Logcat across
  the whole drive: no FATAL EXCEPTION. The physical phone was not
  touched.
- Open work (from the avatar handoff): console pack animations (format
  decoded; anim bone order differs from mesh skin order -- the mapping is
  built in XAM/AvatarEditor code and is the blocker), exact face-feature
  placement (currently estimated; the real compositor is in XAM), and heads
  poking through some hats (the console's "Hiding Template" assets are
  undecoded).
## App v0.10.2

- The live 3D avatar on the home hero is about 15% larger, at Matt's
  request ("slightly larger"). One framing constant changed:
  AvatarOverlay's renderer headroom 0.28 -> 0.113. AvatarRenderer.frame()
  fits (figure height x (1 + headroom)) into the stage with the band
  anchored at the feet, so the figure's screen height scales by
  1.28 / 1.113 ~= 1.15 and its feet keep the exact same screen position:
  growth is upward from the hero floor, no sinking, floating or sideways
  shift. Presentation only -- no behaviour, animation or data changes --
  and the overlay's stage (HomeView.reportAvatarStage) and touch geometry
  are untouched. The full-screen avatar view never sets headroom and is
  unchanged. The flat 0.9.1 fallback figure (home/AvatarFigure.kt) has its
  own independent geometry and is deliberately left as it was.
- Constraint checks (portrait hero): the head top moves from ~32 to ~10
  design px below the hero's top edge -- still inside, and the stage
  itself extends above the hero, so emotes keep their room. The wider
  figure grows around the stage centre and stays clear of the hero art
  and the label zone. Emulator burst frames of the arrival wave at the
  new size show the raised hand fully rendered, peaking just inside the
  hero's top edge -- nothing clips at the stage edge.
- Files: avatar/AvatarOverlay.kt (headroom + comment),
  app/build.gradle.kts (version 0.10.2 / 23), docs/RE_NOTES.md (this
  section).
- Tests: 129 per variant in the default build; 137 per variant with
  -PwithAvatarPack (the 8 decoder suites among them, none skipped);
  0 failures/errors in all four runs, counted from test-results XML
  after a forced --rerun-tasks.
- Emulator evidence (Medium_Phone_API_36.1, public release installed as
  an update over 0.10.1; screenshots in re/out/avatar):
  _s16_hero_before.png / _s16_hero_after.png -- same hero state (home
  channel, AloyMobile hero): figure height ~260 -> ~298 screen px
  (+~15%), feet at the same screen y, head inside the hero, no overlap
  with the hero art or the label. _s16_hero_wave.png -- the arrival
  wave at the new size, hand fully inside the frame.
