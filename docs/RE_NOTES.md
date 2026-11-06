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

## Next
- Hover chrome: first live visual check on hardware with a real pointing device (the
  emulator AVD has none; see the v0.5.0 note above).
- Work-profile badge overlay still awaits a glance on a device with a work profile
  (carried from 0.4.3).
