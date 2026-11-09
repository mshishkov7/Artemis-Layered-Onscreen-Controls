Project: Artemis fork — button layers + custom icons

Fork of ClassicOldSong/moonlight-android (Artemis / "Moonlight Noir"), an Android game-streaming client. I'm adding layers and custom icons to its on-screen buttons, with the layout authored in a web editor on my PC and imported into the app. Button should be able to be ctrl+1 or ctrl+2 or alt+1 alt+2 etc like be able to do a modifier click 

Why

I play WoW (Classic mostly) over Sunshine/Apollo through Tailscale on mobile networks. I need many spell buttons on a touchscreen with joysticks. Existing on-screen controls have no layers and no icons.

Hard constraints
All input stays inside the Moonlight stream. No second network connection, no companion receiver on the PC. Latency and drops over mobile/Tailscale are the reason.
Do not touch the streaming, decoding, or networking code. Changes live in the on-screen controls / custom keyboard layer only.
Keep the fork diff small so I can rebase on upstream releases (the maintainer ships often). Prefer new files and small hooks over edits to existing logic.
Backward compatible: existing layouts and the existing import/export must keep working unchanged. New fields are optional.
Upstream is GPLv3; keep license headers intact.
Working rules for the agent
Work one phase at a time. Stop at the end of each phase, summarize what changed, and tell me how to test it on a device. Don't start the next phase until I say so.(tell me if this is ok strategy if now why)
Read before writing. Don't assume the code structure. Phase 0 exists to find out.
If something I describe contradicts what the code actually does, say so instead of working around it.
Be concise. Ask at most one question at a time.
Don't add dependencies without asking.
What I need (requirements)
Layers: a layout contains N layers (target 5-6). Each layer is its own set of buttons. One or more layer-switch buttons change the active layer. Joysticks and plain key buttons can be global (shown on every layer) or per-layer.
Icons: a button may reference an icon by file name. If the file exists it's drawn on the button; if not, fall back to the text label. No hard failure on a missing icon. (this should be for phase 2 cuz its not essential for working app)
Import from outside the phone: one layout file (JSON, preferably an extension of the existing format) plus a folder of PNG icons. Editing happens on the PC, not in the app.
Web editor (separate, later): a static, local HTML/JS page. Drag buttons on a canvas, set key/combo, label, icon name, layer. Exports the layout file. Previews icons from a local folder by name.
Existing features I rely on and must not break: on-screen joysticks, key buttons, key combos, hold-modifier behavior, Profiles, absolute-touch/trackpad modes.
Phases
Phase 0 — Recon (no code changes)

Goal: understand the codebase and decide the approach.

Windows 11 PC, Android phone as client, Antigravity + Claude Code.