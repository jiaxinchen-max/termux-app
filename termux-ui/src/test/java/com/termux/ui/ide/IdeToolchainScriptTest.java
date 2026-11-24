package com.termux.ui.ide;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Runs the real orchestration script with fake native tools: no network, no package changes. */
public class IdeToolchainScriptTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File script, project, home, prefix, sdk, jdk, calls;
    private String output;
    @Before public void setup() throws Exception {
        script = temp.newFile("toolchain.sh");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("ide/toolchain.sh")) { Files.copy(in, script.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        project = temp.newFolder("project with spaces"); home = temp.newFolder("home"); prefix = temp.newFolder("usr");
        sdk = temp.newFolder("sdk"); jdk = temp.newFolder("jdk"); calls = temp.newFile("calls");
        executable(new File(jdk, "bin/java"), "echo 'openjdk version \"17.0.12\"' >&2\n");
        executable(new File(prefix, "bin/aapt2"), "echo native-aapt2\n");
        executable(new File(prefix, "bin/curl"), "echo network-unexpected >&2; exit 97\n");
        executable(new File(prefix, "bin/unzip"), "exit 97\n");
        executable(new File(prefix, "bin/apt-get"), "echo apt-unexpected >&2; exit 98\n");
        File platform = new File(sdk, "platforms/android-34"); assertTrue(platform.mkdirs()); assertTrue(new File(platform, "android.jar").createNewFile());
        File buildTools = new File(sdk, "build-tools/34.0.0"); assertTrue(buildTools.mkdirs()); assertTrue(new File(buildTools, "package.xml").createNewFile());
        executable(new File(project, "gradlew"), "printf '%s\\n' \"$@\" > \"$IDE_CALLS\"\nexit \"${IDE_TEST_EXIT:-0}\"\n");
    }
    private void executable(File file, String contents) throws Exception {
        file.getParentFile().mkdirs(); Files.write(file.toPath(), ("#!/bin/bash\n" + contents).getBytes(StandardCharsets.UTF_8)); assertTrue(file.setExecutable(true));
    }
    private int run(String operation, String code) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", script.getPath(), operation).directory(project).redirectErrorStream(true);
        Map<String,String> env = builder.environment();
        env.put("HOME", home.getPath()); env.put("PREFIX", prefix.getPath()); env.put("JAVA_HOME", jdk.getPath());
        env.put("ANDROID_HOME", sdk.getPath()); env.put("IDE_AAPT2", new File(prefix, "bin/aapt2").getPath());
        env.put("PATH", prefix + "/bin:/usr/bin:/bin"); env.put("TMPDIR", temp.getRoot().getPath());
        env.put("IDE_CALLS", calls.getPath()); env.put("IDE_TEST_EXIT", code);
        Process process = builder.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = process.getInputStream().read(b)) != -1) bytes.write(b, 0, n);
        output = new String(bytes.toByteArray(), StandardCharsets.UTF_8); return process.waitFor();
    }
    @Test public void compatibleInstalledToolsSkipAllDownloads() throws Exception {
        assertEquals(output, 0, run("prepare", "0"));
        assertTrue(output, output.contains("Existing compatible SDK reused"));
    }
    @Test public void reusingCompleteToolsDoesNotRequireAdditionalDownloadSpace() throws Exception {
        executable(new File(prefix, "bin/df"), "echo unexpected-disk-check >&2; exit 99\n");
        assertEquals(0, run("prepare", "0"));
    }
    @Test public void prepareFindsJdkInstalledIntoModernTermuxLayout() throws Exception {
        File installed = new File(prefix, "lib/jvm/java-17-openjdk/bin/java");
        installed.getParentFile().mkdirs();
        Files.move(new File(jdk, "bin/java").toPath(), new File(prefix, "java-package").toPath());
        executable(new File(prefix, "bin/apt-get"),
            "if test \"$1\" = install; then\n" +
            "cp \"$PREFIX/java-package\" \"$PREFIX/lib/jvm/java-17-openjdk/bin/java\"\n" +
            "chmod +x \"$PREFIX/lib/jvm/java-17-openjdk/bin/java\"\nfi\n");
        assertEquals(output, 0, run("prepare", "0"));
        assertTrue(output, output.contains("Existing compatible SDK reused"));
    }
    @Test public void prepareInstallsSeparateAapt2PackageEvenWhenAaptExists() throws Exception {
        assertTrue(new File(prefix, "bin/aapt2").delete());
        executable(new File(prefix, "bin/aapt"), "exit 0\n");
        executable(new File(prefix, "bin/apt-get"),
            "if test \"$1\" = install; then\n" +
            "printf '%s\\n' \"$@\" > \"$IDE_CALLS\"\n" +
            "case \" $* \" in *' aapt2 '*) cp \"$PREFIX/bin/aapt\" \"$PREFIX/bin/aapt2\";; esac\nfi\n");
        assertEquals(output, 0, run("prepare", "0"));
        List<String> installed = Files.readAllLines(calls.toPath());
        assertTrue(installed.toString(), installed.contains("aapt2"));
        assertFalse(installed.toString(), installed.contains("aapt"));
    }
    @Test public void failedAapt2InstallStopsBeforeSdkDownload() throws Exception {
        assertTrue(new File(prefix, "bin/aapt2").delete());
        assertTrue(new File(sdk, "platforms/android-34/android.jar").delete());
        executable(new File(prefix, "bin/apt-get"), "exit 0\n");
        assertNotEquals(0, run("prepare", "0"));
        assertTrue(output, output.contains("Missing native aapt2 after package installation"));
        assertFalse(output, output.contains("network-unexpected"));
    }
    @Test public void checkCannotMaskJavaFailureWithSuccessfulAapt() throws Exception {
        // Native probes succeed, so Java is the only failure.
        String compiler = "while test \"$1\" != -o; do shift; done\nshift\nprintf '#!/bin/sh\\nexit 0\\n' > \"$1\"\nchmod +x \"$1\"\n";
        executable(new File(prefix, "bin/clang"), compiler);
        executable(new File(prefix, "bin/clang++"), compiler);
        assertEquals(0, run("check", "0"));
        executable(new File(jdk, "bin/java"), "echo 'openjdk version \"25.0.1\"' >&2\n");
        assertNotEquals(0, run("check", "0"));
    }
    @Test public void buildUsesProjectsWrapperAndNativeAaptWithSeparatedArguments() throws Exception {
        assertEquals(output, 0, run("build", "0"));
        List<String> args = Files.readAllLines(calls.toPath());
        assertTrue(args.contains("--no-daemon")); assertTrue(args.contains("assembleDebug"));
        assertTrue(args.contains("-Pandroid.aapt2FromMavenOverride=" + prefix + "/bin/aapt2"));
        assertTrue(args.contains("-Dorg.gradle.java.home=" + jdk));
    }
    @Test public void gradleFailureIsNotConvertedToSuccess() throws Exception { assertEquals(23, run("build", "23")); }
    @Test public void incompatibleJdkStopsBeforeRunningProject() throws Exception {
        executable(new File(jdk, "bin/java"), "echo 'openjdk version \"25.0.1\"' >&2\n");
        assertNotEquals(0, run("build", "0")); assertEquals(0, calls.length()); assertTrue(output.contains("JDK 17–21"));
    }
    @Test public void missingWrapperOrAaptBlocksBuild() throws Exception {
        assertTrue(new File(project, "gradlew").delete()); assertNotEquals(0, run("build", "0"));
        assertTrue(output.contains("no Gradle Wrapper")); assertEquals(0, calls.length());
        assertTrue(new File(prefix, "bin/aapt2").delete()); assertNotEquals(0, run("build", "0"));
        assertTrue(output.contains("native aapt2"));
    }
}
