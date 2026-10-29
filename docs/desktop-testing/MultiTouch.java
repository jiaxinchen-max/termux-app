import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Two-finger gestures for a phone without root: injects multi-pointer MotionEvents through the
 * InputManager, the way the platform `input` command and scrcpy do. Run as the shell user:
 *   CLASSPATH=/data/local/tmp/mt.jar app_process / MultiTouch tap2 x1 y1 x2 y2 [holdMs]
 *   CLASSPATH=/data/local/tmp/mt.jar app_process / MultiTouch tap3 x1 y1 x2 y2 x3 y3 [holdMs]
 *   CLASSPATH=/data/local/tmp/mt.jar app_process / MultiTouch swipe2 x1 y1 x2 y2 dx dy [durationMs]
 */
public final class MultiTouch {
    private static Object inputManager;
    private static Method inject;

    private static void init() throws Exception {
        Throwable first = null;
        for (String name : new String[] {"android.hardware.input.InputManagerGlobal",
                                         "android.hardware.input.InputManager"}) {
            try {
                Class<?> type = Class.forName(name);
                inputManager = type.getMethod("getInstance").invoke(null);
                inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
                return;
            } catch (Throwable error) {
                if (first == null) first = error;
            }
        }
        throw new Exception("no InputManager found", first);
    }

    private static MotionEvent event(long down, int action, float[][] points, int count) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0];
            coords[i].y = points[i][1];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static void send(MotionEvent e) throws Exception {
        boolean ok = (Boolean) inject.invoke(inputManager, e, 0);
        e.recycle();
        if (!ok) System.err.println("injectInputEvent refused an event");
    }

    private static final int DOWN = MotionEvent.ACTION_DOWN;
    private static final int UP = MotionEvent.ACTION_UP;
    private static final int MOVE = MotionEvent.ACTION_MOVE;
    private static final int POINTER_DOWN = MotionEvent.ACTION_POINTER_DOWN
        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    private static final int POINTER_UP = MotionEvent.ACTION_POINTER_UP
        | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);

    private static void tap3(String[] args) throws Exception {
        float[][] p = new float[3][];
        for (int i = 0; i < 3; i++) p[i] = new float[] {f(args[1 + 2 * i]), f(args[2 + 2 * i])};
        long down = SystemClock.uptimeMillis();
        send(event(down, DOWN, p, 1));
        send(event(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), p, 2));
        send(event(down, MotionEvent.ACTION_POINTER_DOWN | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), p, 3));
        Thread.sleep(args.length > 7 ? Long.parseLong(args[7]) : 60);
        send(event(down, MotionEvent.ACTION_POINTER_UP | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), p, 3));
        send(event(down, POINTER_UP, p, 2));
        send(event(down, UP, p, 1));
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 7 && args[0].equals("tap3")) {
            init();
            tap3(args);
            return;
        }
        if (args.length < 5) {
            System.err.println("usage: tap2 x1 y1 x2 y2 [holdMs] | swipe2 x1 y1 x2 y2 dx dy [durationMs]");
            System.exit(2);
        }
        init();
        float[][] p = {{f(args[1]), f(args[2])}, {f(args[3]), f(args[4])}};
        long down = SystemClock.uptimeMillis();
        send(event(down, DOWN, p, 1));
        send(event(down, POINTER_DOWN, p, 2));
        if (args[0].equals("tap2")) {
            Thread.sleep(args.length > 5 ? Long.parseLong(args[5]) : 60);
        } else if (args[0].equals("swipe2")) {
            float dx = f(args[5]), dy = f(args[6]);
            long duration = args.length > 7 ? Long.parseLong(args[7]) : 400;
            int steps = Math.max(8, (int) (duration / 16));
            for (int s = 1; s <= steps; s++) {
                float t = s / (float) steps;
                float[][] q = {{p[0][0] + dx * t, p[0][1] + dy * t}, {p[1][0] + dx * t, p[1][1] + dy * t}};
                send(event(down, MOVE, q, 2));
                Thread.sleep(Math.max(1, duration / steps));
            }
            p[0][0] += dx; p[0][1] += dy; p[1][0] += dx; p[1][1] += dy;
        } else {
            System.err.println("unknown command " + args[0]);
            System.exit(2);
        }
        send(event(down, POINTER_UP, p, 2));
        send(event(down, UP, p, 1));
    }

    private static float f(String s) { return Float.parseFloat(s); }
}
