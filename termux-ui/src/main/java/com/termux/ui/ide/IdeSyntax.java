package com.termux.ui.ide;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;
import java.util.List;
import java.util.concurrent.*;

/** Debounced lexical colours; scanning never runs on the UI or mutable editor buffer. */
public final class IdeSyntax implements TextWatcher {
    private final EditText editor;
    private final boolean xml;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable update = this::highlight;
    private Future<?> pending;
    private int revision;
    private boolean detached;
    private static final class SyntaxSpan extends ForegroundColorSpan {
        SyntaxSpan(int colour) { super(colour); }
    }
    public IdeSyntax(EditText editor, String name) {
        this.editor = editor; xml = name.endsWith(".xml");
        if (xml || name.endsWith(".java") || name.endsWith(".gradle")) {
            editor.addTextChangedListener(this); handler.post(update);
        }
    }
    public void detach() {
        detached = true; revision++; handler.removeCallbacksAndMessages(null);
        editor.removeTextChangedListener(this); worker.shutdownNow();
    }
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { revision++; }
    @Override public void afterTextChanged(Editable e) {
        handler.removeCallbacks(update); handler.postDelayed(update, 180);
    }
    private void highlight() {
        if (detached) return;
        String snapshot = editor.getText().toString(); int version = revision;
        if (pending != null) pending.cancel(true);
        pending = worker.submit(() -> {
            List<IdeSyntaxTokens.Token> tokens = IdeSyntaxTokens.scan(snapshot, xml);
            handler.post(() -> {
                if (detached || version != revision) return;
                Editable text = editor.getText();
                boolean dark = (editor.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                int keyword = Color.parseColor(dark ? "#91BFFF" : "#1757A0");
                int literal = Color.parseColor(dark ? "#AACD8A" : "#387030");
                editor.beginBatchEdit();
                try {
                    for (SyntaxSpan span : text.getSpans(0, text.length(), SyntaxSpan.class)) text.removeSpan(span);
                    for (IdeSyntaxTokens.Token token : tokens)
                        text.setSpan(new SyntaxSpan(token.literal ? literal : keyword), token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } finally { editor.endBatchEdit(); }
            });
        });
    }
}
