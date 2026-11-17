package com.termux.ui.desktop.activity;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.*;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.termux.ui.R;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.api.DesktopArchiveTasks;
import com.termux.ui.desktop.domain.*;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
public class DesktopLogMenuTest {
    private DesktopActivity activity;
    private final DesktopArchiveFlow flow=DesktopArchiveFlow.instance();
    @After public void cleanup()throws Exception{
        if(ShadowDialog.getLatestDialog()!=null)ShadowDialog.getLatestDialog().dismiss();
        if(activity!=null)flow.detach(activity);
        set(flow,"snapshot",null);set(flow,"preparing",false);
    }
    private static void set(Object target,String name,Object value)throws Exception{
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private void archive(DesktopArchiveTasks.Status status,String id,String log)throws Exception{
        Constructor<DesktopArchiveTasks.Snapshot> c=DesktopArchiveTasks.Snapshot.class.getDeclaredConstructor(
            DesktopArchiveTasks.Status.class,String.class,String.class,String.class,String.class);
        c.setAccessible(true);set(flow,"snapshot",c.newInstance(status,"archive-one","",log,id));
        set(flow,"preparing",false);set(flow,"host",activity);
    }
    private DesktopDistro desktop(DesktopRunMode mode,DesktopDe de){
        DesktopGuestGlMode guest=mode==DesktopRunMode.NATIVE?DesktopGuestGlMode.NONE:
            de==DesktopDe.UBUNTU_GNOME||de==DesktopDe.KWIN_PLASMA?DesktopGuestGlMode.LLVMPIPE:DesktopGuestGlMode.DEFAULT;
        return new DesktopDistro("test-"+mode+"-"+de,"Desktop",mode,mode==DesktopRunMode.PROOT?"ubuntu-7":"",de,
            DesktopGlMode.SYSTEM,guest,"1280x720",60,DesktopUsbStorageMode.OFF);
    }
    private Menu show(DesktopDistro d,DesktopSessionState state)throws Exception{
        View anchor=new View(activity);activity.addContentView(anchor,new ViewGroup.LayoutParams(40,40));
        Method m=DesktopActivity.class.getDeclaredMethod("showMore",View.class,DesktopDistro.class,DesktopSessionState.class,boolean.class);
        m.setAccessible(true);m.invoke(activity,anchor,d,state,state.isActive());return ShadowPopupMenu.getLatestPopupMenu().getMenu();
    }
    private List<String> titles(Menu menu){
        List<String> result=new ArrayList<>();for(int i=0;i<menu.size();i++)result.add(menu.getItem(i).getTitle().toString());
        ShadowPopupMenu.getLatestPopupMenu().dismiss();return result;
    }
    private void await(BooleanSupplier done)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!done.getAsBoolean()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);}
        assertTrue("Timed out",done.getAsBoolean());
    }
    private static String text(View view){
        StringBuilder s=new StringBuilder();if(view instanceof TextView)s.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)s.append(text(g.getChildAt(i)));}
        return s.toString();
    }
    private AlertDialog logs(DesktopDistro d)throws Exception{
        show(d,DesktopSessionState.INSTALLED).performIdentifierAction(3,0);
        await(()->ShadowDialog.getLatestDialog()!=null&&ShadowDialog.getLatestDialog().isShowing());
        return (AlertDialog)ShadowDialog.getLatestDialog();
    }
    private void runtimeLog(DesktopDistro d)throws Exception{
        File log=new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir()).getDesktopLogFile(d.getId());
        log.getParentFile().mkdirs();Files.write(log.toPath(),"runtime-log-sentinel".getBytes(StandardCharsets.UTF_8));
    }

    @Test public void archiveHistoryNeverAddsAnotherLogItemToAnyDesktopMenu()throws Exception{
        try(ActivityController<DesktopActivity> c=Robolectric.buildActivity(DesktopActivity.class).create()){
            activity=c.get();
            Map<String,List<String>> expected=new HashMap<>();
            for(DesktopRunMode mode:DesktopRunMode.values())for(DesktopDe de:DesktopDe.values())if(de.isCreatable()){
                DesktopDistro d=desktop(mode,de);
                for(DesktopSessionState session:new DesktopSessionState[]{DesktopSessionState.NOT_INSTALLED,DesktopSessionState.INSTALLED,DesktopSessionState.FAILED,DesktopSessionState.RUNNING}){
                    archive(DesktopArchiveTasks.Status.IDLE,"","");List<String> baseline=titles(show(d,session));
                    assertEquals(1,Collections.frequency(baseline,activity.getString(R.string.desktop_action_logs)));
                    String group=mode+" "+session;
                    if(!expected.containsKey(group))expected.put(group,baseline);
                    assertEquals("All supported desktops share the same menu: "+group,expected.get(group),baseline);
                    for(DesktopArchiveTasks.Status status:DesktopArchiveTasks.Status.values()){
                        archive(status,d.getId(),"archive log");
                        assertEquals(mode+" "+de+" "+session+" "+status,baseline,titles(show(d,session)));
                    }
                }
            }
        }
    }
    @Test public void unifiedLogsKeepRuntimeLogAndOpenLiveArchiveProgress()throws Exception{
        try(ActivityController<DesktopActivity> c=Robolectric.buildActivity(DesktopActivity.class).create()){
            activity=c.get();DesktopDistro d=desktop(DesktopRunMode.PROOT,DesktopDe.KWIN_PLASMA);runtimeLog(d);
            archive(DesktopArchiveTasks.Status.RUNNING,d.getId(),"archive-first");
            AlertDialog logs=logs(d);assertTrue(text(logs.getWindow().getDecorView()).contains("runtime-log-sentinel"));
            assertEquals(View.VISIBLE,logs.getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());
            logs.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
            await(()->ShadowDialog.getLatestDialog()!=logs&&ShadowDialog.getLatestDialog().isShowing());
            AlertDialog progress=(AlertDialog)ShadowDialog.getLatestDialog();assertNotSame(logs,progress);
            assertTrue(text(progress.getWindow().getDecorView()).contains("archive-first"));
            archive(DesktopArchiveTasks.Status.SUCCEEDED,d.getId(),"archive-finished");
            Method update=DesktopArchiveFlow.class.getDeclaredMethod("updateProgress");update.setAccessible(true);update.invoke(flow);
            assertTrue(text(progress.getWindow().getDecorView()).contains("archive-finished"));
        }
    }
    @Test public void absentAndUnrelatedArchiveDoNotHijackDesktopLogs()throws Exception{
        try(ActivityController<DesktopActivity> c=Robolectric.buildActivity(DesktopActivity.class).create()){
            activity=c.get();DesktopDistro d=desktop(DesktopRunMode.NATIVE,DesktopDe.XFCE4);runtimeLog(d);
            for(DesktopArchiveTasks.Status status:DesktopArchiveTasks.Status.values()){
                archive(status,"another-desktop","wrong-log-sentinel");AlertDialog logs=logs(d);
                assertTrue(text(logs.getWindow().getDecorView()).contains("runtime-log-sentinel"));
                assertFalse(text(logs.getWindow().getDecorView()).contains("wrong-log-sentinel"));
                assertNotEquals(View.VISIBLE,logs.getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());logs.dismiss();
            }
        }
    }
    @Test public void importCardRetainsItsOwnProgressRecovery()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("local_games_settings",0).edit().putBoolean("desktop_proot",true).commit();
        try(ActivityController<DesktopActivity> c=Robolectric.buildActivity(DesktopActivity.class).create()){
            activity=c.get();archive(DesktopArchiveTasks.Status.FAILED,"","import failure");
            Method render=DesktopActivity.class.getDeclaredMethod("render",List.class,List.class);render.setAccessible(true);
            render.invoke(activity,Collections.emptyList(),Collections.emptyList());
            View more=activity.findViewById(R.id.desktop_import_more);assertEquals(View.VISIBLE,more.getVisibility());more.performClick();
            Menu menu=ShadowPopupMenu.getLatestPopupMenu().getMenu();assertEquals(1,menu.size());
            assertEquals(activity.getString(R.string.desktop_archive_view_progress),menu.getItem(0).getTitle());
            ShadowPopupMenu.getLatestPopupMenu().dismiss();
        }
    }
}
