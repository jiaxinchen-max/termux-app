// Copyright 2013 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.termux.x11.input;

import android.graphics.PointF;

/**
 * This class stores UI configuration that will be used when rendering the remote desktop.
 */
public class RenderData {
    public PointF scale = new PointF();

    public int screenWidth;
    public int screenHeight;
    public int imageWidth;
    public int imageHeight;
    public int offsetX;
    public int offsetY;
    public int viewportOffsetX;
    public int viewportOffsetY;
    public int viewportWidth;
    public int viewportHeight;

    /**
     * Specifies the position, in image coordinates, at which the cursor image will be drawn.
     * This will normally be at the location of the most recently injected motion event.
     */
    private final PointF mCursorPosition = new PointF();

    /**
     * Returns the position of the rendered cursor.
     *
     * @return A point representing the current position.
     */
    public PointF getCursorPosition() {
        return new PointF(mCursorPosition.x, mCursorPosition.y);
    }

    public float mapScreenX(float x) {
        int width = viewportWidth > 0 ? viewportWidth : imageWidth;
        return width > 0 ? (x - viewportOffsetX) * (float) screenWidth / width : x;
    }

    public float mapScreenY(float y) {
        int height = viewportHeight > 0 ? viewportHeight : imageHeight;
        return height > 0 ? (y - viewportOffsetY) * (float) screenHeight / height : y;
    }

    public boolean isInsideViewport(float x, float y, float epsilon) {
        int width = viewportWidth > 0 ? viewportWidth : imageWidth;
        int height = viewportHeight > 0 ? viewportHeight : imageHeight;
        return x >= viewportOffsetX - epsilon &&
            x <= viewportOffsetX + width + epsilon &&
            y >= viewportOffsetY - epsilon &&
            y <= viewportOffsetY + height + epsilon;
    }

    /**
     * Sets the position of the cursor which is used for rendering.
     *
     * @param newX The new value of the x coordinate.
     * @param newY The new value of the y coordinate
     * @return True if the cursor position has changed.
     */
    public boolean setCursorPosition(float newX, float newY) {
        boolean cursorMoved = false;
        if ((newX-offsetX) != mCursorPosition.x) {
            mCursorPosition.x = newX-offsetX;
            cursorMoved = true;
        }
        if ((newY-offsetY) != mCursorPosition.y) {
            mCursorPosition.y = newY-offsetY;
            cursorMoved = true;
        }
        return cursorMoved;
    }
}
