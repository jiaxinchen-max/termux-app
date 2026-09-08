package com.termux.x11;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Routes native callbacks to the most recently registered live Activity runtime. */
public final class LorieViewRuntimeRegistry {
    private static final LorieViewRuntimeRegistry INSTANCE = new LorieViewRuntimeRegistry();
    private static final List<WeakReference<LorieViewRuntimeController>> RUNTIMES = new ArrayList<>();

    private LorieViewRuntimeRegistry() { }

    static synchronized void register(LorieViewRuntimeController controller) {
        removeLocked(controller);
        pruneLocked();
        RUNTIMES.add(new WeakReference<>(controller));
    }

    @Nullable
    static synchronized LorieViewRuntimeController unregister(LorieViewRuntimeController controller) {
        removeLocked(controller);
        pruneLocked();
        return currentLocked();
    }

    public static synchronized boolean hasLiveInstance() { return currentLocked() != null; }

    @Keep
    @Nullable
    public static synchronized LorieViewRuntimeRegistry getInstance() {
        return currentLocked() == null ? null : INSTANCE;
    }

    @Keep
    void clientConnectedStateChanged() {
        LorieViewRuntimeController controller = current();
        if (controller != null) controller.clientConnectedStateChanged();
    }

    @Keep
    void onRenderConnectionChanged() {
        LorieViewRuntimeController controller = current();
        if (controller != null) controller.onRenderConnectionChanged();
    }

    @Keep
    void onFramePresented() {
        LorieViewRuntimeController controller = current();
        if (controller != null) controller.onRendererFramePresented();
    }

    @Nullable
    private static synchronized LorieViewRuntimeController current() { return currentLocked(); }

    @Nullable
    private static LorieViewRuntimeController currentLocked() {
        pruneLocked();
        return RUNTIMES.isEmpty() ? null : RUNTIMES.get(RUNTIMES.size() - 1).get();
    }

    private static void removeLocked(LorieViewRuntimeController controller) {
        for (int index = RUNTIMES.size() - 1; index >= 0; index--) {
            LorieViewRuntimeController candidate = RUNTIMES.get(index).get();
            if (candidate == null || candidate == controller) RUNTIMES.remove(index);
        }
    }

    private static void pruneLocked() {
        for (int index = RUNTIMES.size() - 1; index >= 0; index--) {
            if (RUNTIMES.get(index).get() == null) RUNTIMES.remove(index);
        }
    }
}
