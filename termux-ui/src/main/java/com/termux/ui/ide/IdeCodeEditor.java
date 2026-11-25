package com.termux.ui.ide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.content.ContextCompat;
import com.termux.ui.R;

/** Fixed line-number gutter drawn only for visible lines, following the editor's own scroll. */
public final class IdeCodeEditor extends AppCompatEditText {
    private final Paint numbers = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint background = new Paint();
    private int gutter;
    public IdeCodeEditor(Context context) {
        super(context);
        numbers.setTextAlign(Paint.Align.RIGHT);
        numbers.setTextSize(dp(11));
        numbers.setColor(ContextCompat.getColor(context, R.color.local_games_on_surface_secondary));
        background.setColor(ContextCompat.getColor(context, R.color.local_games_background));
        updateGutter();
    }
    @Override protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        if (numbers != null) updateGutter();
    }
    private void updateGutter() {
        int lines = 1;
        for (int i = 0; i < length(); i++) if (getText().charAt(i) == '\n') lines++;
        int width = Math.max(dp(44), (int) numbers.measureText(Integer.toString(lines)) + dp(22));
        if (width != gutter) { gutter = width; setPadding(gutter + dp(8), dp(10), dp(14), dp(10)); }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = getLayout();
        if (layout == null) return;
        int top = getScrollY(), left = getScrollX();
        int save = canvas.save();
        canvas.clipRect(left, top, left + gutter, top + getHeight());
        canvas.drawRect(left, top, left + gutter, top + getHeight(), background);
        int first = layout.getLineForVertical(Math.max(0, top - getTotalPaddingTop()));
        int last = layout.getLineForVertical(Math.max(0, top + getHeight() - getTotalPaddingTop()));
        for (int line = first; line <= last; line++)
            canvas.drawText(Integer.toString(line + 1), left + gutter - dp(10),
                getTotalPaddingTop() + layout.getLineBaseline(line), numbers);
        canvas.restoreToCount(save);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
