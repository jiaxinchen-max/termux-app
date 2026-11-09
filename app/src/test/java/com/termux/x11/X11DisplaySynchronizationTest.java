package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

/** Exercises real view geometry and connector branches; only JNI is substituted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = X11DisplaySynchronizationTest.NativeView.class)
public class X11DisplaySynchronizationTest {
    private Activity activity;
    private Prefs prefs;
    private LorieView view;
    private boolean active;

    @Before public void setup() {
        NativeView.resetNativeState();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        prefs = new Prefs(activity, activity.getSharedPreferences("display-test", Context.MODE_PRIVATE),
            activity.getSharedPreferences("display-test-secondary", Context.MODE_PRIVATE));
        prefs.get().edit().clear().putString("displayResolutionMode", "custom")
            .putString("displayResolutionCustom", "1280x720").putBoolean("displayStretch", false)
            .putBoolean("adjustResolution", false).commit();
        active = true;
        view = new LorieView(activity);
        view.setLorieHost((LorieViewRuntimeApi.LorieHost) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{LorieViewRuntimeApi.LorieHost.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getActivity": return activity;
                    case "getX11Prefs": return prefs;
                    case "getLorieView": return view;
                    case "isActiveX11Display": return active;
                    default: return method.getReturnType() == boolean.class ? false : null;
                }
            }));
        view.setCallback((sw, sh, width, height) -> LorieView.sendWindowChange(width, height, 60, "Test"));
    }

    private void size(int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private X11ServerConnector connector() {
        return new X11ServerConnector(new Handler(Looper.getMainLooper()), new X11ServerConnector.Host() {
            public Intent getIntent() { return activity.getIntent(); }
            public void setIntent(Intent intent) { activity.setIntent(intent); }
            public void runOnUiThread(Runnable action) { action.run(); }
            public LorieView getLorieView() { return view; }
            public Prefs getX11Prefs() { return prefs; }
            public void onX11ServerConnectionChanged() { }
        });
    }

    @Test public void existingConnectionResendsSizeLostBeforeConnectWithoutAnotherLayout() {
        size(2208, 1840);
        assertEquals(0, NativeView.sends);
        NativeView.connected = true;
        assertFalse(connector().tryConnect());
        assertEquals(1, NativeView.sends);
        assertArrayEquals(new int[]{1280, 720}, NativeView.screen);
        assertArrayEquals(new int[]{0, 299, 2208, 1242, 1280, 720}, NativeView.viewport);
    }

    @Test public void freshConnectionAlsoResendsSizeLostBeforeConnect() throws Exception {
        size(2208, 1840);
        X11ServerConnector connector = connector();
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        java.io.FileDescriptor receivedDescriptor = pipe[0].getFileDescriptor();
        ReflectionHelpers.setField(connector, "mService", new ICmdEntryInterface.Stub() {
            public ParcelFileDescriptor getXConnection() { return pipe[0]; }
            public ParcelFileDescriptor getLogcatOutput() { return null; }
        });
        connector.tryConnect();
        assertTrue(NativeView.connected);
        assertTrue(NativeView.sends > 0);
        assertArrayEquals(new int[]{1280, 720}, NativeView.screen);
        pipe[1].close();
        new java.io.FileInputStream(receivedDescriptor).close();
    }

    @Test public void backgroundViewCannotOverwriteViewportOrSendOldResolution() {
        size(2208, 1840);
        int[] foreground = NativeView.viewport;
        active = false;
        prefs.displayResolutionCustom.put("800x600");
        NativeView.connected = true;
        connector().tryConnect();
        size(900, 700);
        view.refreshViewport();
        view.releaseDisplaySurface();
        assertSame(foreground, NativeView.viewport);
        assertEquals(0, NativeView.sends);
        assertEquals(0, NativeView.surfaceChanges);
        active = true;
        view.refreshDisplaySurface();
        assertArrayEquals(new int[]{800, 600}, NativeView.screen);
    }

    @Test public void surfaceRecreationAndRotationKeepAspectFitAndInputCoordinates() {
        NativeView.connected = true;
        size(2208, 1840);
        view.refreshDisplaySurface();
        assertEquals(0, view.screenInfo.offsetX);
        assertEquals(299, view.screenInfo.offsetY);
        assertEquals(640f, (1104 - view.screenInfo.offsetX) * view.screenInfo.scale.x, 0.01f);
        assertEquals(360f, (920 - view.screenInfo.offsetY) * view.screenInfo.scale.y, 0.01f);
        size(1840, 2208);
        assertArrayEquals(new int[]{0, 586, 1840, 1035, 1280, 720}, NativeView.viewport);
        assertEquals(640f, 920 * view.screenInfo.scale.x, 0.01f);
        assertEquals(360f, 517.5f * view.screenInfo.scale.y, 0.01f);
        view.releaseDisplaySurface();
        assertTrue(NativeView.surfaceChanges > 0);
    }

    @Test public void resumedHostReclaimsOwnershipFromLaterCreatedSession() {
        LorieViewRuntimeApi.Host host = (LorieViewRuntimeApi.Host) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{LorieViewRuntimeApi.Host.class},
            (proxy, method, args) -> method.getName().equals("getActivity") ? activity : null);
        LorieViewRuntimeController terminal = new LorieViewRuntimeController(host, "terminal-test");
        LorieViewRuntimeController game = new LorieViewRuntimeController(host, "game-test");
        try {
            assertFalse(terminal.isActiveX11Display());
            assertTrue(game.isActiveX11Display());
            terminal.onResume();
            assertTrue(terminal.isActiveX11Display());
            assertFalse(game.isActiveX11Display());
            java.lang.ref.WeakReference<?> keyTarget = ReflectionHelpers.getStaticField(
                com.termux.x11.utils.KeyInterceptor.class, "activity");
            assertSame(terminal, keyTarget.get());
            game.onResume();
            assertTrue(game.isActiveX11Display());
            assertFalse(terminal.isActiveX11Display());
        } finally {
            terminal.destroy();
            game.destroy();
        }
    }

    @Implements(value = LorieView.class)
    public static class NativeView extends org.robolectric.shadows.ShadowSurfaceView {
        static boolean connected;
        static int sends, surfaceChanges, connectedFd;
        static int[] screen, viewport;
        static void resetNativeState() { connected = false; sends = surfaceChanges = 0; screen = viewport = null; }
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected void nativeInit() { }
        @Implementation protected void setFiltering(int filtering) { }
        @Implementation protected static void requestStylusEnabled(boolean enabled) { }
        @Implementation protected void surfaceChanged(android.view.Surface surface) { surfaceChanges++; }
        @Implementation protected static boolean connected() { return connected; }
        @Implementation protected static boolean xConnected() { return connected; }
        @Implementation protected static void connect(int fd) { connectedFd = fd; connected = fd >= 0; }
        @Implementation protected static void sendWindowChange(int w, int h, int rate, String name) {
            if (connected) { sends++; screen = new int[]{w, h}; }
        }
        @Implementation protected static void setViewport(int x, int y, int w, int h, int ew, int eh) {
            viewport = new int[]{x, y, w, h, ew, eh};
        }
        @Implementation protected static void setClipboardSyncEnabled(boolean enabled, boolean enabled2) { }
    }
}
