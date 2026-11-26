package com.termux.ui.ide;

import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class IdeModuleContextsTest {
    private JSONObject module(String name, String type) throws Exception {
        return new JSONObject().put("type", type).put("metadata", new JSONObject()
            .put("projectPath", ":" + name).put("projectDir", "/workspace/" + name)
            .put("classesJar", "/workspace/" + name + "/build/classes.jar"));
    }
    private JSONObject java(String name, String dependency) throws Exception {
        return module(name, "Java").put("sources", new JSONArray().put(new JSONObject().put("sourceDirectories",
            new JSONArray().put(new JSONObject().put("directory", "/workspace/" + name + "/src/main/java")))))
            .put("dependencies", new JSONArray().put(new JSONObject().put("scope", "COMPILE").put("projectPath", ":" + dependency)));
    }
    private JSONObject model(JSONObject... modules) throws Exception {
        return new JSONObject().put("modules", new JSONArray(Arrays.asList(modules)));
    }
    @Test public void transitiveModulesAndCyclesKeepOwningModuleAndUniqueSources() throws Exception {
        JSONObject a = java("app", "library"), b = java("library", "app");
        b.getJSONArray("dependencies").put(new JSONObject().put("scope", "RUNTIME").put("jarFile", "/cache/runtime-only.jar"));
        b.getJSONArray("dependencies").put(new JSONObject().put("scope", "COMPILE").put("jarFile", "/cache/api.jar"));
        IdeModuleContexts contexts = new IdeModuleContexts(model(a,b));
        IdeModuleContexts.Module app = contexts.forFile(new File("/workspace/app/src/main/java/Demo.java"));
        assertNotNull(app); assertEquals(":app", app.path); assertEquals(2, app.sources.size());
        assertTrue(app.classpaths.contains(new File("/cache/api.jar")));
        assertFalse(app.classpaths.contains(new File("/cache/runtime-only.jar")));
        assertEquals(Collections.singleton(":library"), app.dependencies);
        assertEquals(":library", contexts.forFile(new File("/workspace/library/src/main/java/Api.java")).path);
        assertNull(contexts.forFile(new File("/workspace/application/Demo.java")));
    }
    @Test public void androidModelIncludesPlatformAarGeneratedAndProjectDependencies() throws Exception {
        JSONObject app = module("app", "Android").put("variant", "debug")
            .put("bootClasspaths", new JSONArray().put("/sdk/android.jar"))
            .put("sources", new JSONObject().put("sourceProvider", new JSONObject()
                .put("javaDirectories", new JSONArray().put("/workspace/app/src/main/java"))
                .put("resDirectories", new JSONArray().put("/workspace/app/src/main/res"))))
            .put("artifact", new JSONObject().put("mainArtifact", new JSONObject()
                .put("generatedSourceFolders", new JSONArray().put("/workspace/app/build/generated"))
                .put("classJars", new JSONArray().put("/workspace/app/build/R.jar"))))
            .put("libraries", new JSONObject()
                .put("aar", new JSONObject().put("type", "ANDROID_LIBRARY").put("dependencies", new JSONArray().put("aar"))
                    .put("androidLibraryData", new JSONObject().put("compileJarFiles", new JSONArray().put("/cache/aar/classes.jar"))))
                .put("project", new JSONObject().put("type", "PROJECT").put("projectInfo", new JSONObject().put("projectPath", ":library"))));
        JSONObject library = java("library", "app");
        IdeModuleContexts.Module context = new IdeModuleContexts(model(app,library)).modules.get(0);
        assertEquals("debug", context.variant);
        assertTrue(context.bootClasspaths.contains(new File("/sdk/android.jar")));
        assertTrue(context.sources.contains(new File("/workspace/app/build/generated")));
        assertTrue(context.classpaths.contains(new File("/workspace/app/build/R.jar")));
        assertTrue(context.classpaths.contains(new File("/cache/aar/classes.jar")));
        assertTrue(context.classpaths.contains(new File("/workspace/library/build/classes.jar")));
        assertTrue(context.resources.contains(new File("/workspace/app/src/main/res")));
        assertEquals(1, context.toJson().getJSONArray("dependencies").length());
    }
    @Test public void capturedOriginalAndroidModelPreservesSelectedVariantAndBootClasspath() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = getClass().getResourceAsStream("/ide/android-gradle-model.json")) {
            assertNotNull(input); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        IdeModuleContexts contexts = new IdeModuleContexts(new JSONObject(bytes.toString("UTF-8")));
        IdeModuleContexts.Module library = null;
        for (IdeModuleContexts.Module module : contexts.modules) if (module.path.equals(":library")) library = module;
        assertNotNull(library); assertEquals("debug", library.variant);
        assertEquals(1, library.bootClasspaths.size());
        assertTrue(library.bootClasspaths.iterator().next().getName().equals("android.jar"));
        assertTrue(library.sources.stream().anyMatch(file -> file.getPath().endsWith("/library/src/main/java")));
        assertTrue(library.classpaths.stream().anyMatch(file -> file.getName().equals("R.jar")));
        assertEquals("1.8", library.sourceVersion);
    }
    @Test public void capturedNativeDeviceModelResolvesAppInsteadOfRootJavaProject() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = getClass().getResourceAsStream("/ide/native-gradle-model.json")) {
            assertNotNull(input); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        IdeModuleContexts contexts = new IdeModuleContexts(new JSONObject(bytes.toString("UTF-8")));
        assertEquals(2, contexts.modules.size());
        IdeModuleContexts.Module app = contexts.forFile(new File("/data/user/0/com.termux/files/home/projects/VerifyWelcome/app/src/main/java/example/MainActivity.java"));
        assertNotNull(app); assertEquals(":app", app.path);
        assertEquals("debug", app.variant); assertEquals(1, app.bootClasspaths.size());
        assertTrue(app.sources.stream().anyMatch(file -> file.getPath().endsWith("app/src/main/java")));
    }
    @Test public void missingModulesAndAmbiguousPathsFailInsteadOfInventingClasspath() throws Exception {
        try { new IdeModuleContexts(model(java("app", "missing"))); fail(); } catch (IOException expected) { }
        JSONObject a = module("app", "Java");
        try { new IdeModuleContexts(model(a,a)); fail(); } catch (IOException expected) { }
        a.getJSONObject("metadata").put("classesJar", "relative.jar");
        try { new IdeModuleContexts(model(a)); fail(); } catch (IOException expected) { }
    }
}
