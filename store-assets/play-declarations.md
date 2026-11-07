# TilePivot — Play Console declaration drafts

Drafts for version 0.8.1 (versionCode 18), prepared 2026-11-06. Paste into Play Console when
the app is created. Nothing here has been submitted; no console entry exists yet.

## Data safety

- **Does your app collect or share any of the required user data types?** No.
- **Data collected:** None. **Data shared:** None.
- **Basis (one line):** TilePivot has no INTERNET permission (it declares no permissions at
  all) and contains no third-party SDKs, so no data can leave the device; all processing is
  on-device.
- **Is all user data encrypted in transit?** Not applicable: nothing is transmitted.
- **Do you provide a way for users to request that their data is deleted?** Not applicable:
  no data is collected. (Local settings are removed by uninstalling or clearing app data.)
- Notes for the reviewer if asked: the in-app "back up settings" writes a JSON file only to a
  location the user picks through the system file picker; the app never uploads it.

## Privacy policy

https://glerschnersch.github.io/TilePivot/privacy-policy.html

Served from `docs/privacy-policy.html` via GitHub Pages (`main` / `docs`). Verified live
(HTTP 200, correct page) on 2026-11-06, and opened from the app's settings tile
"privacy policy" on the emulator.

## Permissions

- **Declared permissions:** none. `aapt dump permissions` on the 0.8.1 release APK lists only
  the package name.
- **Sensitive / restricted permissions declaration form:** not needed (no
  MANAGE_EXTERNAL_STORAGE, no QUERY_ALL_PACKAGES, no accessibility service, no SMS/call
  log, no location).
- **Storage access:** the optional games library uses a folder the user picks through the
  Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE), read-only, with a persisted grant the
  user can remove in-app or revoke in system settings.
- **Package visibility:** the manifest uses `<queries>` for launcher activities (MAIN /
  LAUNCHER, which a home-screen launcher needs to list apps) and for the Xendroid+ emulator
  package and action. This is a manifest declaration, not a permission.
- **Default handler:** the app offers itself as a HOME app (category HOME). The user chooses
  it in Android's default-apps settings; the app never requests a role by itself.

## Target audience and content

- **Target age groups:** 18 and over is the simplest accurate choice (a general utility,
  not designed for children). Selecting 13+ bands is also defensible; do NOT select any
  under-13 band (that triggers the Families policy, which the app isn't designed for).
- **Appeals to children?** No.
- **Ads:** No ads.
- **Content rating (IARC):** utility app; no violence, sexual content, language, gambling,
  controlled substances, user interaction, location sharing or purchases. Expected:
  Everyone / PEGI 3. See `listing.md` for the notes.
- **News app / COVID / government / financial / health declarations:** No to all.

## OPEN ITEMS (decisions for the publisher; not resolved here)

**(a) Closed-test requirement: UNCONFIRMED.** Matt's Play developer account ("Vincus",
identity verified) may be a *personal* account. Personal accounts created after
November 13, 2023 must run a closed test with at least 12 testers opted in for 14
continuous days before they can apply for production access. Whether this account is
personal or organisation, and when it was created, has NOT been confirmed from the
console; check Play Console › Settings › Developer account before planning a launch date.
If the requirement applies, it is the critical path (Orbital's notes say the same).

**(b) Residual IP risk: publisher's decision.** TilePivot is a homage in layout style to a
console dashboard. The name, copy, icon and feature graphic are original, carry no
Microsoft or Xbox marks, and the listing and privacy policy state that TilePivot is not
affiliated with Microsoft or Xbox. Residual risk remains: the in-app strings use "Xbox"
(the default theme is named "Xbox Green", the settings tile reads "xbox 360 games", the
library dialog says "Xbox 360 game folders"), and those strings are visible in screenshots
4, 6 and 7. Whether to ship as is or rename those strings first is the publisher's call.

**(c) Contact email.** Play requires a developer contact email on the listing; the privacy
policy uses GitHub issues as its contact route. Choose the email when filling the console.

**(d) Signing.** Play App Signing will hold the app-signing key; the upload key is the
publisher's choice. The current release key (Reborn Launchers cert) is shared with other
apps; a dedicated upload key would limit the impact of a leak. Not changed in this chunk.
