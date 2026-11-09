# Plan: layered on-screen buttons + custom icons (Artemis fork)

Supersedes the phase list in PREPLAN.md. Hard constraints and requirements from PREPLAN.md still apply.

## 1. What the code actually does today (Phase 0 findings)

All of this lives in the keyboard on-screen controls:
`app/src/main/java/com/limelight/binding/input/virtual_controller/keyboard/`.
The gamepad overlay (`virtual_controller/VirtualController*`) is a separate system with real analog left/right sticks (sent as gamepad axes). `Game.java` creates both overlays on the same root view and toggles them independently, so both can be on screen at once. The gamepad overlay is only touched by the optional stick-layer hook described below.

**Element set is fixed, positions are per-profile.**
- `KeyBoardControllerConfigurationLoader.createDefaultLayout()` builds every element from the bundled asset `assets/config/keyboard.json` (rockers, dpad, mouse buttons, a fixed list of single keys), auto-placed in a grid.
- Then it appends "custom" combo buttons from the imported special-keys JSON (SharedPreferences `specialPrefs` / `special_key`). Element ids are `custom_<id>`.
- `loadFromPreferences()` overrides position/size/enabled per `elementId` from the active profile (SharedPreferences named by pref `keyboard_axi_list`). Values are **absolute pixels**: `{LEFT, TOP, WIDTH, HEIGHT, ENABLED}`.

**Two existing import/export paths** (`preferences/StreamSettings.java`, `onActivityResult`):
- Profile import (request 1001): a flat `{elementId: "<json string>"}` map, written straight into the profile prefs. Export is a Gson dump of the same map. Device-specific pixels.
- Special keys import (request 1002): stored verbatim. Format: `{"data":[{"id","name","keys":["0xA2","0x31"] | ["VK_..."],"sticky"}]}`.

**Modifier combos already work.** Ctrl+1 is a custom button with `"keys":["0xA2","0x31"]`. `createCustomButton` presses keys in order, applies the modifier flag, releases in reverse. `"sticky": true` gives long-press-to-latch, which is the existing hold-modifier behavior. So "Ctrl+1 / Alt+2 buttons" needs no new input code, only a way to place many of them per layer.

**Things that contradict or affect the preplan:**
1. The existing `layer` int on `KeyBoardDigitalButton` / `KeyBoardTouchPadButton` is not a visual layer. It only scopes finger-slide-between-buttons, and every button uses `1`. It must not be reused for this feature.
2. The existing `icon` is an Android drawable resource id, not a file. File icons need a new path.
3. Slide-to-press (`checkMovementForAllButtons`) iterates **all** elements and checks only geometry, not visibility. Once buttons on hidden layers overlap visible ones, sliding a finger would fire hidden buttons. This needs a one-line visibility check.
4. The special-keys JSON feeds **both** the on-screen custom buttons and the in-game "Send keys" menu (`GameMenu`). Putting 40 spell buttons there would flood that menu. That is the main reason the new layout should be its own file, not more entries in `special_key`.
5. The bundled sample `assets/config/specialbuttons.json` uses `"data"` for key lists, but both parsers read `"keys"`. The sample would fail to import. Not ours to fix, just don't copy it.
6. Build environment: no `local.properties` and no `ANDROID_HOME` in this shell. JDK 17 is present. The Android SDK path from Android Studio/Antigravity must be set before anything can be built.

## 2. Chosen approach

**A new, optional "layered layout" file, loaded by new code through one hook.** Old profile and special-key imports keep working untouched because they go through paths we don't edit.

### File format (`layout.json`, version 1)

Each button entry is a superset of a special-keys entry, so the same key parsing applies.

```json
{
  "format": "artemis-layers",
  "version": 1,
  "layers": [
    { "id": 0, "name": "Main" },
    { "id": 1, "name": "Ctrl" , "modifiers": ["0xA2"] }
  ],
  "buttons": [
    { "id": "fb",  "layer": 0, "x": 0.80, "y": 0.62, "w": 0.07, "h": 0.07,
      "name": "FB", "icon": "frostbolt.png", "keys": ["0x31"] },
    { "id": "sw1", "layer": "global", "x": 0.70, "y": 0.05, "w": 0.06, "h": 0.06,
      "name": "L2", "action": { "type": "layer", "mode": "hold", "target": 1 } }
  ],
  "sticks": [
    { "id": "move", "layer": [0, 1, 3], "x": 0.03, "y": 0.55, "w": 0.18, "h": 0.32,
      "keys": { "up": "W", "down": "S", "left": "A", "right": "D", "middle": "SPACE" } },
    { "id": "aux",  "layer": [0, 1], "x": 0.60, "y": 0.55, "w": 0.18, "h": 0.32,
      "keys": { "up": "UP", "down": "DOWN", "left": "LEFT", "right": "RIGHT" } }
  ],
  "builtins": {
    "rocker_1": { "layer": "global" },
    "key_29":   { "layers": [0, 2] }
  }
}
```

- `x/y/w/h` are fractions of the screen, so one file works across phones. They are converted to pixels at load.
- `layer`: a layer id, a list of ids, or `"global"`. Missing means global, so a file without layers behaves like today.
- `layers[].modifiers` (optional): prefixed to every button on that layer. A "Ctrl layer" can then reuse plain `0x31` buttons instead of spelling out Ctrl+1 per button.
- `action.type = "layer"` with `mode` = `hold` (momentary), `toggle` (go to target, press again to go back), or `next`/`prev` (cycle).
- `sticks` adds **keyboard** joysticks (sticks that press keys), built from the existing keyboard stick classes. The keyboard overlay ships only one (`rocker_1`, WASD), so a second key-stick must come from the file. Each stick can be on any set of layers, like buttons.
- `gamepad` (optional) assigns layers to the gamepad overlay's real analog sticks, e.g. `{ "leftStick": [0, 1, 3], "rightStick": [0, 1] }`. Without this key the gamepad overlay behaves exactly as today. Supporting it needs one extra small hook in `VirtualController` (visibility check + recenter the stick when its layer hides).
- `builtins` assigns layers to the existing joysticks and keys from `keyboard.json` by elementId. Unlisted builtins stay global. Most layouts will just disable the built-ins and define everything in the file.
- Unknown fields are ignored. Missing icons fall back to `name`.

### Where it hooks into existing code

New files (bulk of the work):
- `LayerManager` holds the active layer, decides visibility per element, releases held keys on layer switch.
- `LayeredLayoutLoader` parses the file and adds elements to the controller.
- `LayeredLayoutStore` handles import (copy JSON and icons into app-private storage) and reading it back.
- `IconCache` decodes PNGs once, scaled to button size.
- `LayerSwitchButton` and `IconDigitalButton` subclass the existing button so drawing changes stay out of upstream files.

Small edits to existing files, each a few lines:
- `KeyBoardController`: own a `LayerManager`; `showEnabledElements()` / `showElements()` respect it.
- `KeyBoardControllerConfigurationLoader`: one call to `LayeredLayoutLoader` before `setOpacity`; make `createCustomButton` package-private for reuse.
- `KeyBoardDigitalButton` and `KeyBoardTouchPadButton`: skip non-visible elements in slide detection.
- `StreamSettings` + `preferences.xml` + `strings.xml`: one "Import layered layout" entry and one "Clear layered layout" entry.

In-app Move/Resize keep working. Edits are saved to the profile by elementId exactly like today and override the file's positions. Re-importing a layout clears those overrides for the file's ids only.

### Import mechanism

Recommended: **one .zip** containing `layout.json` and `icons/*.png`, picked with the system file picker. `java.util.zip` is built in, so no new dependency. A bare `.json` import is also accepted for layouts without icons. Windows "Send to > Compressed folder" makes the zip without any tool.

## 3. Phases

Each phase ends with a buildable debug APK, a commit on a feature branch, a short summary, and on-device test steps. A hand-written sample layout lives in `docs/layered/examples/` so device testing never depends on the web editor.

**Phase 0 — Recon and build (no feature code).**
Findings above. Remaining: set `sdk.dir` in `local.properties`, confirm `gradlew assembleDebug` succeeds unmodified, install on the phone, confirm a stream works. Create branch `feature/layers`.

**Phase 1 — Layers, positions from file, layer switching.**
Format v1 without icons. JSON-only import. `LayerManager`, loader, switch button (hold/toggle/next), keyboard key-sticks from the file, builtins layer assignment, gamepad left/right stick layer hook (hide + recenter), slide-detection fix, key release on switch. No mouse/trackpad changes.
Test: import the example, switch layers in a stream, verify Ctrl+1 reaches WoW, verify hold-switch with a second finger pressing a spell, verify no stuck keys when switching mid-press, verify an old profile and old special-keys file still behave identically.

**Phase 2 — Icons.**
Zip import, `IconCache`, `IconDigitalButton`. Missing or broken PNG falls back to text with no crash and a single log line.
Test: zip with some icons missing and one corrupt PNG.

**Phase 3 — Web editor.**
Single static `editor/index.html` plus JS, no build step, no libraries. Canvas sized to the phone's resolution, drag/resize, per-button key/combo, label, icon, layer. Icon preview from a local folder via a directory picker. Exports `layout.json`; the user zips it with the icons folder (or a small hand-written zip writer later, no library).

**Phase 4 — Polish (optional).**
Active-layer indicator, per-layer opacity, export of the current layered layout with in-app position tweaks folded back into fractions.

## 4. Open decisions (defaults used unless you say otherwise)

1. **Layer switch style.** Default: support hold, toggle and cycle, all in Phase 1.
2. **Layout per profile or one global layout.** Default: one layered layout per existing profile (stored under the profile name), so switching profiles switches layouts.
3. **Sticky buttons on a hidden layer.** Default: a latched sticky button stays latched when its layer hides, so a latched Shift survives a layer switch. Non-sticky pressed buttons are released.
4. **Import as zip vs folder picker.** Default: zip.
5. **Which sticks the layouts use.** DECIDED: support both gamepad analog sticks and keyboard key-sticks. The gamepad stick layer hook is in Phase 1. WoW Classic fully supports controller input (confirmed by user).
7. **Mouse input.** DECIDED: no changes. Absolute-touch and trackpad modes, and the existing touchpad buttons, are left exactly as they are.
6. **Builtin keys from keyboard.json.** Default: kept and global unless `builtins` says otherwise. They can already be disabled in-app.

## 5. On the working rules in PREPLAN.md

"One phase at a time, stop, summarize, give device test steps" is a good strategy here. Every phase changes on-screen input that only a real device and a real WoW session can verify, and the upstream rebase cost grows with every unreviewed change. Two adjustments:
- Each phase should end with a commit on a feature branch, so a rebase or rollback is per phase.
- "At most one question at a time" is fine mid-phase. At the start of a phase, batching the questions that block it saves round trips.
