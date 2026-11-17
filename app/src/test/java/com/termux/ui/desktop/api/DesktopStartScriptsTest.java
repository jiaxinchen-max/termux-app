package com.termux.ui.desktop.api;

import android.content.Context;
import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.domain.*;
import com.termux.ui.desktop.runtime.DesktopInstallState;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DesktopStartScriptsTest {
    private Object oldFactory;
    private DesktopTasks tasks;
    private GameStoragePaths paths;
    private Context context;
    private final List<LaunchRequest> launches = new ArrayList<>();
    private final Set<String> pending = new HashSet<>();
    private boolean processAlive;
    private final DesktopDistro distro = new DesktopDistro("native-hyprland", "Hyprland", DesktopRunMode.NATIVE,
        "", DesktopDe.HYPRLAND, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 30, DesktopUsbStorageMode.OFF);

    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication(); paths=new GameStoragePaths(context.getFilesDir());
        tasks=new DesktopTasks(context);
        Field f=LocalGames.class.getDeclaredField("hostFactory"); f.setAccessible(true); oldFactory=f.get(null);
        LocalGames.install(ignored -> new LocalGamesHost() {
            public boolean isRuntimeAvailable(){ return true; }
            public boolean isProcessAlive(long pid){ return processAlive; }
            public boolean isRuntimeSetupPending(String id){ return pending.contains(id); }
            public void startLaunch(LaunchRequest request) throws LaunchHostException {
                try {
                    assertEquals(asset("desktop_hyprland.py"), read(new File(paths.getRuntimeDirectory(),"desktop_hyprland.py")));
                    assertEquals(asset("desktop_hyprland_theme.py"), read(new File(paths.getRuntimeDirectory(),"desktop_hyprland_theme.py")));
                } catch(IOException e) { throw new AssertionError(e); }
                launches.add(request);
            }
            public void prepareLaunchRuntime(){ fail("start must not prepare runtime"); }
            public void startTermuxPackageInstall(RuntimeSetupRequest request){ fail("start must not install packages"); }
        });
        write(paths.getDesktopSpecFile(distro.getId()),"saved spec\n");
        write(new File(paths.getRuntimeDirectory(),"desktop_hyprland.py"),"old session");
        write(new File(paths.getRuntimeDirectory(),"desktop_hyprland_theme.py"),"old theme");
    }
    @After public void cleanup() throws Exception {
        Field f=LocalGames.class.getDeclaredField("hostFactory");f.setAccessible(true);f.set(null,oldFactory);
    }
    private String asset(String name)throws IOException {
        try(InputStream in=context.getAssets().open("local-games/"+name)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static void write(File f,String s)throws IOException{f.getParentFile().mkdirs();Files.write(f.toPath(),s.getBytes(StandardCharsets.UTF_8));}
    private static String read(File f)throws IOException{return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
    private void unchanged()throws IOException{
        assertTrue(launches.isEmpty());assertEquals("old session",read(new File(paths.getRuntimeDirectory(),"desktop_hyprland.py")));
        assertEquals("saved spec\n",read(paths.getDesktopSpecFile(distro.getId())));
    }
    @Test public void refreshesOldScriptsBeforeLaunchAndPreservesUserConfiguration()throws Exception{
        File config=new File(new File(context.getFilesDir(),"home"),".termux/hyprland-native-hyprland/config/custom.conf");
        write(config,"user setting");tasks.start(distro);
        assertEquals(1,launches.size());assertEquals("saved spec\n",read(paths.getDesktopSpecFile(distro.getId())));
        assertEquals("user setting",read(config));
    }
    @Test public void refreshFailureDoesNotSubmitOrMarkStarting()throws Exception{
        File blocked=new File(paths.getRuntimeDirectory(),"desktop_common.sh.tmp");blocked.mkdirs();write(new File(blocked,"keep"),"block");
        LaunchHostException error=assertThrows(LaunchHostException.class,()->tasks.start(distro));
        assertEquals("desktop_prepare_failed",error.getErrorCode());unchanged();
        assertFalse(paths.getDesktopStatusFile(distro.getId()).exists());
    }
    @Test public void installationConflictPrecedesScriptRefresh()throws Exception{
        File marker=new File(paths.getDesktopStatusFile(distro.getId()).getPath()+".install-task");
        DesktopInstallState.writeTask(marker,"desktop-install-pending");pending.add("desktop-install-pending");
        assertThrows(LaunchHostException.class,()->tasks.start(distro));unchanged();
    }
    @Test public void archiveConflictPrecedesScriptRefresh()throws Exception{
        synchronized(DesktopOperationGate.LOCK){DesktopOperationGate.reserveArchive();}
        try{assertThrows(LaunchHostException.class,()->tasks.start(distro));unchanged();}
        finally{synchronized(DesktopOperationGate.LOCK){DesktopOperationGate.releaseArchive();}}
    }
    @Test public void stoppingConflictPrecedesScriptRefresh()throws Exception{
        write(paths.getDesktopStatusFile(distro.getId()),"stopping\n");
        assertThrows(LaunchHostException.class,()->tasks.start(distro));unchanged();
    }

    @Test public void nativeOutputPublishesOnlyResolvedLaunchGeometryWithoutPreparingRuntime() throws Exception {
        DesktopDistro nativeOutput = distro.withOutput("native", 120);
        String resolution = tasks.sessionOutputResolution(nativeOutput, 2400, 1080);
        assertEquals("2400x1080", resolution);
        tasks.start(nativeOutput, resolution);
        assertEquals(1, launches.size());
        String spec = read(paths.getDesktopSpecFile(distro.getId()));
        assertTrue(spec.contains("DESKTOP_RESOLUTION=native\n"));
        assertTrue(spec.contains("DESKTOP_WIDTH=2400\nDESKTOP_HEIGHT=1080\nDESKTOP_REFRESH_RATE=120\n"));
        // Reentering an active compositor from a rotated window must retain its geometry.
        write(paths.getDesktopPidFile(distro.getId()), "1234\n"); processAlive = true;
        assertEquals("2400x1080", tasks.sessionOutputResolution(nativeOutput, 1080, 2400));
        tasks.start(nativeOutput, resolution); assertEquals(1, launches.size());
        processAlive = false; write(paths.getDesktopStatusFile(distro.getId()), "stopped\n");
        assertEquals("1080x2400", tasks.sessionOutputResolution(nativeOutput, 1080, 2400));
        tasks.start(nativeOutput, "1080x2400"); assertEquals(2, launches.size());
        assertTrue(read(paths.getDesktopSpecFile(distro.getId())).contains("DESKTOP_WIDTH=1080\nDESKTOP_HEIGHT=2400\n"));
    }

    @Test public void unresolvedOrInvalidNativeOutputCannotTouchScriptsOrSubmitLaunch() throws Exception {
        DesktopDistro nativeOutput = distro.withOutput("native", 60);
        assertEquals("desktop_output_invalid", assertThrows(LaunchHostException.class,
            () -> tasks.start(nativeOutput)).getErrorCode()); unchanged();
        assertThrows(LaunchHostException.class, () -> tasks.start(nativeOutput, "9000x1080")); unchanged();
    }
}
