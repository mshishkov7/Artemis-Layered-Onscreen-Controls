package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.virtual_controller.VirtualControllerElement;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyMapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the elements of an imported layered layout (see LayeredLayoutStore) and wires up
 * layer switching. Does nothing when no layout is imported.
 */
public final class LayeredLayoutLoader {
    private static final String ID_PREFIX = "lyr_";

    private LayeredLayoutLoader() {}

    static String buttonElementId(String id) {
        return ID_PREFIX + id;
    }

    static String stickElementId(String id) {
        return ID_PREFIX + "stick_" + id;
    }

    /**
     * Step 1: add the file's buttons and sticks to the controller. Returns the parsed layout
     * (to pass to finish) or null when there is no layout or it is unusable.
     */
    public static JSONObject addElements(KeyBoardController controller, Context context, NvConnection conn) {
        LayerManager layers = LayerManager.get();
        layers.clearLayout();
        IconCache.clear();

        String text = LayeredLayoutStore.read(context);
        if (text == null) return null;

        JSONObject root;
        try {
            root = new JSONObject(text);
            if (!LayeredLayoutStore.FORMAT.equals(root.optString("format"))) {
                throw new JSONException("wrong format");
            }
        } catch (JSONException e) {
            LimeLog.warning("Layered layout ignored: " + e.getMessage());
            Toast.makeText(context, R.string.layered_layout_unreadable, Toast.LENGTH_LONG).show();
            return null;
        }

        // Layers, in cycle order
        List<Integer> ids = new ArrayList<>();
        JSONArray layerArr = root.optJSONArray("layers");
        for (int i = 0; layerArr != null && i < layerArr.length(); i++) {
            JSONObject l = layerArr.optJSONObject(i);
            if (l == null || !l.has("id")) continue;
            ids.add(l.optInt("id"));
        }
        if (ids.isEmpty()) ids.add(0);
        int[] idArray = new int[ids.size()];
        for (int i = 0; i < idArray.length; i++) idArray[i] = ids.get(i);
        layers.begin(idArray);

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        int skipped = 0;

        JSONArray buttons = root.optJSONArray("buttons");
        for (int i = 0; buttons != null && i < buttons.length(); i++) {
            try {
                addButton(controller, context, conn, layers, root, buttons.getJSONObject(i), screen);
            } catch (Exception e) {
                skipped++;
                LimeLog.warning("Layered layout: skipped button #" + i + ": " + e);
            }
        }

        JSONArray sticks = root.optJSONArray("sticks");
        for (int i = 0; sticks != null && i < sticks.length(); i++) {
            try {
                addStick(controller, context, layers, config, sticks.getJSONObject(i), screen);
            } catch (Exception e) {
                skipped++;
                LimeLog.warning("Layered layout: skipped stick #" + i + ": " + e);
            }
        }

        JSONObject gamepad = root.optJSONObject("gamepad");
        if (gamepad != null) {
            layers.assignGamepad(VirtualControllerElement.EID_LS, parseLayers(gamepad.opt("leftStick")));
            layers.assignGamepad(VirtualControllerElement.EID_RS, parseLayers(gamepad.opt("rightStick")));
        }

        if (skipped > 0) {
            Toast.makeText(context, context.getString(R.string.layered_layout_skipped, skipped), Toast.LENGTH_LONG).show();
        }
        return root;
    }

    /**
     * Step 2, after saved profile positions were applied: layer assignment and enabled state of
     * the built-in elements, opacity, and initial visibility.
     */
    public static void finish(KeyBoardController controller, Context context, JSONObject root) {
        if (root == null) return;
        LayerManager layers = LayerManager.get();

        JSONObject builtins = root.optJSONObject("builtins");
        boolean hideBuiltins = root.optBoolean("hideBuiltins", false);
        for (keyBoardVirtualControllerElement element : controller.getElements()) {
            if (element.elementId.startsWith(ID_PREFIX)) continue;
            JSONObject spec = builtins != null ? builtins.optJSONObject(element.elementId) : null;
            if (spec == null) {
                if (hideBuiltins) element.enabled = false;
                continue;
            }
            layers.assign(element.elementId, parseLayers(spec.opt("layer")));
            if (spec.has("enabled")) {
                element.enabled = spec.optBoolean("enabled");
            } else if (hideBuiltins) {
                element.enabled = true;
            }
        }

        controller.setOpacity(PreferenceConfiguration.readPreferences(context).oscOpacity);
        controller.applyLayerVisibility();
        layers.notifyLoaded();
    }

    private static void addButton(KeyBoardController controller, Context context, NvConnection conn,
                                  LayerManager layers, JSONObject root, JSONObject b,
                                  DisplayMetrics screen) throws Exception {
        String id = b.getString("id");
        String elementId = buttonElementId(id);
        String name = b.optString("name", "");
        int[] onLayers = parseLayers(b.opt("layer"));

        KeyBoardDigitalButton button;
        JSONObject action = b.optJSONObject("action");
        if (action != null) {
            if (!"layer".equals(action.optString("type"))) {
                throw new JSONException("unknown action type");
            }
            button = createSwitchButton(controller, context, layers, elementId, name, action);
        } else {
            JSONArray keyArr = b.getJSONArray("keys");
            List<Short> keys = new ArrayList<>();
            // A layer's "modifiers" are held with every button that lives only on that layer
            if (onLayers != null && onLayers.length == 1) {
                JSONArray layerArr = root.optJSONArray("layers");
                for (int i = 0; layerArr != null && i < layerArr.length(); i++) {
                    JSONObject l = layerArr.optJSONObject(i);
                    JSONArray mods = l != null && l.optInt("id", Integer.MIN_VALUE) == onLayers[0]
                            ? l.optJSONArray("modifiers") : null;
                    for (int j = 0; mods != null && j < mods.length(); j++) {
                        keys.add(parseVk(mods.getString(j)));
                    }
                }
            }
            for (int i = 0; i < keyArr.length(); i++) {
                keys.add(parseVk(keyArr.getString(i)));
            }
            if (keys.isEmpty()) throw new JSONException("no keys");
            short[] vk = new short[keys.size()];
            for (int i = 0; i < vk.length; i++) vk[i] = keys.get(i);
            button = KeyBoardControllerConfigurationLoader.createCustomButton(
                    elementId, vk, 1, name, -1, b.optBoolean("sticky", false), controller, conn, context);
        }

        String icon = b.optString("icon", "");
        if (!icon.isEmpty()) {
            if (LayeredLayoutStore.isPlainFileName(icon)) {
                button.setIconFile(icon);
                button.setIconAdjust((float) b.optDouble("iconScale", 1.0),
                        (float) b.optDouble("iconDx", 0.0), (float) b.optDouble("iconDy", 0.0));
            } else {
                LimeLog.warning("Layered layout: ignoring icon \"" + icon + "\" (must be a plain file name)");
            }
        }

        String shape = b.optString("shape", root.optString("shape", ""));
        if ("square".equals(shape)) button.setSquareShape(true);
        else if ("round".equals(shape)) button.setSquareShape(false);

        layers.assign(elementId, onLayers);
        place(controller, button, b, screen);
    }

    private static KeyBoardDigitalButton createSwitchButton(
            final KeyBoardController controller, Context context, final LayerManager layers,
            String elementId, String name, JSONObject action) throws JSONException {
        final String mode = action.optString("mode", "hold");
        final int target = action.optInt("target", -1);
        if (("hold".equals(mode) || "toggle".equals(mode)) && !layers.hasLayer(target)) {
            throw new JSONException("unknown target layer " + target);
        }
        if (!"hold".equals(mode) && !"toggle".equals(mode) && !"next".equals(mode) && !"prev".equals(mode)) {
            throw new JSONException("unknown mode " + mode);
        }

        final KeyBoardDigitalButton button = new KeyBoardDigitalButton(controller, elementId, 1, context);
        button.setText(name);
        button.addDigitalButtonListener(new KeyBoardDigitalButton.DigitalButtonListener() {
            private int returnTo;
            private boolean held;

            @Override
            public void onClick() {
                controller.vibrate(KeyEvent.ACTION_DOWN);
                switch (mode) {
                    case "hold":
                        if (!held) {
                            held = true;
                            returnTo = layers.getActive();
                        }
                        layers.setActive(target);
                        break;
                    case "toggle":
                        if (layers.getActive() == target) {
                            layers.setActive(returnTo);
                        } else {
                            returnTo = layers.getActive();
                            layers.setActive(target);
                        }
                        break;
                    case "next":
                        layers.step(1);
                        break;
                    case "prev":
                        layers.step(-1);
                        break;
                }
            }

            @Override
            public void onLongClick() {
            }

            @Override
            public void onRelease() {
                if (held) {
                    held = false;
                    layers.setActive(returnTo);
                }
            }
        });
        return button;
    }

    private static void addStick(KeyBoardController controller, Context context, LayerManager layers,
                                 PreferenceConfiguration config, JSONObject s,
                                 DisplayMetrics screen) throws Exception {
        String elementId = stickElementId(s.getString("id"));
        JSONObject k = s.getJSONObject("keys");
        int[] keys = new int[]{
                parseAndroidKey(k.getString("up")),
                parseAndroidKey(k.getString("down")),
                parseAndroidKey(k.getString("left")),
                parseAndroidKey(k.getString("right")),
                k.has("middle") ? parseAndroidKey(k.getString("middle")) : KeyEvent.KEYCODE_UNKNOWN
        };

        // Same listener the built-in stick uses, except that an unset middle key sends nothing
        if (config.enableNewAnalogStick) {
            KeyBoardAnalogStickButtonFree stick = new KeyBoardAnalogStickButtonFree(controller, elementId, context, keys);
            stick.setListener((code, isPress) -> sendStickKey(controller, code, isPress));
            place(controller, stick, s, screen);
        } else {
            KeyBoardAnalogStickButton stick = new KeyBoardAnalogStickButton(controller, elementId, context, keys);
            stick.setListener((code, isPress) -> sendStickKey(controller, code, isPress));
            place(controller, stick, s, screen);
        }
        layers.assign(elementId, parseLayers(s.opt("layer")));
    }

    private static void sendStickKey(KeyBoardController controller, int code, boolean isPress) {
        if (code == KeyEvent.KEYCODE_UNKNOWN) return;
        KeyEvent event = new KeyEvent(isPress ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code);
        event.setSource(2);
        controller.sendKeyEvent(event);
    }

    private static void place(KeyBoardController controller, keyBoardVirtualControllerElement element,
                              JSONObject spec, DisplayMetrics screen) throws JSONException {
        int w = Math.max(1, Math.round((float) spec.getDouble("w") * screen.widthPixels));
        int h = Math.max(1, Math.round((float) spec.getDouble("h") * screen.heightPixels));
        int x = Math.round((float) spec.getDouble("x") * screen.widthPixels);
        int y = Math.round((float) spec.getDouble("y") * screen.heightPixels);
        controller.addElement(element, x, y, w, h);
    }

    /** null = global. Accepts a number, a list of numbers, or "global". */
    static int[] parseLayers(Object spec) {
        if (spec instanceof Number) {
            return new int[]{((Number) spec).intValue()};
        }
        if (spec instanceof JSONArray) {
            JSONArray arr = (JSONArray) spec;
            int[] out = new int[arr.length()];
            for (int i = 0; i < out.length; i++) out[i] = arr.optInt(i, Integer.MIN_VALUE);
            return out;
        }
        return null;
    }

    /** Windows virtual-key code: "0x31", "VK_RETURN", or a convenience name like "A", "F5", "CTRL". */
    private static final java.util.Map<String, Short> ALIASES = new java.util.HashMap<>();
    static {
        Object[][] t = {
            {"BACKSPACE", 0x08}, {"BKSP", 0x08}, {"RETURN", 0x0D}, {"ESCAPE", 0x1B}, {"CAPS", 0x14}, {"CAPSLOCK", 0x14},
            {"PAUSE", 0x13}, {"PGUP", 0x21}, {"PAGEUP", 0x21}, {"PGDN", 0x22}, {"PAGEDOWN", 0x22}, {"END", 0x23}, {"HOME", 0x24},
            {"LEFT", 0x25}, {"UP", 0x26}, {"RIGHT", 0x27}, {"DOWN", 0x28}, {"PRINTSCREEN", 0x2C}, {"INSERT", 0x2D},
            {"DELETE", 0x2E}, {"DEL", 0x2E},
            {"LSHIFT", 0xA0}, {"RSHIFT", 0xA1}, {"LCTRL", 0xA2}, {"RCTRL", 0xA3}, {"LALT", 0xA4}, {"RALT", 0xA5},
            {"SEMICOLON", 0xBA}, {"EQUALS", 0xBB}, {"PLUS", 0xBB}, {"COMMA", 0xBC}, {"MINUS", 0xBD}, {"PERIOD", 0xBE},
            {"SLASH", 0xBF}, {"TILDE", 0xC0}, {"GRAVE", 0xC0}, {"BACKTICK", 0xC0},
            {"LBRACKET", 0xDB}, {"BACKSLASH", 0xDC}, {"RBRACKET", 0xDD}, {"QUOTE", 0xDE},
            {"MOUSE_LEFT", 0x1001}, {"MOUSE_MIDDLE", 0x1002}, {"MOUSE_RIGHT", 0x1003}, {"MOUSE_X1", 0x1004}, {"MOUSE_X2", 0x1005},
        };
        for (Object[] e : t) ALIASES.put((String) e[0], (short) (int) (Integer) e[1]);
        for (int i = 0; i < 10; i++) ALIASES.put("NUM" + i, (short) (0x60 + i));
    }

    static short parseVk(String code) throws Exception {
        String c = code.trim();
        if (c.startsWith("0x") || c.startsWith("0X")) {
            return (short) Integer.parseInt(c.substring(2), 16);
        }
        if (c.startsWith("VK_")) {
            return (short) KeyMapper.class.getDeclaredField(c).getInt(null);
        }
        String u = c.toUpperCase(Locale.ROOT);
        if (u.length() == 1 && ((u.charAt(0) >= 'A' && u.charAt(0) <= 'Z') || (u.charAt(0) >= '0' && u.charAt(0) <= '9'))) {
            return (short) u.charAt(0);
        }
        if (u.matches("F([1-9]|1[0-9]|2[0-4])")) {
            return (short) (0x70 + Integer.parseInt(u.substring(1)) - 1);
        }
        Short alias = ALIASES.get(u);
        if (alias != null) return alias;
        switch (u) {
            case "CTRL": return 0xA2;
            case "SHIFT": return 0xA0;
            case "ALT": return 0xA4;
            case "WIN":
            case "META": return 0x5B;
            case "SPACE": return 0x20;
            case "ENTER": return 0x0D;
            case "ESC": return 0x1B;
            case "TAB": return 0x09;
            default: throw new Exception("Unknown key: " + code);
        }
    }

    /** Android keycode: "W", "KEYCODE_W", "DPAD_UP", or a number. */
    static int parseAndroidKey(String name) throws Exception {
        String n = name.trim();
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException ignored) {
            // not numeric, look it up by name
        }
        String full = n.toUpperCase(Locale.ROOT);
        if (!full.startsWith("KEYCODE_")) full = "KEYCODE_" + full;
        int code = KeyEvent.keyCodeFromString(full);
        if (code == KeyEvent.KEYCODE_UNKNOWN) throw new Exception("Unknown key: " + name);
        return code;
    }
}
