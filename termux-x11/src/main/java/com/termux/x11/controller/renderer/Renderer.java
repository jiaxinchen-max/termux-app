package com.termux.x11.controller.renderer;

import com.termux.x11.LorieView;

/**
 * 终端侧适配层：LorieView 由原生 lorie 渲染，没有 winlator 的 GLRenderer。
 * 这里只保留触摸板坐标换算需要的状态，捏合缩放不改变实际渲染（降级行为）。
 */
public class Renderer {
    private final LorieView xServer;
    private float pinchZoom = 1.0f;
    private float pinchAnchorX = 0.0f;
    private float pinchAnchorY = 0.0f;

    public Renderer(LorieView xServer) {
        this.xServer = xServer;
    }

    public boolean isFullscreen() {
        return xServer != null && xServer.isFullscreen();
    }

    public float getPinchZoom() {
        return pinchZoom;
    }

    public void setPinchZoom(float pinchZoom, float anchorX, float anchorY) {
        this.pinchZoom = pinchZoom;
        this.pinchAnchorX = anchorX;
        this.pinchAnchorY = anchorY;
    }

    public float getPinchAnchorX() {
        return pinchAnchorX;
    }

    public float getPinchAnchorY() {
        return pinchAnchorY;
    }

    public void resetPinchZoom() {
        pinchZoom = 1.0f;
        pinchAnchorX = 0.0f;
        pinchAnchorY = 0.0f;
    }
}
