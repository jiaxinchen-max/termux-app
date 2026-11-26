package com.termux.ui.ide;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import androidx.core.content.ContextCompat;
import com.termux.ui.R;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/** Host configuration for the exact Sora editor revision used by AndroidIDE. */
public final class IdeCodeEditor extends CodeEditor {
    private Integer pendingScrollY;
    public IdeCodeEditor(Context context) {
        super(context);
        setTextSize(14);
        setTypefaceText(Typeface.MONOSPACE);
        setLineNumberEnabled(true);
        setPinLineNumber(true);
        setTabWidth(4);
        setWordwrap(false);
        setUndoEnabled(true);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Keep code and identifiers out of keyboard fullscreen/personalized learning.
        getProps().disallowSuggestions = true;
        getProps().allowFullscreen = false;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        setColorScheme(dark ? new io.github.rosemoe.sora.widget.schemes.SchemeDarcula()
            : new EditorColorScheme());
        EditorColorScheme colors = getColorScheme();
        int surface = ContextCompat.getColor(context, R.color.local_games_background);
        colors.setColor(EditorColorScheme.WHOLE_BACKGROUND, surface);
        colors.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, surface);
        colors.setColor(EditorColorScheme.LINE_DIVIDER, surface);
        colors.setColor(EditorColorScheme.TEXT_NORMAL,
            ContextCompat.getColor(context, R.color.local_games_on_surface));
        colors.setColor(EditorColorScheme.LINE_NUMBER,
            ContextCompat.getColor(context, R.color.local_games_on_surface_secondary));
        colors.setColor(EditorColorScheme.KEYWORD, Color.parseColor(dark ? "#91BFFF" : "#1757A0"));
        colors.setColor(EditorColorScheme.LITERAL, Color.parseColor(dark ? "#AACD8A" : "#387030"));
    }
    @Override public android.view.inputmethod.InputConnection onCreateInputConnection(
            android.view.inputmethod.EditorInfo attributes) {
        android.view.inputmethod.InputConnection connection = super.onCreateInputConnection(attributes);
        attributes.imeOptions |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return connection;
    }
    @Override public void setText(CharSequence text) {
        super.setText(text instanceof IdeEditorBuffer ? text : new IdeEditorBuffer(text));
    }
    /** UTF16 offsets match document storage and Sora's indexer. */
    public int getSelectionStart() { return getCursor().getLeft(); }
    public int length() { return getText().length(); }
    public void setSelection(int offset) {
        io.github.rosemoe.sora.text.CharPosition position = getText().getIndexer()
            .getCharPosition(Math.max(0, Math.min(offset, length())));
        setSelection(position.line, position.column);
    }
    public void restoreScroll(int y) {
        pendingScrollY = y;
        applyPendingScroll();
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyPendingScroll();
    }
    @Override public void setLayoutBusy(boolean busy) {
        super.setLayoutBusy(busy);
        if (!busy) applyPendingScroll();
    }
    private void applyPendingScroll() {
        if (pendingScrollY == null || getHeight() <= 0 || !isEditable()) return;
        int offset = Math.max(0, Math.min(pendingScrollY, getScrollMaxY()));
        pendingScrollY = null;
        getScroller().forceFinished(true);
        getScroller().startScroll(getOffsetX(), offset, 0, 0, 0);
        getScroller().abortAnimation();
        invalidate();
    }
}
