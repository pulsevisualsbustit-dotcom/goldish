package dev.velora.render;

/**
 * A HUD element that can be moved around while the chat screen is open.
 */
public interface HudElement {

    float hudX();

    float hudY();

    float hudWidth();

    float hudHeight();

    void moveHud(float x, float y);

    boolean isEnabled();

    String getName();
}
