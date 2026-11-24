package com.termux.ui.ide;

import java.io.*;

/** Small Java/Android project; Gradle Wrapper and AGP versions are deliberately paired. */
public final class IdeTemplate {
    public static final String GRADLE_SHA256 = "544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d";
    public static File create(IdeWorkspace ws, String name, String applicationId, InputStream wrapper, InputStream license) throws IOException {
        if (!applicationId.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) throw new IOException("Invalid application ID");
        for (String part : applicationId.split("\\."))
            if (part.matches("abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|true|false|null|_"))
                throw new IOException("Application ID contains a Java keyword");
        File target = ws.project(name);
        if (target.exists()) throw new IOException("Project already exists");
        File staging = new File(ws.root, ".create-" + java.util.UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("Cannot create project");
        try {
            ws.write(new File(staging, "settings.gradle"), "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name = 'AndroidApp'\ninclude ':app'\n");
            ws.write(new File(staging, "build.gradle"), "plugins { id 'com.android.application' version '8.5.2' apply false }\n");
            ws.write(new File(staging, "gradle.properties"), "org.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\norg.gradle.vfs.watch=false\n");
            ws.write(new File(staging, "app/build.gradle"), "plugins { id 'com.android.application' }\nandroid {\n namespace '" + applicationId + "'\n compileSdk 34\n buildToolsVersion '34.0.0'\n defaultConfig { applicationId '" + applicationId + "'; minSdk 21; targetSdk 34; versionCode 1; versionName '1.0' }\n}\n");
            ws.write(new File(staging, "app/src/main/AndroidManifest.xml"), "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:theme=\"@android:style/Theme.Material.Light.NoActionBar\" android:label=\"Android App\"><activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>\n");
            ws.write(new File(staging, "app/src/main/java/" + applicationId.replace('.', '/') + "/MainActivity.java"), "package " + applicationId + ";\n\nimport android.app.Activity;\nimport android.os.Bundle;\n\npublic class MainActivity extends Activity {\n    @Override public void onCreate(Bundle state) {\n        super.onCreate(state);\n        setContentView(R.layout.activity_main);\n    }\n}\n");
            ws.write(new File(staging, "app/src/main/res/layout/activity_main.xml"), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"\n    android:gravity=\"center\" android:text=\"Hello from Termux IDE!\" android:textSize=\"24sp\" />\n");
            ws.write(new File(staging, "gradle/wrapper/gradle-wrapper.properties"), "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip\ndistributionSha256Sum=" + GRADLE_SHA256 + "\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n");
            ws.write(new File(staging, "gradlew"), "#!/data/data/com.termux/files/usr/bin/sh\nAPP_HOME=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\nexec \"${JAVA_HOME}/bin/java\" -classpath \"$APP_HOME/gradle/wrapper/gradle-wrapper.jar\" org.gradle.wrapper.GradleWrapperMain \"$@\"\n");
            File jar = ws.checked(new File(staging, "gradle/wrapper/gradle-wrapper.jar"));
            try (OutputStream out = new FileOutputStream(jar)) { byte[] buffer = new byte[8192]; int n; while ((n = wrapper.read(buffer)) != -1) out.write(buffer, 0, n); }
            try (OutputStream out = new FileOutputStream(new File(staging, "GRADLE-LICENSE.txt"))) {
                byte[] buffer = new byte[8192]; int n; while ((n = license.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            ws.write(new File(staging, ".gitignore"), ".gradle/\n**/build/\nlocal.properties\n.termux-ide.properties\n");
            ws.write(new File(staging, "GRADLE-WRAPPER-NOTICE.txt"), "Gradle Wrapper is copyright Gradle, Inc., licensed under Apache License 2.0.\nhttps://www.apache.org/licenses/LICENSE-2.0\nSource: https://github.com/gradle/gradle/tree/v8.7.0/gradle/wrapper\n");
            ws.markCreated(staging);
            if (!staging.renameTo(target)) throw new IOException("Cannot publish project");
            return target;
        } finally { if (staging.exists()) ws.deleteTree(staging); }
    }
    private IdeTemplate() { }
}
