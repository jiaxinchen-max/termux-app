package com.termux.ui.desktop.activity;

import android.content.*;
import android.os.Looper;
import android.view.*;
import androidx.appcompat.app.AlertDialog;
import com.termux.ui.R;
import com.termux.ui.api.*;
import com.termux.ui.activity.PrefixSetupConsoleDialog;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DesktopReinstallMenuTest {
    private Object oldFactory;
    private final List<String> events=Collections.synchronizedList(new ArrayList<>());
    private final Set<String> pending=Collections.synchronizedSet(new HashSet<>());
    @Before public void setup()throws Exception{
        Field f=LocalGames.class.getDeclaredField("hostFactory");f.setAccessible(true);oldFactory=f.get(null);
        LocalGames.install(ignored->new LocalGamesHost(){
            public boolean isRuntimeAvailable(){return true;}
            public boolean isRuntimeSetupPending(String id){return pending.contains(id);}
            public void forceStopRuntimeProcesses(List<String> scopes){events.add("stop");}
            public void startTermuxPackageInstall(RuntimeSetupRequest r){events.add("install");pending.add(r.getTaskId());}
        });
    }
    @After public void cleanup()throws Exception{
        if(ShadowDialog.getLatestDialog()!=null)ShadowDialog.getLatestDialog().dismiss();
        Field f=LocalGames.class.getDeclaredField("hostFactory");f.setAccessible(true);f.set(null,oldFactory);
    }
    private Menu show(DesktopActivity a,DesktopDistro d,DesktopSessionState state)throws Exception{
        View anchor=new View(a);a.addContentView(anchor,new ViewGroup.LayoutParams(40,40));
        Method m=DesktopActivity.class.getDeclaredMethod("showMore",View.class,DesktopDistro.class,DesktopSessionState.class,boolean.class);
        m.setAccessible(true);m.invoke(a,anchor,d,state,state.isActive());return ShadowPopupMenu.getLatestPopupMenu().getMenu();
    }
    private DesktopDistro desktop(DesktopRunMode mode,DesktopDe de){
        DesktopGuestGlMode guest=mode==DesktopRunMode.NATIVE?DesktopGuestGlMode.NONE:
            de==DesktopDe.UBUNTU_GNOME||de==DesktopDe.KWIN_PLASMA?DesktopGuestGlMode.LLVMPIPE:DesktopGuestGlMode.DEFAULT;
        return new DesktopDistro("test-"+mode+"-"+de,"Desktop",mode,mode==DesktopRunMode.PROOT?"ubuntu-7":"",de,
            DesktopGlMode.SYSTEM,guest,"1280x720",60,DesktopUsbStorageMode.OFF);
    }
    private void await(BooleanSupplier done)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!done.getAsBoolean()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);}
        assertTrue("Timed out",done.getAsBoolean());
    }
    @Test public void bothModesAndAllSupportedDesktopsKeepReinstallEnabledDuringSessions()throws Exception{
        try(ActivityController<DesktopActivity> c=Robolectric.buildActivity(DesktopActivity.class).create().start().resume()){
            for(DesktopRunMode mode:DesktopRunMode.values())for(DesktopDe de:DesktopDe.values())
                for(DesktopSessionState state:DesktopSessionState.values()){
                    Menu menu=show(c.get(),desktop(mode,de),state);
                    assertNull("Old ambiguous Install",menu.findItem(1));
                    MenuItem reinstall=menu.findItem(9);
                    if(!de.isCreatable())assertNull(reinstall);
                    else{assertNotNull(mode+" "+de+" "+state,reinstall);
                        assertEquals(c.get().getString(R.string.local_games_component_reinstall),reinstall.getTitle());
                        if(state!=DesktopSessionState.INSTALLING)assertTrue(state.toString(),reinstall.isEnabled());}
                    ShadowPopupMenu.getLatestPopupMenu().dismiss();
                }
        }
    }
    @Test public void cancellationDoesNothingAndConfirmationStopsBeforeRealInstallConsole()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        DesktopDistro d=desktop(DesktopRunMode.PROOT,DesktopDe.XFCE4);
        new FileDesktopDistroRepository(new GameStoragePaths(context.getFilesDir()).getDesktopDistrosDirectory()).save(d);
        try(ActivityController<DesktopActivity> c=Robolectric.buildActivity(DesktopActivity.class).create()){
            DesktopActivity a=c.get();Menu menu=show(a,d,DesktopSessionState.RUNNING);
            assertNotNull(menu.findItem(9));menu.performIdentifierAction(9,0);
            AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            assertTrue(events.isEmpty());dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();assertTrue(events.isEmpty());
            show(a,d,DesktopSessionState.STOPPING).performIdentifierAction(9,0);
            ((AlertDialog)ShadowDialog.getLatestDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            await(()->events.contains("install"));assertEquals("stop",events.get(0));assertEquals("install",events.get(events.size()-1));
            await(()->ShadowDialog.getLatestDialog() instanceof PrefixSetupConsoleDialog);
        }
    }
}
