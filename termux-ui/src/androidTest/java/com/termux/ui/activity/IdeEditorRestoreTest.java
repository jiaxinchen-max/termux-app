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
            openProject(scenario, project.getName()); clickRow(scenario, "Demo.java");
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
    @Test public void multipleDraftsAndCursorSurviveRotationAndLogPanel() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean wasEnabled = IdePreference.isEnabled(context);
        IdePreference.setEnabled(context, true);
        IdeWorkspace workspace = new IdeWorkspace(new File(context.getFilesDir(), "home/projects"));
        File project = workspace.project("tabs-" + UUID.randomUUID()); assertTrue(project.mkdir());
        workspace.write(new File(project, "A.java"), "class A {}\n");
        workspace.write(new File(project, "B.java"), "class B {}\n");
        try (ActivityScenario<IdeActivity> scenario = ActivityScenario.launch(new Intent(context, IdeActivity.class))) {
            openProject(scenario, project.getName()); clickRow(scenario, "A.java");
            await(scenario, activity -> {
                EditText input = editor(activity.findViewById(android.R.id.content));
                if (input == null || !input.getText().toString().contains("class A")) return false;
                input.setText("class A { int draft = 1; }\n"); input.setSelection(8); return true;
            });
            clickRow(scenario, "B.java");
            await(scenario, activity -> {
                EditText input = editor(activity.findViewById(android.R.id.content));
                if (input == null || !input.getText().toString().contains("class B")) return false;
                input.setText("class B { int draft = 2; }\n"); return true;
            });
            scenario.recreate();
            await(scenario, activity -> {
                EditText input = editor(activity.findViewById(android.R.id.content));
                return input != null && input.getText().toString().contains("draft = 2");
            });
            clickRow(scenario, "A.java •");
            await(scenario, activity -> {
                EditText input = editor(activity.findViewById(android.R.id.content));
                return input != null && input.getText().toString().contains("draft = 1") && input.getSelectionStart() == 8;
            });
            scenario.onActivity(activity -> {
                EditText before = editor(activity.findViewById(android.R.id.content));
                assertTrue(click(activity.findViewById(android.R.id.content), activity.getString(com.termux.ui.R.string.ide_task)));
                assertSame(before, editor(activity.findViewById(android.R.id.content)));
                assertTrue(click(activity.findViewById(android.R.id.content), activity.getString(com.termux.ui.R.string.ide_hide_log)));
                assertSame(before, editor(activity.findViewById(android.R.id.content)));
            });
            try (BufferedReader input = new BufferedReader(new FileReader(new File(project, "A.java")))) {
                assertEquals("class A {}", input.readLine());
            }
            try (BufferedReader input = new BufferedReader(new FileReader(new File(project, "B.java")))) {
                assertEquals("class B {}", input.readLine());
            }
        } finally { IdePreference.setEnabled(context, wasEnabled); workspace.deleteProject(project); }
    }
    private void openProject(ActivityScenario<IdeActivity> scenario, String name) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        awaitWelcome(scenario);
        clickRow(scenario, context.getString(com.termux.ui.R.string.ide_open_project));
        androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo(name))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.click());
    }
    @Test public void toolsBackReturnsToWelcomeAfterRotation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean wasEnabled = IdePreference.isEnabled(context);
        IdePreference.setEnabled(context, true);
        try (ActivityScenario<IdeActivity> scenario = ActivityScenario.launch(new Intent(context, IdeActivity.class))) {
            awaitWelcome(scenario);
            clickRow(scenario, context.getString(com.termux.ui.R.string.ide_tools));
            awaitTools(scenario);
            scenario.recreate();
            awaitTools(scenario);
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            clickRow(scenario, context.getString(com.termux.ui.R.string.ide_new_project));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint(com.termux.ui.R.string.ide_project_name))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.cancel))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click());
        } finally { IdePreference.setEnabled(context, wasEnabled); }
    }
    @Test public void toolsBackPreservesWorkspaceDraftAfterRotation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean wasEnabled = IdePreference.isEnabled(context);
        IdePreference.setEnabled(context, true);
        IdeWorkspace workspace = new IdeWorkspace(new File(context.getFilesDir(), "home/projects"));
        File project = workspace.project("tools-back-" + UUID.randomUUID()); assertTrue(project.mkdir());
        workspace.write(new File(project, "Demo.java"), "class Demo {}\n");
        try (ActivityScenario<IdeActivity> scenario = ActivityScenario.launch(new Intent(context, IdeActivity.class))) {
            openProject(scenario, project.getName()); clickRow(scenario, "Demo.java");
            await(scenario, activity -> {
                EditText input = editor(activity.findViewById(android.R.id.content));
                if (input == null) return false;
                input.setText("class Demo { int draft = 42; }\n"); return true;
            });
            clickRow(scenario, context.getString(com.termux.ui.R.string.ide_more));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(com.termux.ui.R.string.ide_tools))
                .perform(androidx.test.espresso.action.ViewActions.click());
            awaitTools(scenario);
            scenario.recreate();
            awaitTools(scenario);
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            await(scenario, activity -> {
                EditText input = editor(activity.findViewById(android.R.id.content));
                return input != null && input.getText().toString().contains("draft = 42");
            });
            try (BufferedReader input = new BufferedReader(new FileReader(new File(project, "Demo.java")))) {
                assertEquals("class Demo {}", input.readLine());
            }
        } finally { IdePreference.setEnabled(context, wasEnabled); workspace.deleteProject(project); }
    }
    private void awaitTools(ActivityScenario<IdeActivity> scenario) {
        await(scenario, activity -> {
            java.util.ArrayList<View> paths = new java.util.ArrayList<>();
            activity.findViewById(android.R.id.content).findViewsWithText(paths,
                activity.getString(com.termux.ui.R.string.ide_tool_paths), View.FIND_VIEWS_WITH_TEXT);
            return !paths.isEmpty();
        });
    }
    @Test public void welcomeCreateActionOpensExistingProjectForm() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean wasEnabled = IdePreference.isEnabled(context);
        IdePreference.setEnabled(context, true);
        try (ActivityScenario<IdeActivity> scenario = ActivityScenario.launch(new Intent(context, IdeActivity.class))) {
            awaitWelcome(scenario);
            clickRow(scenario, context.getString(com.termux.ui.R.string.ide_new_project));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint(com.termux.ui.R.string.ide_project_name))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withHint(com.termux.ui.R.string.ide_application_id))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.cancel))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click());
        } finally { IdePreference.setEnabled(context, wasEnabled); }
    }
    private void awaitWelcome(ActivityScenario<IdeActivity> scenario) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        await(scenario, activity -> {
            java.util.ArrayList<View> loading = new java.util.ArrayList<>();
            activity.findViewById(android.R.id.content).findViewsWithText(loading,
                context.getString(com.termux.ui.R.string.ide_loading_projects), View.FIND_VIEWS_WITH_TEXT);
            return loading.isEmpty();
        });
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
        if (view instanceof TextView && (label.contentEquals(((TextView)view).getText()) || label.contentEquals(view.getContentDescription()))) {
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
