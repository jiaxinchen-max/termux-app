package com.termux.render;

public class NativeLib {

    // Used to load the 'wayland' library on application startup.
    static {
        System.loadLibrary("termux-render");
    }

    /**
     * A native method that is implemented by the 'wayland' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
