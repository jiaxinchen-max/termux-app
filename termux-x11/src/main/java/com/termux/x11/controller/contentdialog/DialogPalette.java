package com.termux.x11.controller.contentdialog;

/**
 * Optional colour palette a host can supply so ContentDialogs (task manager and its child dialogs)
 * match the host's visual style. When no palette is supplied the dialogs keep the stock
 * termux-x11 look. Colours are ARGB ints resolved by the host at dialog creation time.
 */
public final class DialogPalette {
    public final int background;
    public final int outline;
    public final int accent;
    public final int onSurface;
    public final int onSurfaceSecondary;
    public final int panel;
    public final int tableHead;
    public final int tableHeadText;
    public final int buttonFill;
    public final int buttonText;
    public final float cornerRadiusDp;

    public DialogPalette(int background, int outline, int accent, int onSurface, int onSurfaceSecondary,
                         int panel, int tableHead, int tableHeadText, int buttonFill, int buttonText,
                         float cornerRadiusDp) {
        this.background = background;
        this.outline = outline;
        this.accent = accent;
        this.onSurface = onSurface;
        this.onSurfaceSecondary = onSurfaceSecondary;
        this.panel = panel;
        this.tableHead = tableHead;
        this.tableHeadText = tableHeadText;
        this.buttonFill = buttonFill;
        this.buttonText = buttonText;
        this.cornerRadiusDp = cornerRadiusDp;
    }

    /** Resolved lazily by the host so night-mode changes are honoured on every dialog creation. */
    public interface Provider {
        DialogPalette getDialogPalette();
    }
}
