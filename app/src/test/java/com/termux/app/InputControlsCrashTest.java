package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.termux.x11.controller.ControlsEditorActivity;
import com.termux.x11.controller.ExternalControllerBindingsActivity;
import com.termux.x11.controller.IconPackManagerActivity;
import com.termux.x11.controller.ImageFilePickerActivity;
import com.termux.x11.controller.InputControllerActivity;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.InputControlsManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 移植后的虚拟按键相关页面冒烟测试。
 * 主要目的是捕捉「主题属性缺失 / 空指针 / 强转」这类只在运行时才暴露的闪退。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public class InputControlsCrashTest {

    private int profileId;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        InputControlsManager manager = new InputControlsManager(context);
        // createProfile 依赖内部 profiles 列表已初始化，必须先 getProfiles() 加载一次
        manager.getProfiles();
        ControlsProfile profile = manager.createProfile("smoke-test-profile");
        profileId = profile.id;
    }

    private Intent intentFor(Class<?> cls) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), cls);
        intent.putExtra("profile_id", profileId);
        intent.putExtra("selected_profile_id", profileId);
        intent.putExtra("edit_input_controls", true);
        intent.putExtra("controller_id", "smoke-test-controller");
        return intent;
    }

    private void launch(Class<? extends Activity> cls, String tag) {
        try {
            Robolectric.buildActivity(cls, intentFor(cls)).setup().get();
            System.out.println("SMOKE_PASS " + tag);
        } catch (Throwable t) {
            System.out.println("SMOKE_FAIL " + tag);
            t.printStackTrace(System.out);
            Assert.fail(tag + " 启动失败: " + t);
        }
    }

    @Test
    public void inputControllerActivity() {
        launch(InputControllerActivity.class, "InputControllerActivity(键盘手柄/输入控制)");
    }

    @Test
    public void controlsEditorActivity() {
        launch(ControlsEditorActivity.class, "ControlsEditorActivity(控制器编辑器)");
    }

    @Test
    public void externalControllerBindingsActivity() {
        launch(ExternalControllerBindingsActivity.class, "ExternalControllerBindingsActivity(手柄按键绑定)");
    }

    @Test
    public void iconPackManagerActivity() {
        launch(IconPackManagerActivity.class, "IconPackManagerActivity(图标包管理)");
    }

    @Test
    public void imageFilePickerActivity() {
        launch(ImageFilePickerActivity.class, "ImageFilePickerActivity(图片选择)");
    }
}
