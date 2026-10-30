package com.termux.ui.desktop.archive;

import com.termux.ui.domain.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.apache.commons.compress.archivers.tar.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

public class DesktopArchiveEngineTest {
    private Path temporary,source,containers;
    private NioFs fs;
    private DesktopArchiveEngine engine;
    private DesktopArchiveMetadata metadata;
    private static final String TEXT="content-payload-abc";
    @Before public void setup() throws Exception {
        temporary=Files.createTempDirectory("desktop-archive-core-");
        source=temporary.resolve("old-container");containers=temporary.resolve("containers");
        Files.createDirectories(source);Files.createDirectories(containers);
        write(".desktop-de-xfce4","");
        write("manifest.json","{\"architecture\":\"arm64\",\"image\":\"debian:trixie\"}");
        write("sysdata/loadavg","0.0 0.0 0.0 1/1 1\n");
        write("rootfs/usr/lib/os-release","ID=debian\nVERSION_CODENAME=trixie\n");
        Files.createDirectories(source.resolve("rootfs/etc"));
        Files.createSymbolicLink(source.resolve("rootfs/etc/os-release"),Paths.get("../usr/lib/os-release"));
        write("rootfs/usr/bin/app",TEXT);Files.setAttribute(source.resolve("rootfs/usr/bin/app"),"unix:mode",0751);
        Files.createLink(source.resolve("rootfs/usr/bin/app-hard"),source.resolve("rootfs/usr/bin/app"));
        Files.createSymbolicLink(source.resolve("rootfs/usr/bin/absolute"),Paths.get("/etc/os-release"));
        write("rootfs/root/用户文件", "你好\n");
        for(String p:Arrays.asList("proc","sys","dev","run","tmp"))write("rootfs/"+p+"/runtime-secret","must not be archived");
        write("shm/old-ipc","not persistent");
        fs=new NioFs();engine=new DesktopArchiveEngine(fs,limits());
        metadata=DesktopArchiveMetadata.create("com.termux","aarch64","trixie",
            new DesktopDistro("old-id","工作桌面",DesktopRunMode.PROOT,"old-container",DesktopDe.XFCE4,
                DesktopGlMode.SYSTEM,"1920x1080",60),"3","horizontal");
    }
    private DesktopArchiveLimits limits(){return new DesktopArchiveLimits(1024L*1024*1024,128L*1024*1024,30000,1024*1024);}
    private void write(String relative,String data)throws Exception {
        Path p=source.resolve(relative);Files.createDirectories(p.getParent());Files.write(p,data.getBytes("UTF-8"));
    }
    private File archive()throws Exception {
        File out=temporary.resolve("desktop.tar.gz").toFile();engine.exportArchive(source.toFile(),out,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,DesktopArchiveControl.NONE);return out;
    }
    private DesktopArchiveMetadata inspect(File file)throws Exception{return engine.readMetadata(file,"com.termux","aarch64");}
    private DesktopArchiveImportResult restore(File file,DesktopArchiveMetadata checked)throws Exception {
        return engine.importArchive(file,containers.toFile(),"new-id","new-container",checked,DesktopArchiveControl.NONE);
    }
    @After public void cleanup()throws Exception { remove(temporary); }
    private static void remove(Path p)throws Exception {
        if(p==null||!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){
            Files.setAttribute(p,"unix:mode",0700);
            try(DirectoryStream<Path> children=Files.newDirectoryStream(p)){for(Path child:children)remove(child);}
        }
        Files.delete(p);
    }
    private interface Checked {void run()throws Exception;}
    private static IOException rejected(Checked body)throws Exception {
        try{body.run();fail("archive should be rejected");return null;}catch(IOException expected){return expected;}
    }
    @Test public void roundTripPreservesContainerSettingsPermissionsAndLinksWithoutOwnership()throws Exception {
        File file=archive();assertFalse(new File(file+".partial").exists());
        DesktopArchiveMetadata checked=inspect(file);
        assertEquals("工作桌面",checked.getDesktop().getName());assertEquals("3",checked.getTouchMode());
        assertEquals("horizontal",checked.getOrientation());assertEquals(64,checked.getArchiveSha256().length());
        assertTrue(checked.getEntryCount()>10);assertTrue(checked.getExpandedBytes()>checked.getEntryCount()*4096L);
        DesktopArchiveImportResult imported=restore(file,checked);Path root=imported.getContainerDirectory().toPath();
        assertEquals("new-id",imported.getDesktop().getId());assertEquals("new-container",imported.getDesktop().getProotDistro());
        assertEquals(DesktopGlMode.SYSTEM,imported.getDesktop().getGlMode());assertEquals(60,imported.getDesktop().getRefreshRate());
        assertEquals(TEXT,new String(Files.readAllBytes(root.resolve("rootfs/usr/bin/app")),"UTF-8"));
        assertTrue(Files.isSameFile(root.resolve("rootfs/usr/bin/app"),root.resolve("rootfs/usr/bin/app-hard")));
        assertEquals(0751,((Number)Files.getAttribute(root.resolve("rootfs/usr/bin/app"),"unix:mode")).intValue()&07777);
        assertEquals(Paths.get("/etc/os-release"),Files.readSymbolicLink(root.resolve("rootfs/usr/bin/absolute")));
        assertEquals(Paths.get("../usr/lib/os-release"),Files.readSymbolicLink(root.resolve("rootfs/etc/os-release")));
        assertEquals("你好\n",new String(Files.readAllBytes(root.resolve("rootfs/root/用户文件")),"UTF-8"));
        assertTrue(Files.exists(root.resolve("sysdata/loadavg")));assertTrue(Files.exists(root.resolve("manifest.json")));
        for(String p:Arrays.asList("proc","sys","dev","run","tmp"))assertEquals(0,root.resolve("rootfs/"+p).toFile().list().length);
        assertEquals(0,root.resolve("shm").toFile().list().length);
        assertTrue(Files.exists(source.resolve("rootfs/tmp/runtime-secret")));
        engine.discardImportedContainer(imported);assertFalse(Files.exists(root));assertTrue(Files.exists(source));
    }
    @Test public void unreadableHostMountpointNodesArePreservedButTheirContentsAreNeverRead()throws Exception {
        List<String> paths=Arrays.asList("rootfs/mnt/sdcard","rootfs/data/app","rootfs/data/dalvik-cache",
            "rootfs/data/misc/apexdata/com.android.art/dalvik-cache","rootfs/data/data/com.termux/files/apps",
            "rootfs/data/data/com.termux/files/home","rootfs/data/data/com.termux/files/usr","rootfs/data/data/com.termux/cache",
            "rootfs/sdcard","rootfs/storage/emulated/0","rootfs/storage/self/primary","rootfs/apex","rootfs/odm",
            "rootfs/product","rootfs/system","rootfs/system_ext","rootfs/vendor");
        for(String p:paths){write(p+"/host-secret","excluded");Files.setAttribute(source.resolve(p),"unix:mode",0000);}
        File file=archive();DesktopArchiveImportResult imported=restore(file,inspect(file));
        for(String p:paths)assertEquals(p,0,((Number)Files.getAttribute(imported.getContainerDirectory().toPath().resolve(p),"unix:mode")).intValue()&07777);
    }
    @Test public void unknownArchitecturePackageAndModesAreRejected()throws Exception {
        File file=archive();rejected(()->engine.readMetadata(file,"com.other","aarch64"));rejected(()->engine.readMetadata(file,"com.termux","x86_64"));
        try{DesktopArchiveMetadata.create("com.termux","aarch64","trixie",metadata.getDesktop().withGlMode(DesktopGlMode.AUTO),null,null);fail();}
        catch(IllegalArgumentException expected){ }
        try{DesktopArchiveMetadata.create("com.termux","aarch64","forky",metadata.getDesktop(),null,null);fail();}
        catch(IllegalArgumentException expected){ }
    }
    @Test public void unreadableHostFileBindPlaceholdersAreNeverOpenedAndRestoreEmpty()throws Exception {
        List<String> paths=Arrays.asList("rootfs/linkerconfig/ld.config.txt","rootfs/linkerconfig/com.android.art/ld.config.txt",
            "rootfs/plat_property_contexts","rootfs/property_contexts");
        for(String p:paths){write(p,"host-only-data");Files.setAttribute(source.resolve(p),"unix:mode",0000);}
        NioFs noHostRead=new NioFs(){@Override public InputStream openRegular(File file,Stat expected)throws IOException {
            String relative=source.relativize(file.toPath()).toString();
            if(paths.contains(relative))throw new AssertionError("host file-bind placeholder opened");
            return super.openRegular(file,expected);
        }};
        DesktopArchiveEngine isolated=new DesktopArchiveEngine(noHostRead,limits());File file=temporary.resolve("placeholders.tar.gz").toFile();
        isolated.exportArchive(source.toFile(),file,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,DesktopArchiveControl.NONE);
        DesktopArchiveImportResult result=restore(file,inspect(file));
        for(String p:paths){Path out=result.getContainerDirectory().toPath().resolve(p);assertEquals(0,Files.size(out));assertEquals(0,((Number)Files.getAttribute(out,"unix:mode")).intValue()&07777);}
        TarArchiveEntry bad=new TarArchiveEntry("payload/container/rootfs/linkerconfig/ld.config.txt");bad.setSize(1);
        File invalid=evil(Arrays.asList(dir("payload/container"),dir("payload/container/rootfs"),dir("payload/container/rootfs/linkerconfig"),bad));
        assertEquals("archive_runtime_placeholder_invalid",((DesktopArchiveException)rejected(()->inspect(invalid))).getCode());
    }
    @Test public void missingInstalledMarkerAndWrongActualCodenameDoNotPublishExport()throws Exception {
        Files.delete(source.resolve(".desktop-de-xfce4"));rejected(this::archive);assertFalse(Files.exists(temporary.resolve("desktop.tar.gz.partial")));
        write(".desktop-de-xfce4","");write("rootfs/usr/lib/os-release","ID=debian\nVERSION_CODENAME=forky\n");rejected(this::archive);
        assertFalse(Files.exists(temporary.resolve("desktop.tar.gz")));
    }
    @Test public void existingArchiveAndImportIdentityAreProtectedWhileUnrelatedPartialIsIgnored()throws Exception {
        File file=archive();byte[] original=Files.readAllBytes(file.toPath());
        rejected(()->engine.exportArchive(source.toFile(),file,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,DesktopArchiveControl.NONE));assertArrayEquals(original,Files.readAllBytes(file.toPath()));
        Path partial=temporary.resolve("other.tar.gz.partial");Files.write(partial,new byte[]{42});
        File other=temporary.resolve("other.tar.gz").toFile();
        engine.exportArchive(source.toFile(),other,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,DesktopArchiveControl.NONE);
        assertTrue(other.isFile());assertArrayEquals(new byte[]{42},Files.readAllBytes(partial));
        Files.createDirectories(containers.resolve("new-container"));Files.write(containers.resolve("new-container/keep"),new byte[]{7});
        rejected(()->restore(file,inspect(file)));assertTrue(Files.exists(containers.resolve("new-container/keep")));
        rejected(()->engine.importArchive(file,containers.toFile(),"other-id","old-container",inspect(file),DesktopArchiveControl.NONE));
    }
    @Test public void targetsCreatedDuringExclusiveCreationAndImportPublicationRaceArePreserved()throws Exception {
        NioFs race=new NioFs(){
            @Override public ExclusiveOutput createExclusiveFile(File target)throws IOException {
                Files.write(target.toPath(),new byte[]{99});return super.createExclusiveFile(target);
            }
            @Override public void rename(File from,File to)throws IOException {
                Files.createDirectory(to.toPath());Files.write(to.toPath().resolve("keep"),new byte[]{88});
                super.rename(from,to);
            }
        };
        DesktopArchiveEngine racing=new DesktopArchiveEngine(race,limits());File target=temporary.resolve("raced.tar.gz").toFile();
        rejected(()->racing.exportArchive(source.toFile(),target,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,DesktopArchiveControl.NONE));
        assertArrayEquals(new byte[]{99},Files.readAllBytes(target.toPath()));assertFalse(new File(target+".partial").exists());
        File file=archive();DesktopArchiveMetadata checked=inspect(file);
        rejected(()->racing.importArchive(file,containers.toFile(),"new-id","new-container",checked,DesktopArchiveControl.NONE));
        assertArrayEquals(new byte[]{88},Files.readAllBytes(containers.resolve("new-container/keep")));
        assertFalse(Files.exists(containers.resolve(".desktop-import-new-container")));
    }
    @Test public void inspectedArchiveCannotBeReplacedByAnotherOtherwiseValidArchive()throws Exception {
        File file=archive();DesktopArchiveMetadata checked=inspect(file);Files.delete(file.toPath());
        write("rootfs/root/用户文件","changed");archive();
        assertEquals("archive_changed_after_inspection",((DesktopArchiveException)rejected(()->restore(file,checked))).getCode());
        assertFalse(Files.exists(containers.resolve("new-container")));assertFalse(Files.exists(containers.resolve(".desktop-import-new-container")));
        assertTrue(Files.exists(source));
    }
    @Test public void cancellationCleansOnlyOwnedOutputAndStaging()throws Exception {
        File out=temporary.resolve("cancel.tar.gz").toFile();
        rejected(()->engine.exportArchive(source.toFile(),out,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,(m,b,t)->{throw new IOException("cancel");}));
        assertFalse(out.exists());assertFalse(new File(out+".partial").exists());
        File file=archive();DesktopArchiveMetadata checked=inspect(file);
        rejected(()->engine.importArchive(file,containers.toFile(),"new-id","new-container",checked,(m,b,t)->{throw new IOException("cancel");}));
        assertFalse(Files.exists(containers.resolve(".desktop-import-new-container")));assertFalse(Files.exists(containers.resolve("new-container")));
        assertTrue(Files.exists(source));
    }
    @Test public void exclusiveModeIsRequiredAndCreationIdentityArrivesBeforeBytes()throws Exception {
        File out=temporary.resolve("explicit.tar.gz").toFile();
        assertEquals("archive_export_mode_invalid",((DesktopArchiveException)rejected(()->
            engine.exportArchive(source.toFile(),out,metadata,null,DesktopArchiveControl.NONE))).getCode());
        assertFalse(out.exists());
        AtomicInteger created=new AtomicInteger();
        engine.exportArchive(source.toFile(),out,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,new DesktopArchiveControl(){
            @Override public void createdOutput(File file,long device,long inode)throws IOException {
                StatIdentity.assertCreated(fs,file,device,inode);created.incrementAndGet();
            }
            @Override public void update(String message,long bytes,long total){assertEquals(1,created.get());}
        });
        assertEquals(1,created.get());inspect(out);
    }
    @Test public void creationAndFinalProgressCancellationRemoveOnlyOwnedOutput()throws Exception {
        for(boolean atCreation:new boolean[]{true,false}) {
            File out=temporary.resolve("cancel-"+atCreation+".tar.gz").toFile();
            rejected(()->engine.exportArchive(source.toFile(),out,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,new DesktopArchiveControl(){
                @Override public void createdOutput(File file,long device,long inode)throws IOException {
                    if(atCreation)throw new IOException("cancel-created");
                }
                @Override public void update(String message,long bytes,long total)throws IOException {
                    if(!atCreation&&total>=0)throw new IOException("cancel-final");
                }
            }));
            assertFalse(out.exists());
        }
    }
    @Test public void replacementAfterCreationIsPreservedWhenExportFails()throws Exception {
        File out=temporary.resolve("replacement.tar.gz").toFile();
        Path held=temporary.resolve("held-output");
        rejected(()->engine.exportArchive(source.toFile(),out,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,new DesktopArchiveControl(){
            @Override public void createdOutput(File file,long device,long inode)throws IOException {
                Files.move(file.toPath(),held);Files.write(file.toPath(),new byte[]{77});
                throw new IOException("replaced");
            }
            @Override public void update(String message,long bytes,long total){ }
        }));
        assertArrayEquals(new byte[]{77},Files.readAllBytes(out.toPath()));
        assertEquals(0,Files.size(held));
    }
    @Test public void syncAndCloseFailuresFailAndRemoveOwnedOutputWithoutRename()throws Exception {
        for(boolean failSync:new boolean[]{true,false}) {
            NioFs failing=new NioFs(){
                @Override public ExclusiveOutput createExclusiveFile(File target)throws IOException {
                    ExclusiveOutput delegate=super.createExclusiveFile(target);
                    return new ExclusiveOutput(){
                        @Override public Stat getIdentity(){return delegate.getIdentity();}
                        @Override public void write(int b)throws IOException{delegate.write(b);}
                        @Override public void write(byte[] b,int o,int n)throws IOException{delegate.write(b,o,n);}
                        @Override public void flush()throws IOException{delegate.flush();}
                        @Override public void sync()throws IOException {
                            if(failSync)throw new IOException("fsync-failed");delegate.sync();
                        }
                        @Override public void close()throws IOException {
                            delegate.close();if(!failSync)throw new IOException("close-failed");
                        }
                    };
                }
                @Override public void rename(File from,File to){throw new AssertionError("export must not rename");}
            };
            File out=temporary.resolve("failure-"+failSync+".tar.gz").toFile();
            DesktopArchiveEngine broken=new DesktopArchiveEngine(failing,limits());
            assertEquals(failSync?"fsync-failed":"close-failed",rejected(()->broken.exportArchive(
                source.toFile(),out,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,DesktopArchiveControl.NONE)).getMessage());
            assertFalse(out.exists());
        }
    }
    @Test public void existingSymlinkOutputIsRejectedWithoutFollowingOrDeletingIt()throws Exception {
        Path existing=temporary.resolve("existing");Files.write(existing,new byte[]{42});
        Path out=temporary.resolve("link.tar.gz");Files.createSymbolicLink(out,existing);
        rejected(()->engine.exportArchive(source.toFile(),out.toFile(),metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,DesktopArchiveControl.NONE));
        assertTrue(Files.isSymbolicLink(out));assertArrayEquals(new byte[]{42},Files.readAllBytes(existing));
    }
    private static final class StatIdentity {
        static void assertCreated(NioFs fs,File file,long device,long inode)throws IOException {
            DesktopArchiveFileSystem.Stat stat=fs.lstat(file);
            assertNotNull(stat);assertEquals(0,stat.size);assertEquals(device,stat.device);assertEquals(inode,stat.inode);
        }
    }
    @Test public void importCreationIdentityIsReportedBeforePayloadAndSurvivesPublication()throws Exception {
        File file=archive();DesktopArchiveMetadata checked=inspect(file);long[] identity=new long[2];
        AtomicInteger called=new AtomicInteger();
        DesktopArchiveImportResult imported=engine.importArchive(file,containers.toFile(),"new-id","new-container",checked,
            new DesktopArchiveControl(){
                @Override public void update(String message,long bytes,long total){assertEquals(1,called.get());}
                @Override public void createdImportStaging(File stage,long device,long inode)throws IOException {
                    assertEquals(0,stage.list().length);DesktopArchiveFileSystem.Stat stat=fs.lstat(stage);
                    assertEquals(device,stat.device);assertEquals(inode,stat.inode);
                    identity[0]=device;identity[1]=inode;called.incrementAndGet();
                }
            });
        DesktopArchiveFileSystem.Stat published=fs.lstat(imported.getContainerDirectory());
        assertEquals(identity[0],published.device);assertEquals(identity[1],published.inode);assertEquals(1,called.get());
    }
    @Test public void importCreationCallbackFailureCleansOnlyTheCreatedStagingIdentity()throws Exception {
        File file=archive();DesktopArchiveMetadata checked=inspect(file);
        rejected(()->engine.importArchive(file,containers.toFile(),"new-id","new-container",checked,
            new DesktopArchiveControl(){
                @Override public void update(String message,long bytes,long total){ }
                @Override public void createdImportStaging(File stage,long device,long inode)throws IOException {
                    throw new IOException("journal failed");
                }
            }));
        assertFalse(Files.exists(containers.resolve(".desktop-import-new-container")));
        assertFalse(Files.exists(containers.resolve("new-container")));
        IOException failure=rejected(()->engine.importArchive(file,containers.toFile(),"new-id","new-container",checked,
            new DesktopArchiveControl(){
                @Override public void update(String message,long bytes,long total){ }
                @Override public void createdImportStaging(File stage,long device,long inode)throws IOException {
                    Files.move(stage.toPath(),temporary.resolve("displaced-staging"));
                    Files.createDirectory(stage.toPath());Files.write(stage.toPath().resolve("keep"),new byte[]{7});
                    throw new IOException("journal failed after replacement");
                }
            }));
        assertArrayEquals(new byte[]{7},Files.readAllBytes(containers.resolve(".desktop-import-new-container/keep")));
        assertEquals(1,failure.getSuppressed().length);
        assertEquals("archive_cleanup_identity_mismatch",failure.getSuppressed()[0].getMessage());
    }
    @Test public void traversalAbsoluteNamesAndDuplicateDirectoriesAreRejected()throws Exception {
        for(String name:Arrays.asList("../escape","/absolute","payload/container/../escape","payload/container/a//b","payload/container/./b")){
            File file=evil(Collections.singletonList(new TarArchiveEntry(name)));
            rejected(()->inspect(file));
        }
        TarArchiveEntry root=dir("payload/container");File duplicate=evil(Arrays.asList(root,dir("payload/container")));rejected(()->inspect(duplicate));
    }
    @Test public void symbolicLinkParentCannotReceiveAnyFileOrDirectory()throws Exception {
        Path outside=temporary.resolve("outside");Files.createDirectories(outside);
        for(boolean childDirectory:new boolean[]{false,true}) {
            TarArchiveEntry link=new TarArchiveEntry("payload/container/rootfs/a",TarConstants.LF_SYMLINK);link.setLinkName(outside.toString());
            TarArchiveEntry child=childDirectory?dir("payload/container/rootfs/a/escape"):new TarArchiveEntry("payload/container/rootfs/a/escape");
            File file=evil(Arrays.asList(dir("payload/container"),dir("payload/container/rootfs"),link,child));
            assertEquals("archive_parent_or_order_invalid",((DesktopArchiveException)rejected(()->inspect(file))).getCode());
            assertFalse(Files.exists(outside.resolve("escape")));
        }
    }
    @Test public void hardlinksCannotReferenceUnmarkedFilesOtherPayloadsOrLinks()throws Exception {
        for(String target:Arrays.asList("payload/container/rootfs/etc/os-release","../../outside","payload/host/bin/x")){
            TarArchiveEntry link=new TarArchiveEntry("payload/container/a",TarConstants.LF_LINK);link.setLinkName(target);
            File file=evil(Arrays.asList(dir("payload/container"),link));
            assertEquals(target.startsWith("..")?"archive_invalid_path":"archive_invalid_hardlink",
                ((DesktopArchiveException)rejected(()->inspect(file))).getCode());
        }
        TarArchiveEntry symbolic=new TarArchiveEntry("payload/container/rootfs/a",TarConstants.LF_SYMLINK);symbolic.setLinkName("/etc/os-release");
        TarArchiveEntry hard=new TarArchiveEntry("payload/container/rootfs/b",TarConstants.LF_LINK);hard.setLinkName("payload/container/rootfs/a");
        File file=evil(Arrays.asList(dir("payload/container"),dir("payload/container/rootfs"),symbolic,hard));
        assertEquals("archive_invalid_hardlink",((DesktopArchiveException)rejected(()->inspect(file))).getCode());
    }
    @Test public void hardlinkCannotDeclareDifferentModificationTimeThanItsInodeSource()throws Exception {
        TarArchiveEntry first=new TarArchiveEntry("payload/container/a");first.setMode(0644);first.setModTime(0);first.addPaxHeader("TERMUX.hardlink","1");
        TarArchiveEntry link=new TarArchiveEntry("payload/container/b",TarConstants.LF_LINK);link.setMode(0644);link.setModTime(1000);link.setLinkName("payload/container/a");
        File file=evil(Arrays.asList(dir("payload/container"),first,link));
        assertEquals("archive_invalid_hardlink",((DesktopArchiveException)rejected(()->inspect(file))).getCode());
    }
    @Test public void inspectionDoesNotRetainDirectoriesButHostileHardlinkMetadataHasIndependentBudget()throws Exception {
        DesktopArchiveEngine large=new DesktopArchiveEngine(fs,new DesktopArchiveLimits(1024L*1024*1024,128L*1024*1024,200000,1024));
        List<TarArchiveEntry> entries=new ArrayList<>();entries.add(dir("payload/container"));
        for(int i=0;i<120000;i++)entries.add(dir(String.format(Locale.ROOT,"payload/container/d%06d",i)));
        File dirs=evil(entries);
        assertEquals("archive_integrity_missing",((DesktopArchiveException)rejected(()->large.readMetadata(dirs,"com.termux","aarch64"))).getCode());
        entries.clear();entries.add(dir("payload/container"));
        for(int i=0;i<120000;i++){
            TarArchiveEntry file=new TarArchiveEntry(String.format(Locale.ROOT,"payload/container/f%06d",i));
            file.setMode(0644);file.addPaxHeader("TERMUX.hardlink","1");entries.add(file);
        }
        File links=evil(entries);
        assertEquals("archive_retained_metadata_limit",((DesktopArchiveException)rejected(()->large.readMetadata(links,"com.termux","aarch64"))).getCode());
    }
    @Test public void excludedRuntimeContentIsRejectedOnImportNotSilentlyDropped()throws Exception {
        File file=evil(Arrays.asList(dir("payload/container"),dir("payload/container/rootfs"),dir("payload/container/rootfs/tmp"),new TarArchiveEntry("payload/container/rootfs/tmp/secret")));
        assertEquals("archive_excluded_path",((DesktopArchiveException)rejected(()->inspect(file))).getCode());
    }
    @Test public void corruptTarHeaderAndPayloadChecksumAreRejected()throws Exception {
        File file=archive();byte[] raw=gunzip(Files.readAllBytes(file.toPath()));byte[] broken=raw.clone();broken[0]^=1;
        Files.write(file.toPath(),gzip(broken));rejected(()->inspect(file));
        int start=index(raw,TEXT.getBytes("UTF-8"));assertTrue(start>0);raw[start]^=1;Files.write(file.toPath(),gzip(raw));
        assertEquals("archive_integrity_mismatch",((DesktopArchiveException)rejected(()->inspect(file))).getCode());
    }
    @Test public void validGzipWithMissingOneOrBothTarEndRecordsCannotSucceed()throws Exception {
        File file=archive();byte[] raw=gunzip(Files.readAllBytes(file.toPath()));
        for(int cut:new int[]{512,1024,1200}){Files.write(file.toPath(),gzip(Arrays.copyOf(raw,raw.length-cut)));rejected(()->inspect(file));}
    }
    @Test public void truncatedGzipBadCrcConcatenatedMembersAndTrailingGarbageAreRejected()throws Exception {
        File file=archive();byte[] gz=Files.readAllBytes(file.toPath());
        for(int cut:new int[]{1,4,8,17,gz.length/2}){Files.write(file.toPath(),Arrays.copyOf(gz,gz.length-cut));rejected(()->inspect(file));}
        byte[] crc=gz.clone();crc[crc.length-8]^=1;Files.write(file.toPath(),crc);rejected(()->inspect(file));
        for(byte[] tail:new byte[][]{new byte[]{1,2},gzip(new byte[]{0})}){
            ByteArrayOutputStream joined=new ByteArrayOutputStream();joined.write(gz);joined.write(tail);Files.write(file.toPath(),joined.toByteArray());rejected(()->inspect(file));
        }
    }
    @Test public void unsupportedPaxSizeOverrideAndSocketHeadersAreRejected()throws Exception {
        TarArchiveEntry entry=new TarArchiveEntry("pax-size",(byte)'x');
        rejected(()->inspect(evil(Arrays.asList(dir("payload/container"),entry))));
        TarArchiveEntry fifo=new TarArchiveEntry("payload/container/a",TarConstants.LF_FIFO);rejected(()->inspect(evil(Arrays.asList(dir("payload/container"),fifo))));
    }
    @Test public void tinyIndexLimitStillHandlesThousandsOfFilesWithThrottledProgress()throws Exception {
        for(int i=0;i<5000;i++)write(String.format(Locale.ROOT,"rootfs/opt/many/f%05d",i),"x");
        DesktopArchiveEngine smallIndex=new DesktopArchiveEngine(fs,new DesktopArchiveLimits(1024L*1024*1024,128L*1024*1024,30000,1024));
        AtomicInteger updates=new AtomicInteger();File file=temporary.resolve("many.tar.gz").toFile();
        smallIndex.exportArchive(source.toFile(),file,metadata,DesktopArchiveExportMode.DIRECT_EXCLUSIVE,(m,b,t)->updates.incrementAndGet());
        DesktopArchiveMetadata checked=smallIndex.readMetadata(file,"com.termux","aarch64");assertTrue(checked.getEntryCount()>5000);assertTrue(updates.get()<100);
        DesktopArchiveEngine tooFew=new DesktopArchiveEngine(fs,new DesktopArchiveLimits(1024L*1024*1024,128L*1024*1024,20,1024));rejected(()->tooFew.readMetadata(file,"com.termux","aarch64"));
    }
    @Test public void longUnicodePathsAndAbsoluteSymlinkTargetsUseValidatedPax()throws Exception {
        String name="rootfs/opt/"+String.join("",Collections.nCopies(30,"中文"));write(name,"long");
        File file=archive();DesktopArchiveImportResult result=restore(file,inspect(file));assertEquals("long",new String(Files.readAllBytes(result.getContainerDirectory().toPath().resolve(name)),"UTF-8"));
    }
    @Test public void actualSystemdSliceNamesPreserveLiteralPosixBackslashesContentAndPermissions()throws Exception {
        String[] names={"system-systemd\\x2dcryptsetup.slice","system-systemd\\x2dveritysetup.slice"};
        for(int i=0;i<names.length;i++) {
            String relative="rootfs/usr/lib/systemd/system/"+names[i];
            write(relative,"[Unit]\nDescription="+names[i]+"\n");
            Files.setAttribute(source.resolve(relative),"unix:mode",i==0?0644:0640);
        }
        File file=archive();DesktopArchiveImportResult restored=restore(file,inspect(file));
        for(int i=0;i<names.length;i++) {
            String relative="rootfs/usr/lib/systemd/system/"+names[i];
            Path actual=restored.getContainerDirectory().toPath().resolve(relative);
            assertArrayEquals(Files.readAllBytes(source.resolve(relative)),Files.readAllBytes(actual));
            assertEquals(i==0?0644:0640,((Number)Files.getAttribute(actual,"unix:mode")).intValue()&07777);
        }
        assertFalse(Files.exists(restored.getContainerDirectory().toPath().resolve("rootfs/usr/lib/systemd/system/system-systemd/x2dcryptsetup.slice")));
    }
    private static TarArchiveEntry dir(String name){TarArchiveEntry e=new TarArchiveEntry(name,TarConstants.LF_DIR);e.setMode(0755);return e;}
    private File evil(List<TarArchiveEntry> entries)throws Exception {
        File file=temporary.resolve("evil.tar.gz").toFile();
        try(OutputStream output=new FileOutputStream(file);GZIPOutputStream gz=new GZIPOutputStream(output);TarArchiveOutputStream tar=new TarArchiveOutputStream(gz,512,"UTF-8")){
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            byte[] data=metadata.encode();TarArchiveEntry manifest=new TarArchiveEntry("manifest.json");manifest.setSize(data.length);tar.putArchiveEntry(manifest);tar.write(data);tar.closeArchiveEntry();
            for(TarArchiveEntry e:entries){
                // Commons defaults include file-type bits; all fixtures here require valid
                // permission bits so their intended path/link checks are actually reached.
                if((e.getMode() & ~07777)!=0)e.setMode(e.isDirectory()?0755:e.isSymbolicLink()?0777:0644);
                byte[] body="pax-size".equals(e.getName())?"9 size=1\n".getBytes("UTF-8"):new byte[(int)e.getSize()];
                e.setSize(body.length);tar.putArchiveEntry(e);tar.write(body);tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return file;
    }
    private static byte[] gzip(byte[] raw)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream gz=new GZIPOutputStream(bytes)){gz.write(raw);}return bytes.toByteArray();}
    private static byte[] gunzip(byte[] gz)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(gz))){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return out.toByteArray();}
    private static int index(byte[] b,byte[] needle){outer:for(int i=0;i<=b.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(b[i+j]!=needle[j])continue outer;return i;}return -1;}

    static class NioFs implements DesktopArchiveFileSystem {
        @Override public Stat lstat(File f)throws IOException {
            Path p=f.toPath();Map<String,Object> a;
            try{a=Files.readAttributes(p,"unix:*",LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException e){return null;}
            int mode=((Number)a.get("mode")).intValue(),type=mode&0170000;
            Kind kind=type==0100000?Kind.FILE:type==0040000?Kind.DIRECTORY:type==0120000?Kind.SYMLINK:type==0140000?Kind.SOCKET:type==0010000?Kind.FIFO:Kind.OTHER;
            return new Stat(kind,mode&07777,((Number)a.get("size")).longValue(),((FileTime)a.get("lastModifiedTime")).toMillis(),((Number)a.get("dev")).longValue(),((Number)a.get("ino")).longValue(),((Number)a.get("nlink")).longValue());
        }
        @Override public InputStream openRegular(File f,Stat expected)throws IOException {
            if(!expected.sameFile(lstat(f)))throw new IOException("changed");return Files.newInputStream(f.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
        }
        @Override public ExclusiveOutput createExclusiveFile(File target)throws IOException {
            java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(target.toPath(),
                StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try {
                Stat identity=lstat(target);
                OutputStream stream=java.nio.channels.Channels.newOutputStream(channel);
                return new ExclusiveOutput(){
                    @Override public Stat getIdentity(){return identity;}
                    @Override public void write(int b)throws IOException{stream.write(b);}
                    @Override public void write(byte[] b,int offset,int length)throws IOException{stream.write(b,offset,length);}
                    @Override public void flush()throws IOException{stream.flush();}
                    @Override public void sync()throws IOException{channel.force(true);}
                    @Override public void close()throws IOException{stream.close();}
                };
            }catch(IOException|RuntimeException|Error e){try{channel.close();}catch(IOException close){e.addSuppressed(close);}throw e;}
        }
        @Override public String readLink(File f)throws IOException{return Files.readSymbolicLink(f.toPath()).toString();}
        @Override public void createSymbolicLink(String target,File link)throws IOException{Files.createSymbolicLink(link.toPath(),Paths.get(target));}
        @Override public void createHardLink(File target,File link)throws IOException{Files.createLink(link.toPath(),target.toPath());}
        @Override public void applyMode(File f,int mode)throws IOException{Files.setAttribute(f.toPath(),"unix:mode",mode,LinkOption.NOFOLLOW_LINKS);}
        @Override public void applyModifiedTime(File f,long value)throws IOException{Files.setLastModifiedTime(f.toPath(),FileTime.fromMillis(value));}
        @Override public void rename(File from,File to)throws IOException{Files.move(from.toPath(),to.toPath());}
    }
}
