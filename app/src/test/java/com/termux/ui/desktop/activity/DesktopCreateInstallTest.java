package com.termux.ui.desktop.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import com.termux.ui.R;
import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.*;
import com.termux.ui.activity.PrefixSetupConsoleDialog;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DesktopCreateInstallTest {
    private Object oldFactory;
    private volatile int installs;
    private volatile boolean failInstall;
    @Before public void setup() throws Exception {
        Field f=LocalGames.class.getDeclaredField("hostFactory");f.setAccessible(true);oldFactory=f.get(null);
        LocalGames.install(c->new LocalGamesHost() {
            public boolean isRuntimeAvailable(){return true;}
            public void startTermuxPackageInstall(RuntimeSetupRequest request) throws LaunchHostException {
                installs++;
                assertTrue(new File(request.getSpecPath()).isFile());
                if(failInstall)throw new LaunchHostException("test_install_failed",true,null);
            }
        });
    }
    @After public void restore() throws Exception {
        if(ShadowDialog.getLatestDialog()!=null)ShadowDialog.getLatestDialog().dismiss();
        Field f=LocalGames.class.getDeclaredField("hostFactory");f.setAccessible(true);f.set(null,oldFactory);
    }
    private static void await(BooleanSupplier done) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!done.getAsBoolean() && System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);}
        assertTrue("Timed out",done.getAsBoolean());
    }
    private static void created(DesktopActivity a,String id) throws Exception {
        Method m=DesktopActivity.class.getDeclaredMethod("installCreatedDesktop",String.class);m.setAccessible(true);m.invoke(a,id);
    }
    @Test public void bothModesSaveOnceAndImmediatelyInstallWithoutAnotherConfirmation() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        for(DesktopRunMode mode:DesktopRunMode.values()) {
            try(ActivityController<DesktopActivity> list=Robolectric.buildActivity(DesktopActivity.class).create().start().resume();
                ActivityController<DesktopCreateActivity> create=Robolectric.buildActivity(DesktopCreateActivity.class,DesktopCreateActivity.createIntent(c,mode)).create().start().resume()) {
                DesktopCreateActivity a=create.get();
                Method p=DesktopCreateActivity.class.getDeclaredMethod("populate");p.setAccessible(true);p.invoke(a);
                a.findViewById(R.id.desktop_create_action).performClick();a.findViewById(R.id.desktop_create_action).performClick();
                await(a::isFinishing);
                assertEquals(Activity.RESULT_OK,Shadows.shadowOf(a).getResultCode());
                Intent data=Shadows.shadowOf(a).getResultIntent();String id=data.getStringExtra(DesktopCreateActivity.EXTRA_CREATED_ID);
                FileDesktopDistroRepository repo=new FileDesktopDistroRepository(new GameStoragePaths(c.getFilesDir()).getDesktopDistrosDirectory());
                assertEquals(1,repo.list().stream().filter(d->d.getId().equals(id)).count());
                int before=installs;created(list.get(),id);created(list.get(),id);
                await(()->installs==before+1);await(()->ShadowDialog.getLatestDialog() instanceof PrefixSetupConsoleDialog);
                Bundle state=new Bundle();list.saveInstanceState(state);list.pause().stop().destroy();
                try(ActivityController<DesktopActivity> restored=Robolectric.buildActivity(DesktopActivity.class).create(state)){
                    created(restored.get(),id);Thread.sleep(100);assertEquals(before+1,installs);
                }
                ShadowDialog.getLatestDialog().dismiss();
            }
        }
    }
    @Test public void invalidOrCancelledResultDoesNotInstall() throws Exception {
        try(ActivityController<DesktopActivity> list=Robolectric.buildActivity(DesktopActivity.class).create()) {
            created(list.get(),null);created(list.get(),"../invalid");Thread.sleep(80);assertEquals(0,installs);
        }
    }
    @Test public void failedInstallPreservesRecordAndManualRetryStillWorks() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        DesktopDistro d=new DesktopDistro("desktop-failed-install","Test",DesktopRunMode.NATIVE,"",DesktopDe.XFCE4,DesktopGlMode.SYSTEM,DesktopGuestGlMode.NONE,"1280x720",30,DesktopUsbStorageMode.OFF);
        FileDesktopDistroRepository repo=new FileDesktopDistroRepository(new GameStoragePaths(c.getFilesDir()).getDesktopDistrosDirectory());repo.save(d);failInstall=true;
        try(ActivityController<DesktopActivity> list=Robolectric.buildActivity(DesktopActivity.class).create()) {
            created(list.get(),d.getId());await(()->installs==1);assertTrue(repo.find(d.getId()).isPresent());
            failInstall=false;
            Method m=DesktopActivity.class.getDeclaredMethod("installAndShowConsole",DesktopDistro.class);m.setAccessible(true);m.invoke(list.get(),d);
            assertEquals(2,installs);assertTrue(repo.find(d.getId()).isPresent());
        }
    }
    @Test public void failedSaveDoesNotReturnSuccessAndAllowsRetry() throws Exception {
        Context c=RuntimeEnvironment.getApplication();GameStoragePaths paths=new GameStoragePaths(c.getFilesDir());
        File dir=paths.getDesktopDistrosDirectory();dir.getParentFile().mkdirs();assertTrue(dir.createNewFile());
        try(ActivityController<DesktopCreateActivity> ctl=Robolectric.buildActivity(DesktopCreateActivity.class,DesktopCreateActivity.createIntent(c,DesktopRunMode.PROOT)).create().start().resume()) {
            DesktopCreateActivity a=ctl.get();Method p=DesktopCreateActivity.class.getDeclaredMethod("populate");p.setAccessible(true);p.invoke(a);
            a.findViewById(R.id.desktop_create_action).performClick();await(()->a.findViewById(R.id.desktop_create_action).isEnabled());
            assertFalse(a.isFinishing());assertNotEquals(Activity.RESULT_OK,Shadows.shadowOf(a).getResultCode());assertEquals(0,installs);
            assertTrue(dir.delete());
            a.findViewById(R.id.desktop_create_action).performClick();await(a::isFinishing);assertEquals(Activity.RESULT_OK,Shadows.shadowOf(a).getResultCode());
        }
    }
}
