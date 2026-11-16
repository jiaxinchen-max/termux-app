package com.termux.ui.desktop.runtime;

import com.termux.usb.UsbStorageWritePolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsbStorageWritePolicyTest {
    @Test public void onlyExplicitlyUnmountedOrRemovedVolumesAllowRawWrite() {
        assertTrue(UsbStorageWritePolicy.isUnmounted("unmounted"));
        assertTrue(UsbStorageWritePolicy.isUnmounted("removed"));
        for (String state : new String[] {null, "", "unknown", "mounted", "mounted_ro", "checking",
                "ejecting", "bad_removal", "unmountable", "shared", "nofs"})
            assertFalse(String.valueOf(state), UsbStorageWritePolicy.isUnmounted(state));
    }
}
