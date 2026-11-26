package com.termux.ui.ide;

import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.*;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SubscriptionReceipt;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/** Temporary lexical colours while the full project language services are ported. */
public final class IdeSyntax {
    private final IdeCodeEditor editor;
    private final boolean xml;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable update = this::highlight;
    private SubscriptionReceipt<ContentChangeEvent> subscription;
    private Future<?> pending;
    private int revision;
    private boolean detached;
    public IdeSyntax(IdeCodeEditor editor, String name) {
        this.editor = editor; xml = name.endsWith(".xml");
        if (xml || name.endsWith(".java") || name.endsWith(".gradle")) {
            subscription = editor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
                revision++;
                handler.removeCallbacks(update); handler.postDelayed(update, 180);
            });
            handler.post(update);
        }
    }
    public void detach() {
        detached = true; revision++; handler.removeCallbacksAndMessages(null);
        if (subscription != null) subscription.unsubscribe();
        worker.shutdownNow();
    }
    private void highlight() {
        if (detached) return;
        String snapshot = editor.getText().toString(); int version = revision;
        if (pending != null) pending.cancel(true);
        pending = worker.submit(() -> {
            Styles styles = analyze(snapshot, xml);
            if (styles == null) return;
            handler.post(() -> {
                if (!detached && version == revision) editor.setStyles(styles);
            });
        });
    }
    static Styles analyze(String snapshot, boolean xml) {
        List<IdeSyntaxTokens.Token> tokens = IdeSyntaxTokens.scan(snapshot, xml);
        int[] colors = new int[snapshot.length()];
        java.util.Arrays.fill(colors, EditorColorScheme.TEXT_NORMAL);
        // Scanner emits literals last; quoted/commented keywords must stay literal-colored.
        for (IdeSyntaxTokens.Token token : tokens)
            java.util.Arrays.fill(colors, token.start, token.end,
                token.literal ? EditorColorScheme.LITERAL : EditorColorScheme.KEYWORD);
        MappedSpans.Builder spans = new MappedSpans.Builder();
        int line = 0, column = 0;
        spans.addIfNeeded(0, 0, colors.length == 0 ? EditorColorScheme.TEXT_NORMAL : colors[0]);
        for (int i = 0; i < snapshot.length(); i++) {
            if (Thread.currentThread().isInterrupted()) return null;
            if (snapshot.charAt(i) == '\r' || snapshot.charAt(i) == '\n') {
                if (snapshot.charAt(i) == '\r' && i + 1 < snapshot.length()
                        && snapshot.charAt(i + 1) == '\n') i++;
                line++; column = 0;
            }
            else { spans.addIfNeeded(line, column, colors[i]); column++; }
    }
    spans.determine(line);
    return new Styles(spans.build());
    }

}
