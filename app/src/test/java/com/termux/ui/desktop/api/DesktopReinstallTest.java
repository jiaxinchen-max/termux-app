package com.termux.ui.desktop.api;

import android.content.Context;
import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DesktopReinstallTest {
    private Object oldFactory;
    private DesktopTasks tasks;
    private GameStoragePaths paths;
    private FileDesktopDistroRepository repo;
    private final List<String> events = new ArrayList<>();
    private final Set<String> pending = new HashSet<>();
    private final List<RuntimeSetupRequest> submitted = new ArrayList<>();
    private boolean failStop;

    @Before public void setup() throws Exception {
        Context c=RuntimeEnvironment.getApplication(); paths=new GameStoragePaths(c.getFilesDir());
        tasks=new DesktopTasks(c); repo=new FileDesktopDistroRepository(paths.getDesktopDistrosDirectory());
        Field f=LocalGames.class.getDeclaredField("hostFactory");f.setAccessible(true);oldFactory=f.get(null);
        LocalGames.install(ignored->new LocalGamesHost(){
            public boolean isRuntimeAvailable(){return true;}
            public boolean isRuntimeSetupPending(String id){return pending.contains(id);}
            public void forceStopRuntimeProcesses(List<String> scopes) throws LaunchHostException {
                events.add("stop");
                assertTrue(submitted.isEmpty());
                if(failStop)throw new LaunchHostException("runtime_force_stop_failed",true,null);
                assertTrue(scopes.stream().anyMatch(p->p.endsWith(".spec")));
            }
            public void startTermuxPackageInstall(RuntimeSetupRequest r){events.add("install");submitted.add(r);pending.add(r.getTaskId());}
        });
    }
    @After public void cleanup() throws Exception {
        Field f=LocalGames.class.getDeclaredField("hostFactory");f.setAccessible(true);f.set(null,oldFactory);
    }
    private String reinstall(DesktopDistro d) throws Exception {
        try{return (String)DesktopTasks.class.getMethod("reinstall",DesktopDistro.class).invoke(tasks,d);}
        catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    private DesktopDistro desktop(String id,DesktopRunMode mode){
        return new DesktopDistro(id,"Desktop",mode,mode==DesktopRunMode.PROOT?"ubuntu-xfce4-7":"",DesktopDe.XFCE4,
            DesktopGlMode.SYSTEM,mode==DesktopRunMode.PROOT?DesktopGuestGlMode.DEFAULT:DesktopGuestGlMode.NONE,
            "1280x720",30,DesktopUsbStorageMode.OFF);
    }
    private static void write(File f,String s)throws Exception{f.getParentFile().mkdirs();Files.write(f.toPath(),s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8);}

    @Test public void stopsAllManagedSessionsBeforeSubmittingAndUsesLatestSettings()throws Exception{
        DesktopDistro stale=desktop("target",DesktopRunMode.PROOT);
        repo.save(stale.withName("Current name").withOutput("1920x1080",60));
        DesktopDistro other=desktop("other",DesktopRunMode.NATIVE);repo.save(other);
        write(paths.getDesktopStatusFile(stale.getId()),"stopping\n");
        write(paths.getDesktopStatusFile(other.getId()),"running\n");
        File user=new File(paths.getTermuxPrefixDirectory(),"var/lib/proot-distro/containers/ubuntu-xfce4-7/rootfs/home/termux/keep");write(user,"preserve");
        String id=reinstall(stale);
        assertEquals(Arrays.asList("stop","stop","install"),events);
        assertEquals(id,submitted.get(0).getTaskId());
        String spec=read(new File(submitted.get(0).getSpecPath()));
        assertTrue(spec.contains("DESKTOP_REINSTALL=1\n"));assertTrue(spec.contains("1920"));
        assertEquals("Current name",repo.find(stale.getId()).get().getName());assertEquals("preserve",read(user));
        assertEquals(id,reinstall(stale));assertEquals(1,submitted.size());assertEquals(3,events.size());
    }
    @Test public void failedStopLeavesSpecAndInventoryAndNeverInstalls()throws Exception{
        DesktopDistro d=desktop("target",DesktopRunMode.NATIVE);repo.save(d);
        write(paths.getDesktopSpecFile(d.getId()),"original spec");failStop=true;
        assertThrows(LaunchHostException.class,()->reinstall(d));
        assertTrue(submitted.isEmpty());assertEquals("original spec",read(paths.getDesktopSpecFile(d.getId())));
        assertTrue(repo.find(d.getId()).isPresent());
    }
    @Test public void anotherInstallationIsProtectedBeforeAnyProcessIsKilled()throws Exception{
        DesktopDistro d=desktop("target",DesktopRunMode.NATIVE),other=desktop("other",DesktopRunMode.PROOT);repo.save(d);repo.save(other);
        tasks.install(other);events.clear();
        assertThrows(LaunchHostException.class,()->reinstall(d));assertTrue(events.isEmpty());assertEquals(1,submitted.size());
    }
    @Test public void archiveConflictNeverKillsOrSubmits()throws Exception{
        DesktopDistro d=desktop("target",DesktopRunMode.NATIVE);repo.save(d);
        synchronized(DesktopOperationGate.LOCK){DesktopOperationGate.reserveArchive();}
        try{assertThrows(LaunchHostException.class,()->reinstall(d));assertTrue(events.isEmpty());}
        finally{synchronized(DesktopOperationGate.LOCK){DesktopOperationGate.releaseArchive();}}
    }
    @Test public void removedRecordCannotBeResurrectedByStaleMenu()throws Exception{
        assertThrows(LaunchHostException.class,()->reinstall(desktop("missing",DesktopRunMode.NATIVE)));assertTrue(events.isEmpty());
    }
    @Test public void normalInstallAndPrepareDoNotForceReinstall()throws Exception{
        DesktopDistro d=desktop("normal",DesktopRunMode.NATIVE);repo.save(d);tasks.install(d);
        assertFalse(read(paths.getDesktopSpecFile(d.getId())).contains("DESKTOP_REINSTALL=1"));
        DesktopDistro p=desktop("prepare",DesktopRunMode.PROOT);repo.save(p);tasks.prepare(p);
        assertFalse(read(paths.getDesktopSpecFile(p.getId())).contains("DESKTOP_REINSTALL=1"));
    }
}
