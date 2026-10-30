import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Multi-pointer UI injection, run as Android shell. The InputManager API is explicit. */
public final class MultiTouch {
    private static Object inputManager;
    private static Method inject;

    private static void init(String api) throws Exception {
        String name;
        if (api.equals("global")) name = "android.hardware.input.InputManagerGlobal";
        else if (api.equals("legacy")) name = "android.hardware.input.InputManager";
        else throw new IllegalArgumentException("input-manager must be global or legacy");
        Class<?> type = Class.forName(name);
        inputManager = type.getMethod("getInstance").invoke(null);
        inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static MotionEvent event(long down, int action, float[][] points, int count) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0]; coords[i].y = points[i][1];
            coords[i].pressure = 1f; coords[i].size = 1f;
        }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static void send(MotionEvent e) throws Exception {
        try {
            // WAIT_FOR_FINISH: a refused injection is a failed step, never a reported success.
            if (!(Boolean) inject.invoke(inputManager, e, 2))
                throw new IllegalStateException("injectInputEvent refused an event");
        } finally { e.recycle(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].startsWith("--input-manager="))
            throw new IllegalArgumentException("usage: --input-manager=global|legacy tap2|tap3|swipe2 coordinates [ms]");
        String api = args[0].substring("--input-manager=".length());
        args = Arrays.copyOfRange(args, 1, args.length);
        String command = args[0];
        int count;
        int required;
        if (command.equals("tap2")) { count = 2; required = 5; }
        else if (command.equals("tap3")) { count = 3; required = 7; }
        else if (command.equals("swipe2")) { count = 2; required = 7; }
        else throw new IllegalArgumentException("unknown command: " + command);
        if (args.length != required && args.length != required + 1)
            throw new IllegalArgumentException("invalid coordinate/duration argument count");
        float[][] points = new float[count][2];
        for (int i = 0; i < count; i++) {
            points[i][0] = finite(args[1 + 2 * i]); points[i][1] = finite(args[2 + 2 * i]);
        }
        float dx = command.equals("swipe2") ? finite(args[5]) : 0;
        float dy = command.equals("swipe2") ? finite(args[6]) : 0;
        long duration = args.length > required ? Long.parseLong(args[required]) : (command.equals("swipe2") ? 400 : 60);
        if (duration < 1 || duration > 60000) throw new IllegalArgumentException("duration must be 1..60000 ms");
        init(api); // No event is sent before every argument and the selected API are validated.
        long down = SystemClock.uptimeMillis();
        int activeCount = 0;
        try {
            send(event(down, MotionEvent.ACTION_DOWN, points, 1)); activeCount = 1;
            for (int n = 2; n <= count; n++) {
                send(event(down, MotionEvent.ACTION_POINTER_DOWN | ((n - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT), points, n));
                activeCount = n;
            }
            if (command.equals("swipe2")) {
                int steps = Math.max(8, (int) (duration / 16));
                for (int step = 1; step <= steps; step++) {
                    float t = step / (float) steps;
                    float[][] moved = {{points[0][0] + dx * t, points[0][1] + dy * t},
                                       {points[1][0] + dx * t, points[1][1] + dy * t}};
                    send(event(down, MotionEvent.ACTION_MOVE, moved, count));
                    Thread.sleep(Math.max(1, duration / steps));
                }
                for (float[] point : points) { point[0] += dx; point[1] += dy; }
            } else Thread.sleep(duration);
            for (int n = count; n >= 2; n--) {
                send(event(down, MotionEvent.ACTION_POINTER_UP | ((n - 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT), points, n));
                activeCount = n - 1;
            }
            send(event(down, MotionEvent.ACTION_UP, points, 1)); activeCount = 0;
        } finally {
            // Release this tool's unfinished sequence on failure; do not try another injection API.
            if (activeCount > 0) send(event(down, MotionEvent.ACTION_CANCEL, points, activeCount));
        }
    }
    private static float finite(String text) {
        float value = Float.parseFloat(text);
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("non-finite coordinate");
        return value;
    }
}
