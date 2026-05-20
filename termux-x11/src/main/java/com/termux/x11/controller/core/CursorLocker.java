package com.termux.x11.controller.core;

import com.termux.x11.LorieView;
import com.termux.x11.controller.math.Mathf;

public class CursorLocker {
    private static final float VIEWPORT_SCALE = 1.25f;

    private final LorieView xServer;
    private boolean enabled = false;
    private int panX = 0;
    private int panY = 0;

    public CursorLocker(LorieView xServer) {
        this.xServer = xServer;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled)
            resetPan();
        else
            xServer.refreshViewport();
    }

    public float getViewportScale() {
        return enabled ? VIEWPORT_SCALE : 1.0f;
    }

    public int getPanX() {
        return enabled ? panX : 0;
    }

    public int getPanY() {
        return enabled ? panY : 0;
    }

    public void panBy(int dx, int dy) {
        if (!enabled)
            return;
        panX += dx;
        panY += dy;
        xServer.refreshViewport();
    }

    public void clampPan(int minX, int maxX, int minY, int maxY) {
        panX = (int) Mathf.clamp(panX, minX, maxX);
        panY = (int) Mathf.clamp(panY, minY, maxY);
    }

    public void resetPan() {
        panX = 0;
        panY = 0;
        xServer.refreshViewport();
    }
}
