# Novalock V6.9 — Cocoon ROM launch relay (experimental pilot)

## The underlying discovery

Cocoon's official public `platforms/*.json` configuration controls a game
launch through `playerList[].amStartArguments`. For instance:
- GameCube/Wii Dolphin: `-e AutoStartFile {file.uri}`
- PS2 ARMSX2 (Refresh): `-d {file.uri}`
- Switch Eden: `-d {file.uri}`
- Nintendo 3DS Azahar: `-d {file.uri}`

A separate working Android helper project (GameNativeSteamLauncher) demonstrates
that Cocoon can **import a patched platform JSON** with a helper player.

Instead of reading another app's private data, our explicit exported
`CocoonRelayActivity` receives the ROM URI through Cocoon's custom player,
extracts the filename, resolves matching artwork from the user-approved Cocoon
folder and forwards the URI to the original emulator in the same launch.

No root, ADB, RetroAchievements login, OCR, screenshot similarity, per-game
manual choice or network access is needed. Original Cocoon players are kept
unaltered in all patched JSON files.

## Before any test — backup and safe rollback

1. In Cocoon, use **Settings → System & Advance → Backup** and include
   sources/players, game data and appearance. Do not reset or rescan the library
   unnecessarily. This import may overwrite the affected platform definition.
2. Make sure the regular Dolphin player works **before** trying the relay.
3. Keep original Dolphin selected for every untested platform.
4. If an imported player cannot launch a game, immediately return to the
   original player using Cocoon's Default Player dropdown or restore backup.
5. Do not uninstall Cocoon or delete the ROMs. The pilot does not modify ROMs.

## One-time configuration, not once per game

1. Install the V6.9 debug APK, with package ID
   `pl.iskri.pocketlock.nova`.
2. Set Novalock Cocoon mode to **Icon** or **Hero**, choose Cocoon artwork
   folder and press **Refresh artwork index**.
3. For the initial pilot, import only `GameCube.Novalock.json` via Cocoon's
   **Library & Data → Add Platform / Import custom platform** flow. If Cocoon
   warns about an already existing `gc` platform, STOP rather than
   overwriting data unexpectedly; record the warning.
4. For the GameCube platform, select player **Dolphin via Novalock** once.
   All games using that default player subsequently launch via the relay.
   Existing per-game player overrides may need adjusting separately.
5. Launch **Super Mario Sunshine** from Cocoon as usual. Verify Dolphin
   actually opens the ROM. Lock and inspect game artwork.
6. In Novalock Options, tap **Copy artwork matching diagnostics**. Look for
   `Last Cocoon ROM relay`, `ROM relay match`, `Assigned title`,
   `Loaded image`. These prove whether actual ROM metadata crossed apps.

**Only after the GameCube pilot is confirmed** import the other files to
support Wii/Dolphin, PS2/ARMSX2 (Refresh), Switch/Eden (official package) or
3DS/Azahar. The five adapters share the same code, but emulator package and
intent formats differ and must be validated separately.

## Technical caveats

- Cocoon's ability to import a patched platform JSON is externally
  demonstrated, but importing/updating a platform that already exists may
  behave differently across Cocoon builds. Keep a full backup.
- Cocoon's `{file.path}` and `{file.uri}` variables are copied from its
  official platform configs. Some SAF providers use opaque document IDs: the
  ROM itself should still launch, but artwork matching may fall back until
  metadata can be resolved from the provider.
- Each patched platform JSON **retains every original player**, and adds one
  Novalock route. The relay explicitly routes to a fixed known emulator only;
  it never makes a guessed package choice.
- Test builds have a debug application ID suffix `.nova`. A future release
  package needs regenerated player configs to match its actual package ID.
- The relay is never automatically enabled just by installing Novalock.
  Original Cocoon launch behavior remains unchanged until one of the custom
  players is explicitly chosen.
- A CI build can verify syntactic integrity, Android compilation, JSON schema,
  and code tests. It cannot prove that imported player handling, URI grants
  and emulator start work on the actual Retroid Pocket Nova without a device
  run. Never market as production-ready before this test passes.
