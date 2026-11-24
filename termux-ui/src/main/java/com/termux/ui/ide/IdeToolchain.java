package com.termux.ui.ide;

import android.content.Context;
import android.content.SharedPreferences;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import java.io.*;
import java.util.*;

/** Discovers native installations without changing other sessions' environment. */
public final class IdeToolchain {
    public final File prefix, home, sdk, javaHome, aapt2;
    public IdeToolchain(Context context) throws IOException {
        prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        SharedPreferences p = context.getSharedPreferences("termux_ide", Context.MODE_PRIVATE);
        String customJava = p.getString("java_home", "").trim();
        File candidate = null;
        if (!customJava.isEmpty()) candidate = new File(customJava);
        for (String name : new String[] {"opt/openjdk-17", "opt/openjdk", "opt/openjdk-21"}) {
            File file = new File(prefix, name);
            if (candidate == null && new File(file, "bin/java").canExecute()) candidate = file;
        }
        File binJava = new File(prefix, "bin/java");
        if (candidate == null && binJava.canExecute()) candidate = binJava.getCanonicalFile().getParentFile().getParentFile();
        javaHome = candidate == null ? new File(prefix, "opt/openjdk") : candidate;
        String customSdk = p.getString("sdk_home", "").trim();
        File sdkCandidate = customSdk.isEmpty() ? null : new File(customSdk);
        for (File file : new File[] {new File(home, "android-sdk"), new File(prefix, "share/android-sdk"), new File(home, ".androidide/android-sdk")}) {
            if (sdkCandidate == null && new File(file, "platforms").isDirectory()) sdkCandidate = file;
        }
        sdk = sdkCandidate == null ? new File(home, "android-sdk") : sdkCandidate;
        aapt2 = new File(prefix, "bin/aapt2");
    }
    public Map<String,String> environment(Context context) {
        Map<String,String> result = new HashMap<>(new TermuxShellEnvironment().getEnvironment(context, false));
        result.put("JAVA_HOME", javaHome.getPath());
        result.put("ANDROID_HOME", sdk.getPath());
        result.put("ANDROID_SDK_ROOT", sdk.getPath());
        result.put("IDE_AAPT2", aapt2.getPath());
        result.put("IDE_CUSTOM_JAVA", context.getSharedPreferences("termux_ide", Context.MODE_PRIVATE)
            .getString("java_home", "").trim().isEmpty() ? "0" : "1");
        result.put("PATH", javaHome + "/bin:" + prefix + "/bin:/system/bin");
        result.put("GRADLE_USER_HOME", new File(home, ".gradle").getPath());
        result.remove("LD_LIBRARY_PATH");
        result.put("TERM", "dumb");
        return result;
    }
    public String describe() {
        return "JAVA_HOME: " + javaHome + (new File(javaHome, "bin/java").canExecute() ? " ✓" : " ✗")
            + "\nSDK: " + sdk + (new File(sdk, "platforms/android-34/android.jar").isFile() ? " ✓ API 34" : " ✗ API 34")
            + "\nAAPT2: " + aapt2 + (aapt2.canExecute() ? " ✓" : " ✗")
            + "\nClang: " + new File(prefix, "bin/clang").canExecute()
            + "\nCMake: " + new File(prefix, "bin/cmake").canExecute()
            + "\nNinja: " + new File(prefix, "bin/ninja").canExecute();
    }
    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
