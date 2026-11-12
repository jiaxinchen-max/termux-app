package com.termux.ui.desktop.activity;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.termux.ui.R;
import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.*;
import com.termux.x11.X11SessionView;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = DesktopSessionControlsTest.DisplayShadow.class)
public class DesktopSessionControlsTest {
    @Implements(X11SessionView.class)
    public static class DisplayShadow extends org.robolectric.shadows.ShadowViewGroup {
        @Implementation protected void initialize(Context c) { }
        @Implementation protected void bind(AppCompatActivity a, String resolution, int hz,
                                             X11SessionView.Listener listener) { }
        @Implementation protected void unbind() { }
    }
    private Object oldFactory;
    private volatile int stops;
    private volatile boolean failStop;
    private CountDownLatch release;

    @Before public void setup() throws Exception {
        Field f = LocalGames.class.getDeclaredField("hostFactory"); f.setAccessible(true); oldFactory = f.get(null);
        LocalGames.install(c -> new LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void startLaunch(LaunchRequest request) throws LaunchHostException {
                if (!request.getTaskId().startsWith("desktop-stop-")) return;
                stops++;
                if (release != null) try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (failStop) throw new LaunchHostException("test_stop_failed", true, null);
            }
        });
    }
    @After public void restore() throws Exception {
        if (release != null) release.countDown();
        Field f = LocalGames.class.getDeclaredField("hostFactory"); f.setAccessible(true); f.set(null, oldFactory);
    }
    private ActivityController<DesktopSessionActivity> session() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        DesktopDistro d = new DesktopDistro("desktop-controls", "Test", DesktopRunMode.NATIVE, "",
            DesktopDe.XFCE4, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 30, DesktopUsbStorageMode.OFF);
        new FileDesktopDistroRepository(new GameStoragePaths(c.getFilesDir()).getDesktopDistrosDirectory()).save(d);
        return Robolectric.buildActivity(DesktopSessionActivity.class,
            DesktopSessionActivity.createIntent(c, d.getId())).create().start().resume();
    }
    private static void call(Object o, String method) throws Exception {
        Method m=o.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(o);
    }
    private static AlertDialog dialog() { return (AlertDialog) ShadowDialog.getLatestDialog(); }
    private static void await(BooleanSupplier done) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!done.getAsBoolean() && System.nanoTime()<until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);
        }
        assertTrue("Timed out",done.getAsBoolean());
    }
    @Test public void allExitEntriesConfirmAndCancelKeepsSessionAndBackDrawerWorks() throws Exception {
        try (ActivityController<DesktopSessionActivity> ctl=session()) {
            DesktopSessionActivity a=ctl.get();
            assertEquals(0,a.getResources().getIdentifier("desktop_session_menu","id",a.getPackageName()));
            for (int entry : new int[]{R.id.desktop_session_close_waiting,R.id.desktop_session_exit,R.id.desktop_session_exit_compact}) {
                a.findViewById(entry).performClick();
                AlertDialog d=dialog();assertNotNull(d.getButton(DialogInterface.BUTTON_POSITIVE));
                assertNull(d.getListView());
                d.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse(a.isFinishing());assertEquals(0,stops);
            }
            call(a,"handleSessionBack");
            assertEquals(View.VISIBLE,a.findViewById(R.id.desktop_session_drawer_layer).getVisibility());
            Field f=DesktopSessionActivity.class.getDeclaredField("lastBackHandledAtMs");f.setAccessible(true);f.setLong(a,-1);
            call(a,"handleSessionBack");call(a,"finishHidingDrawer");
            assertEquals(View.GONE,a.findViewById(R.id.desktop_session_drawer_layer).getVisibility());
        }
    }
    @Test public void confirmedExitSubmitsOnceWaitsAndFailureCanBeRetried() throws Exception {
        release=new CountDownLatch(1);failStop=true;
        try (ActivityController<DesktopSessionActivity> ctl=session()) {
            DesktopSessionActivity a=ctl.get();
            a.findViewById(R.id.desktop_session_exit).performClick();AlertDialog d=dialog();
            call(a,"showExitChoice");assertSame(d,dialog());
            d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            call(a,"stopAndExit");
            await(()->stops==1);assertFalse(a.isFinishing());
            assertFalse(a.findViewById(R.id.desktop_session_close_waiting).isEnabled());
            release.countDown();
            await(()->a.findViewById(R.id.desktop_session_close_waiting).isEnabled());
            assertFalse(a.isFinishing());
            dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            failStop=false;
            a.findViewById(R.id.desktop_session_close_waiting).performClick();
            dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            await(a::isFinishing);assertEquals(2,stops);
        }
    }
}
