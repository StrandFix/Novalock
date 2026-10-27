# NovaLock for Retroid Pocket Nova — test V3

Built from the public Pocket Lock project. `main` remains unchanged; this test lives on `nova-preview`.

## V3 changes
- Original 3-button unlock behavior, sounds, vibration, animation and customizable dots retained.
- Smaller dots, closer together. Migrate once only if previous defaults are unchanged; manual adjustments preserved.
- Dedicated Cocoon HOME Menu screen with a geometric house icon instead of Cocoon's generic app icon.
- Screenshot preview for games and other active apps, app-icon fallback if capture unavailable.
- Less battery usage: optional screenshot capture; a still frame every ~25 seconds while the display is on and unlocked, plus after app switches; no screenshot polling during sleep, while locked or on Cocoon HOME.
- Downscaled screenshot frames (~760 px long edge) kept in memory only. No uploads and no saved screenshots.
- Android Accessibility screenshot API permission remains optional and must be explicitly enabled.

## RetroArch note
Certain RetroArch video backends can reject Android system screenshots. V3 improves application matching but cannot bypass Android secure-window restrictions or guarantee Vulkan captures; the normal RetroArch icon is fallback. The Permissions tab includes a status line with Android screenshot errors.

## Install note
GitHub Actions debug APKs are signed with an ephemeral debug key. Uninstall the previous NovaLock Test after disabling its device-administrator permission before installing V3. The original Pocket Lock can stay installed but disable its locking service.

## Testing
1. Launch Cocoon and lock: expect HOME Menu (house) without screenshots.
2. Launch DuckStation/other emulator for 30+ seconds, lock: expect a recent game frame.
3. Repeat with RetroArch; capture error status available under NovaLock > Permissions.
4. Test original three button presses, dot color/size/spacing, and screen-off timeout.
5. Disable screenshot capture in Options; verify no game screenshots while the lock continues functioning.

This APK is a test build; battery usage and emulator behavior require validation on physical Retroid Pocket Nova.

## V4 finishing touches (Novalock)
- Launcher and settings name are **Novalock** without "Test".
- Optional **Floating game preview** toggle (Off by default). A ~7dp gentle vertical drift; only animates the screenshot card while a visible lock screen is awake. Stops on screen-off, lock-view removal, pause and unlock; Cocoon HOME and text/dots never float.
- Quiet, no-badge foreground service notification channel, and app notification preference defaults Off. Android may still show an indicator for an active foreground service; the **Hide system notification** button opens Novalock's system notification settings to disable app notifications without disabling the service.
- Screenshot sampling never schedules while sleeping, locked, in Cocoon, or disabled. One frame is retained in RAM only.
- These choices minimize additional battery use but do **not** guarantee zero device power draw while sleeping: Android still maintains the foreground lock service and might wake it for lifecycle events.
- Physical Retroid Pocket Nova battery testing and visual verification still required.

## V5 — Settings, with no screenshot
- Android Settings and permission-related system panels show the same circular minimalist design as HOME, but with a white gear and the heading **Settings**. The subtitle reads **Unlock to return to Settings.**
- Screenshots and screenshot sampling are disabled in Settings, avoiding sensitive device information on the lock screen and reducing work.
- Game preview includes a small, still **READY TO RESUME** label under the card; the game preview, optional float animation, Cocoon HOME layout, buttons and dots otherwise remain the same.
- Existing foreground service remains for reliable locking. Battery impact is not claimed to be literally zero; Android device testing is required.

## V6.1 — Fix Android Settings wake-lock visibility
- Android Settings may hide non-system overlays for security; Settings now deliberately skips the third-party overlay and uses the visible LockActivity window instead.
- On activity-only unlock, the service clears the armed-lock flag, frozen preview, timeout, muted media, and audio focus. Otherwise the subsequent sleep can inherit the previous Settings lock.
- Cocoon, all emulators and game previews still use the original overlay-based path. Existing 3-button behavior, animations, low-power screen-off handling and custom appearance settings remain unchanged.
- Validate on real Retroid Pocket Nova: Settings → sleep/wake shows Settings lock immediately; three presses unlock; a second Settings sleep/wake also works; Cocoon and games still show previews; no regressions in screen-off power use.

## V6.2 — Game preview emulator icon + capture speed
- Keep Settings activity-only lock fix from V6.1; do not alter Cocoon, HOME, Settings, device key logic, dot settings, preview animations or battery/clock.
- Show active app/emulator's native transparent app icon as a subtly bordered graphite badge, overlapping screenshot's lower-left corner. The badge never appears on HOME or Settings, or when there's no screenshot.
- New Preview option: **Show emulator icon on game preview**, enabled by default, configurable independently from image capture and float animation.
- Screenshot refresh speed: **Battery saver 25 s**, **Balanced 11 s** (default), **Responsive 6 s**. A still image is also requested soon after switching apps. Never schedule screenshot capture while sleeping, locked, Cocoon HOME, Settings, or if disabled. A faster rate may increase energy use when actively gaming, but sleep mode polling remains off.
- All bitmap captures remain ephemeral in memory; no persistent uploads or recordings.
- Physical Retroid Pocket Nova testing required for the overlapping badge, Settings wake and sleep-mode power.

## V6.3 — Cocoon local artwork integration (opt-in, screenshot-first)
- Keep `Live screenshot` as the unchanged DEFAULT display mode; preserved for all users upgrading.
- Optional `Cocoon hero / game icon / game logo` image choices. These modes are never activated without explicit user selection.
- Access Cocoon's chosen data/art folder via Android Storage Access Framework `ACTION_OPEN_DOCUMENT_TREE`, with user-approved READ permission only, persisted for later sessions. The app does not inspect Android private storage or require `MANAGE_EXTERNAL_STORAGE`.
- Index limited to 3500 images and 7 subdirectory levels, only when user selects/rescans the folder or when the already-enabled integration is initialized while the device is awake. No folder scanning during sleep.
- Conservative automatic title matching: an exact known artwork title from Cocoon's click/focus Accessibility event just before emulator launch, or from the emulator's window title. No arbitrary package-to-game guess; emulator package alone is NOT evidence of the ROM name.
- Some versions of Cocoon and emulators do not expose the game title, and some Cocoon images may be named opaque IDs/hashes. In these cases Novalock intentionally falls back to the existing screenshot. Full automatic identification for all emulator/game combinations requires an officially exposed Cocoon playing-game API, documented asset metadata, or user-supported game metadata mapping.
- Image is decoded in the background ONLY when display is interactive. Temporary bitmaps are in memory; no requests to SteamGridDB, no external networking and no sending personal data.
- All previous controls, existing play preview, Cocoon HOME, Settings activity-only fix, emulator badge, live screenshot refresh speeds, floating animation, top bar and three-press unlock are retained.
- Device validation required: Android SAF folder picker and matched game artwork tested with actual Cocoon folder layout. Confirm consistent fallback on unmatched games.
