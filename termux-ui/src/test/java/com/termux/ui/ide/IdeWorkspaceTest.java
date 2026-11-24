package com.termux.ui.ide;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class IdeWorkspaceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private IdeWorkspace ws;
    @Before public void setUp() throws Exception { ws = new IdeWorkspace(temporary.newFolder("projects")); }
    private File create(String name) throws Exception {
        try (InputStream wrapper = getClass().getClassLoader().getResourceAsStream("ide/gradle/wrapper/gradle-wrapper.jar");
             InputStream license = getClass().getClassLoader().getResourceAsStream("ide/GRADLE-LICENSE.txt")) {
            return IdeTemplate.create(ws, name, "com.example.app", wrapper, license);
        }
    }
    @Test public void realTemplateContainsSourceLayoutAndVerifiedWrapperAndIsReopenable() throws Exception {
        File project = create("My Project");
        assertEquals(project, ws.owner(new File(project, "app/src/main/java/com/example/app/MainActivity.java")));
        assertTrue(new File(project, "app/src/main/res/layout/activity_main.xml").isFile());
        Properties wrapper = new Properties();
        try (InputStream in = new FileInputStream(new File(project, "gradle/wrapper/gradle-wrapper.properties"))) { wrapper.load(in); }
        assertEquals("https://services.gradle.org/distributions/gradle-8.7-bin.zip", wrapper.getProperty("distributionUrl"));
        assertEquals(IdeTemplate.GRADLE_SHA256, wrapper.getProperty("distributionSha256Sum"));
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new File(project, "gradle/wrapper/gradle-wrapper.jar"))) {
            assertNotNull(jar.getJarEntry("org/gradle/wrapper/GradleWrapperMain.class"));
        }
        assertTrue(new File(project, "GRADLE-LICENSE.txt").length() > 1000);
        assertEquals(project, new IdeWorkspace(ws.root).projects().get(0));
    }
    @Test public void unicodeProjectNamesAreSupported() throws Exception {
        assertEquals("我的应用", create("我的应用").getName());
    }
    @Test public void traversalAndSymlinkEscapeAreRejected() throws Exception {
        File external = temporary.newFolder("outside");
        Files.createSymbolicLink(new File(ws.root, "link").toPath(), external.toPath());
        for (File file : new File[] {new File(ws.root, "../outside/data"), new File(ws.root, "link/data")}) {
            try { ws.checked(file); fail("accepted escape"); } catch (IOException expected) { }
        }
        assertTrue(ws.projects().isEmpty());
        File staging = new File(ws.root, ".import"); assertTrue(staging.mkdir());
        for (String path : new String[] {"../outside", "/tmp/escape"}) {
            try { ws.importTarget(staging, path); fail("accepted import escape"); } catch (IOException expected) { }
        }
    }
    @Test public void deletionNeverFollowsLinksOrTouchesOtherProjects() throws Exception {
        File one = create("one"), two = create("two");
        File outside = temporary.newFile("preserve");
        Files.createSymbolicLink(new File(one, "link").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(one, "otherProject").toPath(), two.toPath());
        ws.deleteProject(one);
        assertFalse(one.exists()); assertTrue(two.isDirectory()); assertTrue(outside.isFile());
    }
    @Test public void invalidAndDuplicateProjectsDoNotPartiallyPublish() throws Exception {
        create("existing");
        for (String name : new String[] {"../escape", "existing"}) {
            try { create(name); fail("accepted project"); } catch (IOException expected) { }
        }
        assertEquals(1, ws.projects().size());
        for (String name : ws.root.list()) assertFalse(name.startsWith(".create-"));
    }
    @Test public void oldBuildApksAreIsolatedButImportedGameApksAreNotMoved() throws Exception {
        File project = create("app");
        File stale = new File(project, "app/build/outputs/apk/debug/app-debug.apk"); ws.write(stale, "stale");
        File imported = new File(project, "fixtures/test.apk"); ws.write(imported, "fixture");
        File cache = new File(project, ".gradle/cache/build/outputs/hidden.apk"); ws.write(cache, "cache");
        File previous = temporary.newFolder("job"); File isolated = new File(previous, "previous-apks");
        IdeJobStore.isolatePreviousApks(project, ws, isolated);
        assertFalse(stale.exists()); assertTrue(imported.exists()); assertTrue(cache.exists());
        assertTrue(IdeJobStore.apks(project, ws).isEmpty());
        assertEquals("stale", new String(Files.readAllBytes(new File(isolated, "0.apk").toPath()), StandardCharsets.UTF_8));
        ws.write(stale, "new"); assertEquals(Collections.singletonList(stale), IdeJobStore.apks(project, ws));
    }
}
