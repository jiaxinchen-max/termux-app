package com.termux.ui.activity;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.ui.ide.IdeWorkspace;
import com.termux.ui.platform.IdePreference;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Regression: asynchronous directory loading must not swallow restored unsaved editor state. */
@RunWith(AndroidJUnit4.class)
public class IdeEditorRestoreTest {
    @Test public void unsavedTextSurvivesActivityRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean wasEnabled = IdePreference.isEnabled(context);
        IdePreference.setEnabled(context, true);
        IdeWorkspace workspace = new IdeWorkspace(new File(context.getFilesDir(), "home/projects"));
        File project = workspace.project("restore-" + UUID.randomUUID()); assertTrue(project.mkdir());
        workspace.write(new File(project, "Demo.java"), "class Demo {}\n");
        try (ActivityScenario<IdeActivity> scenario = ActivityScenario.launch(new Intent(context, IdeActivity.class))) {
            clickRow(scenario, project.getName()); clickRow(scenario, "Demo.java");
            await(scenario, activity -> {
                EditText editor = editor(activity.findViewById(android.R.id.content));
                if (editor == null) return false;
                editor.setText("class Demo { int unsaved = 42; }\n"); return true;
            });
            scenario.recreate();
            await(scenario, activity -> {
                EditText editor = editor(activity.findViewById(android.R.id.content));
                return editor != null && editor.getText().toString().contains("unsaved = 42");
            });
            try (java.io.BufferedReader input = new java.io.BufferedReader(new FileReader(new File(project, "Demo.java")))) {
                assertEquals("class Demo {}", input.readLine());
            }
        } finally { IdePreference.setEnabled(context, wasEnabled); workspace.deleteProject(project); }
    }
    private interface Condition { boolean matches(IdeActivity activity); }
    private void await(ActivityScenario<IdeActivity> scenario, Condition condition) {
        for (int i = 0; i < 60; i++) {
            AtomicBoolean matched = new AtomicBoolean();
            scenario.onActivity(activity -> matched.set(condition.matches(activity)));
            if (matched.get()) return;
            SystemClock.sleep(100);
        }
        fail("IDE did not reach the expected UI state");
    }
    private void clickRow(ActivityScenario<IdeActivity> scenario, String label) {
        await(scenario, activity -> click(activity.findViewById(android.R.id.content), label));
    }
    private boolean click(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView)view).getText())) {
            View target = view;
            while (!target.isClickable() && target.getParent() instanceof View) target = (View)target.getParent();
            return target.performClick();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) if (click(group.getChildAt(i), label)) return true;
        }
        return false;
    }
    private EditText editor(View view) {
        if (view instanceof EditText) return (EditText)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) { EditText found = editor(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
}
