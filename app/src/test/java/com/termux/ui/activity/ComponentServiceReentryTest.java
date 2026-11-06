package com.termux.ui.activity;

import android.app.Service;
import com.termux.ui.service.ComponentTaskForegroundService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ComponentServiceReentryTest {
    @Test public void reusedServicePromotesBeforeHandlingNewCommand() {
        ServiceController<ComponentTaskForegroundService> controller =
            Robolectric.buildService(ComponentTaskForegroundService.class).create();
        try {
            ComponentTaskForegroundService service = controller.get();
            ShadowService shadow = Shadows.shadowOf(service);
            service.stopForeground(false);
            assertTrue(shadow.isForegroundStopped());
            service.onStartCommand(null, 0, 2);
            assertFalse(shadow.isForegroundStopped());
            assertTrue(shadow.isLastForegroundNotificationAttached());
            assertNotNull(shadow.getLastForegroundNotification());
        } finally {
            controller.destroy();
        }
    }

    @Test @Config(shadows = PendingStartShadow.class)
    public void queuedNewerStartPreventsIdleForegroundDemotion() throws Exception {
        ServiceController<ComponentTaskForegroundService> controller =
            Robolectric.buildService(ComponentTaskForegroundService.class).create();
        try {
            ComponentTaskForegroundService service = controller.get();
            service.onStartCommand(null, 0, 1);
            Field executorField = ComponentTaskForegroundService.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            ((ExecutorService) executorField.get(service)).submit(() -> { }).get(10, TimeUnit.SECONDS);
            Method idle = ComponentTaskForegroundService.class.getDeclaredMethod("stopIfIdle");
            idle.setAccessible(true);
            idle.invoke(service);
            assertFalse(Shadows.shadowOf(service).isForegroundStopped());
        } finally {
            controller.destroy();
        }
    }

    /** AMS rejects this stop because it has already queued a newer start. */
    @Implements(Service.class)
    public static class PendingStartShadow extends ShadowService {
        @Implementation protected boolean stopSelfResult(int startId) { return false; }
    }
}
