package com.termux.ui.ide;

import org.junit.Test;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class IdeToolingModelTest {
    @Test public void acceptsUpstreamWireEnumsAndRejectsUnknownProtocol() throws Exception {
        assertEquals("Android", IdeToolingModel.projectType(1));
        assertEquals("Java", IdeToolingModel.projectType(2));
        assertEquals("Gradle", IdeToolingModel.projectType("Gradle"));
        for (Object bad : Arrays.asList(99, -1, 1.5, "1", JSONObject.NULL)) {
            try { IdeToolingModel.projectType(bad); fail(); } catch (IOException expected) { }
        }
    }
    @Test(timeout = 90000) public void originalServerReturnsRealMultiModuleModel() throws Exception {
        File jar = new File(System.getProperty("ide.tooling.jar"));
        File gradle = new File(System.getProperty("ide.tooling.gradle"));
        assertTrue(jar.isFile());
        Path project = Files.createTempDirectory("ide-tooling-test-");
        java.lang.Process process = null;
        try {
            write(project.resolve("settings.gradle"), "rootProject.name='model-test'\ninclude 'library'\n");
            write(project.resolve("build.gradle"), "");
            write(project.resolve("library/build.gradle"), "plugins { id 'java-library' }\n");
            write(project.resolve("library/src/main/java/example/Demo.java"), "package example; public class Demo {}\n");
            process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-jar", jar.getPath())
                .redirectError(project.resolve("server-stderr.log").toFile()).start();
            try (IdeToolingRpc rpc = new IdeToolingRpc(process.getInputStream(), process.getOutputStream(), new IdeToolingRpc.Client() {
                public Object request(String method, Object params) throws Exception {
                    if (method.equals("client/getBuildArguments")) return new JSONArray().put("--offline");
                    if (method.equals("client/checkGradleWrapperAvailability")) return new JSONObject().put("isAvailable", true);
                    throw new IOException(method);
                }
                public void notification(String method, Object params) { }
            })) {
                try {
                    JSONObject params = new JSONObject().put("directory", project.toString())
                        .put("gradleDistribution", new JSONObject().put("type", "GRADLE_INSTALLATION").put("value", gradle.getPath()))
                        .put("androidParams", new JSONObject().put("variantSelections", new JSONObject()));
                    JSONObject initialized = (JSONObject) rpc.call("server/initialize", params, 60);
                    assertTrue(initialized.toString(), initialized.getBoolean("isSuccessful"));
                    JSONObject model = IdeToolingModel.read((m, p) -> rpc.call(m, p, 10), project.toString());
                    JSONArray modules = model.getJSONArray("modules");
                    assertEquals(2, modules.length());
                    JSONObject library = null;
                    for (int i = 0; i < modules.length(); i++) {
                        JSONObject module = modules.getJSONObject(i);
                        if (module.getJSONObject("metadata").getString("projectPath").equals(":library")) library = module;
                    }
                    assertNotNull(library);
                    assertEquals("Java", library.getString("type"));
                    assertTrue(library.getJSONArray("tasks").toString().contains(":library:compileJava"));
                    assertTrue(library.getJSONArray("sources").toString().contains("library/src/main/java"));
                    assertTrue(library.has("dependencies"));
                } finally { try { rpc.call("server/shutdown", null, 3); } catch (Exception ignored) { } }
            }
        } finally {
            if (process != null && !process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            }
            try (java.util.stream.Stream<Path> files = Files.walk(project)) {
                for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file);
            }
        }
    }
    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent()); Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
