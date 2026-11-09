package com.limelight.binding.input.virtual_controller.keyboard;

/**
 * Implemented by on-screen elements that can hold input state (a pressed key, a deflected
 * stick). When the element's layer is hidden it is told to let go, so no key stays stuck down.
 */
public interface LayerReleasable {
    void releaseForLayerSwitch();
}
