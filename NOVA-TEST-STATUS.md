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
