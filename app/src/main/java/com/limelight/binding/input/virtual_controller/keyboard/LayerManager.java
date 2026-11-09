package com.limelight.binding.input.virtual_controller.keyboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks which layer of a layered layout is active and which elements belong to which layer.
 *
 * With no layered layout loaded (layer ids empty) every query answers "visible", so the
 * overlays behave exactly as they did before layers existed.
 */
public final class LayerManager {
    private static final LayerManager INSTANCE = new LayerManager();

    public static LayerManager get() {
        return INSTANCE;
    }

    // Keyboard overlay elements by elementId. Absent means global (shown on every layer).
    private final Map<String, int[]> elementLayers = new HashMap<>();
    // Gamepad overlay elements by VirtualControllerElement.EID_*. Absent means global.
    private final Map<Integer, int[]> gamepadLayers = new HashMap<>();

    private int[] layerIds = new int[0];
    private int activeLayer = 0;
    private KeyBoardController controller;
    private Runnable gamepadListener;

    private LayerManager() {}

    /** Forget the layout and the active layer (a new stream session starts). */
    public synchronized void reset(KeyBoardController newController) {
        clearLayout();
        activeLayer = 0;
        controller = newController;
    }

    /** Forget the loaded layout but keep the active layer if it is still valid. */
    public synchronized void clearLayout() {
        elementLayers.clear();
        gamepadLayers.clear();
        layerIds = new int[0];
    }

    public synchronized void begin(int[] ids) {
        layerIds = ids;
        if (!hasLayer(activeLayer)) {
            activeLayer = ids[0];
        }
    }

    public synchronized void assign(String elementId, int[] layers) {
        if (layers != null) {
            elementLayers.put(elementId, layers);
        }
    }

    public synchronized void assignGamepad(int gamepadElementId, int[] layers) {
        if (layers != null) {
            gamepadLayers.put(gamepadElementId, layers);
        }
    }

    public void setGamepadListener(Runnable listener) {
        gamepadListener = listener;
    }

    public synchronized boolean hasLayers() {
        return layerIds.length > 0;
    }

    public synchronized int getActive() {
        return activeLayer;
    }

    public synchronized boolean hasLayer(int id) {
        for (int l : layerIds) {
            if (l == id) return true;
        }
        return false;
    }

    private static boolean contains(int[] layers, int id) {
        for (int l : layers) {
            if (l == id) return true;
        }
        return false;
    }

    public synchronized boolean isActive(keyBoardVirtualControllerElement element) {
        if (layerIds.length == 0) return true;
        int[] layers = elementLayers.get(element.elementId);
        return layers == null || contains(layers, activeLayer);
    }

    public boolean isHiddenByLayer(keyBoardVirtualControllerElement element) {
        return !isActive(element);
    }

    public synchronized boolean isGamepadActive(int gamepadElementId) {
        if (layerIds.length == 0) return true;
        int[] layers = gamepadLayers.get(gamepadElementId);
        return layers == null || contains(layers, activeLayer);
    }

    public void setActive(int id) {
        KeyBoardController c;
        Runnable g;
        synchronized (this) {
            if (layerIds.length == 0 || id == activeLayer || !hasLayer(id)) return;
            activeLayer = id;
            c = controller;
            g = gamepadListener;
        }
        if (c != null) c.onLayerChanged();
        if (g != null) g.run();
    }

    public void step(int delta) {
        int target;
        synchronized (this) {
            if (layerIds.length == 0) return;
            int idx = 0;
            for (int i = 0; i < layerIds.length; i++) {
                if (layerIds[i] == activeLayer) idx = i;
            }
            target = layerIds[((idx + delta) % layerIds.length + layerIds.length) % layerIds.length];
        }
        setActive(target);
    }

    /** Called after a layout finished loading so already-created overlays pick it up. */
    public void notifyLoaded() {
        Runnable g;
        synchronized (this) {
            g = gamepadListener;
        }
        if (g != null) g.run();
    }
}
