package com.termux.api;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import org.json.*;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.channels.FileLock;

public class RuntimeApiComponentsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File prefix;
    private LinkedHashMap<String, byte[]> files;
    private JSONObject component;
    private byte[] archive;
    private AtomicInteger downloads;
    private final RuntimeApiComponents.Platform platform = new RuntimeApiComponents.Platform() {
        public boolean isSymlink(File path) { return Files.isSymbolicLink(path.toPath()); }
        public void chmod(File path, int mode) throws IOException {
            if (!path.setExecutable((mode & 0100) != 0, true)) throw new IOException("chmod");
        }
    };
    @Before public void setup() throws Exception {
        prefix = temp.newFolder("usr"); assertTrue(new File(prefix,"bin").mkdir());
        files = new LinkedHashMap<>();
        files.put("bin/termux-battery-status", "script".getBytes());
        files.put("libexec/termux-api", "native-cli".getBytes());
        files.put("libexec/termux-callback", "callback".getBytes());
        files.put("share/embedded-api/termux-api.deb", "deb".getBytes());
        downloads = new AtomicInteger(); rebuild();
    }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x",b & 255));
        return result.toString();
    }
    private void rebuild() throws Exception {
        archive = zip(null, false, false);
        JSONObject inventory = new JSONObject();
        for (Map.Entry<String, byte[]> file : files.entrySet()) inventory.put(file.getKey(),
            new JSONObject().put("sha256",sha(file.getValue())).put("size",file.getValue().length)
                .put("executable",file.getKey().startsWith("bin/") || file.getKey().startsWith("libexec/")));
        component = new JSONObject().put("abi","arm64-v8a").put("contract","embedded-v1")
            .put("url","https://example.invalid/companion.zip").put("size",archive.length)
            .put("sha256",sha(archive)).put("files",inventory);
    }
    private byte[] zip(String extra, boolean symlink, boolean duplicate) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
            for (Map.Entry<String,byte[]> file : files.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.getKey());
                entry.setUnixMode(symlink ? 0120777 : 0100644);
                zip.putArchiveEntry(entry); zip.write(file.getValue()); zip.closeArchiveEntry();
            }
            if (extra != null || duplicate) {
                ZipArchiveEntry entry = new ZipArchiveEntry(duplicate ? files.keySet().iterator().next() : extra);
                entry.setUnixMode(0100644); zip.putArchiveEntry(entry); zip.write(new byte[]{1}); zip.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }
    private RuntimeApiComponents installer() {
        return new RuntimeApiComponents(platform, (url,target,size) -> {
            downloads.incrementAndGet(); Files.write(target.toPath(),archive);
        });
    }
    private RuntimeApiComponents offline() {
        return new RuntimeApiComponents(platform,(url,target,size) -> { throw new IOException("offline"); });
    }
    private byte[] content(String path) throws Exception { return Files.readAllBytes(new File(prefix,path).toPath()); }
    private void expectFailure(RuntimeApiComponents installer) throws Exception {
        try { installer.install(prefix,component); fail("expected failure"); } catch (IOException expected) { }
    }
    @Test public void selectedAbiRequiresExactContract() throws Exception {
        JSONObject catalog = new JSONObject().put("schema",1).put("contract","embedded-v1")
            .put("components",new JSONObject().put("arm64-v8a",component));
        assertSame(component, RuntimeApiComponents.resolve(catalog,"arm64-v8a"));
        try { RuntimeApiComponents.resolve(catalog,"x86"); fail(); } catch (IOException expected) { }
        component.put("abi","x86");
        try { RuntimeApiComponents.resolve(catalog,"arm64-v8a"); fail(); } catch (IOException expected) { }
        component.put("abi","arm64-v8a"); catalog.put("contract","other-apk");
        try { RuntimeApiComponents.resolve(catalog,"arm64-v8a"); fail(); } catch (IOException expected) { }
    }
    @Test public void exactInstalledFilesAreReusableOffline() throws Exception {
        installer().install(prefix,component);
        assertEquals(1,downloads.get()); assertTrue(installer().matches(prefix,component));
        offline().install(prefix,component);
        assertArrayEquals(files.get("libexec/termux-api"),content("libexec/termux-api"));
        assertTrue(new File(prefix,"share/embedded-api/receipt.json").isFile());
    }
    @Test public void exactLegacyInstallationIsAdoptedWithoutDownloading() throws Exception {
        for (Map.Entry<String,byte[]> file : files.entrySet()) {
            File target = new File(prefix,file.getKey()); target.getParentFile().mkdirs();
            Files.write(target.toPath(),file.getValue()); target.setExecutable(true);
        }
        offline().install(prefix,component);
        assertTrue(new File(prefix,"share/embedded-api/receipt.json").isFile());
    }
    @Test public void cacheRepairsOfflineAndBadCacheCannotActivate() throws Exception {
        installer().install(prefix,component);
        new File(prefix,"libexec/termux-api").delete();
        offline().install(prefix,component);
        assertTrue(installer().matches(prefix,component));
        new File(prefix,"libexec/termux-api").delete();
        File cached = new File(prefix,".runtime-api/cache/"+component.getString("sha256")+".zip");
        Files.write(cached.toPath(),new byte[]{1}); expectFailure(offline()); assertFalse(cached.exists());
        installer().install(prefix,component); assertEquals(2,downloads.get());
    }
    @Test public void offlineFirstInitializationIsExplicitlyIncompleteAndRetryWorks() throws Exception {
        File user = new File(prefix,"bin/user-script"); Files.write(user.toPath(),"custom".getBytes());
        expectFailure(offline());
        assertFalse(new File(prefix,"share/embedded-api/receipt.json").exists());
        assertEquals("custom",new String(content("bin/user-script")));
        installer().install(prefix,component); assertTrue(installer().matches(prefix,component));
    }
    @Test public void wrongDownloadHashNeverOverwritesExistingVersion() throws Exception {
        installer().install(prefix,component); byte[] before = content("bin/termux-battery-status");
        files.put("bin/termux-battery-status","updated".getBytes()); rebuild();
        expectFailure(new RuntimeApiComponents(platform,(url,target,size) -> Files.write(target.toPath(),new byte[]{0})));
        assertArrayEquals(before,content("bin/termux-battery-status"));
        assertFalse(new File(prefix,".runtime-api/pending").exists());
    }
    @Test public void interruptedDownloadIsRemovedAndOldVersionPreserved() throws Exception {
        installer().install(prefix,component); byte[] before=content("share/embedded-api/receipt.json");
        files.put("bin/termux-battery-status","updated".getBytes()); rebuild();
        expectFailure(new RuntimeApiComponents(platform,(url,target,size) -> {
            Files.write(target.toPath(),new byte[]{0}); throw new InterruptedIOException("cancelled");
        }));
        assertArrayEquals(before,content("share/embedded-api/receipt.json"));
        for (File p : new File(prefix,".runtime-api/cache").listFiles()) assertFalse(p.getName().endsWith(".part"));
    }
    @Test public void unsafeArchivesCannotPublishFiles() throws Exception {
        for (String kind : new String[]{"traversal","symlink","duplicate","oversized","incomplete"}) {
            if (kind.equals("oversized")) {
                files.put("libexec/termux-api",new byte[1024]); archive=zip(null,false,false); files.put("libexec/termux-api","native-cli".getBytes());
            } else if (kind.equals("incomplete")) {
                byte[] removed=files.remove("libexec/termux-api"); archive=zip(null,false,false); files.put("libexec/termux-api",removed);
            } else archive=zip(kind.equals("traversal") ? "../escape" : null,kind.equals("symlink"),kind.equals("duplicate"));
            component.put("size",archive.length).put("sha256",sha(archive)); expectFailure(installer());
            assertFalse(new File(prefix,"share/embedded-api/receipt.json").exists());
            assertFalse(new File(prefix,".runtime-api/pending").exists());
            assertFalse(new File(prefix.getParentFile(),"escape").exists());
        }
    }
    @Test public void symlinkTargetsNeverWriteOutsidePrefix() throws Exception {
        File outside=temp.newFolder("outside"); Files.createSymbolicLink(new File(prefix,"libexec").toPath(),outside.toPath());
        expectFailure(installer()); assertEquals(0,outside.list().length);
    }
    @Test public void installationFailureRollsBackEveryOriginalFile() throws Exception {
        installer().install(prefix,component); byte[] original=content("bin/termux-battery-status");
        files.put("bin/termux-battery-status","updated".getBytes()); rebuild();
        RuntimeApiComponents failing = new RuntimeApiComponents(platform,(url,target,size) -> Files.write(target.toPath(),archive)) {
            protected void move(File source,File target) throws IOException {
                if (source.getPath().contains("/staged/libexec/termux-api")) throw new IOException("disk full");
                super.move(source,target);
            }
        };
        expectFailure(failing); assertArrayEquals(original,content("bin/termux-battery-status"));
        assertFalse(new File(prefix,".runtime-api/pending").exists());
    }
    @Test public void processDeathBeforeCommitIsRecoveredOnNextExplicitRetry() throws Exception {
        installer().install(prefix,component); byte[] original=content("bin/termux-battery-status");
        files.put("bin/termux-battery-status","updated".getBytes()); rebuild();
        RuntimeApiComponents crashing = new RuntimeApiComponents(platform,(url,target,size) -> Files.write(target.toPath(),archive)) {
            protected void move(File source,File target) throws IOException {
                if (source.getPath().contains("/staged/libexec/termux-api")) throw new AssertionError("process death");
                super.move(source,target);
            }
        };
        try { crashing.install(prefix,component); fail(); } catch (AssertionError expected) { }
        assertTrue(new File(prefix,".runtime-api/pending/plan.json").exists());
        // Remove the verified candidate cache to force a failing download after recovery.
        new File(prefix,".runtime-api/cache/"+component.getString("sha256")+".zip").delete();
        expectFailure(offline()); assertArrayEquals(original,content("bin/termux-battery-status"));
        installer().install(prefix,component); assertTrue(installer().matches(prefix,component));
    }
    @Test public void concurrentInstallIsRejected() throws Exception {
        File state=new File(prefix,".runtime-api"); state.mkdir();
        try (RandomAccessFile lockFile=new RandomAccessFile(new File(state,"install.lock"),"rw"); FileLock lock=lockFile.getChannel().lock()) {
            expectFailure(installer()); assertEquals(0,downloads.get());
        }
    }
    @Test public void plainHttpIsRejectedBeforeConnection() throws Exception {
        try { RuntimeApiComponents.downloadHttps("http://127.0.0.1/no-network",temp.newFile(),1); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("HTTPS")); }
    }
    @Test public void actualPublishedCompanionsInstallForEveryAbi() throws Exception {
        File built = new File("build/outputs/apiComponents");
        JSONObject catalog = new JSONObject(new String(Files.readAllBytes(new File(built,"runtime-api-components.json").toPath()),"UTF-8"));
        String[] abis = {"arm64-v8a","armeabi-v7a","x86","x86_64"};
        int[] machines = {183,40,3,62};
        for (int i=0; i<abis.length; i++) {
            JSONObject entry = RuntimeApiComponents.resolve(catalog,abis[i]);
            File root = temp.newFolder(abis[i]); assertTrue(new File(root,"bin").mkdir());
            File payload = new File(built, entry.getString("asset"));
            RuntimeApiComponents instance = new RuntimeApiComponents(platform,(url,target,size) ->
                Files.copy(payload.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING));
            instance.install(root,entry); assertTrue(instance.matches(root,entry));
            byte[] elf=Files.readAllBytes(new File(root,"libexec/termux-api").toPath());
            assertEquals(0x7f,elf[0]); assertEquals('E',elf[1]);
            assertEquals(machines[i],(elf[18]&255) | ((elf[19]&255)<<8));
            assertTrue(new File(root,"share/embedded-api/termux-api.deb").length()>0);
            assertEquals(i==0,new File(root,"share/embedded-api/upower.deb").exists());
        }
    }
    @Test public void invalidRecoveryJournalCannotModifyActiveFiles() throws Exception {
        installer().install(prefix,component); byte[] before=content("libexec/termux-api");
        File pending=new File(prefix,".runtime-api/pending"); assertTrue(pending.mkdir());
        JSONArray plan=new JSONArray();
        for (String name : files.keySet()) plan.put(new JSONObject().put("path",name).put("original",false));
        plan.put(new JSONObject().put("path","../outside").put("original",false));
        Files.write(new File(pending,"plan.json").toPath(),plan.toString().getBytes());
        expectFailure(installer()); assertArrayEquals(before,content("libexec/termux-api"));
    }

}
