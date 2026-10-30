package com.termux.ui.desktop.archive;

import java.util.Arrays;
import java.util.List;

/** runtime-v1: keep mountpoint directory nodes, never their mounted/ephemeral contents. */
final class DesktopArchiveRuntimeExclusions {
    private static final List<String> FILE_PLACEHOLDERS=Arrays.asList(
        "rootfs/linkerconfig/ld.config.txt", "rootfs/linkerconfig/com.android.art/ld.config.txt",
        "rootfs/plat_property_contexts", "rootfs/property_contexts");
    private static final List<String> ROOTS=Arrays.asList(
        "shm", "rootfs/proc", "rootfs/sys", "rootfs/dev", "rootfs/run", "rootfs/tmp",
        "rootfs/mnt/sdcard", "rootfs/sdcard", "rootfs/storage/emulated/0",
        "rootfs/storage/self/primary", "rootfs/data/app", "rootfs/data/dalvik-cache",
        "rootfs/data/misc/apexdata/com.android.art/dalvik-cache", "rootfs/apex", "rootfs/odm",
        "rootfs/product", "rootfs/system", "rootfs/system_ext", "rootfs/vendor");
    static boolean root(String relative,String appPackage) {
        if (ROOTS.contains(relative)) return true;
        for (String base:Arrays.asList("rootfs/data/data/", "rootfs/data/user/0/")) {
            String app=base+appPackage;
            if (relative.equals(app+"/cache") || relative.equals(app+"/files/apps")
                    || relative.equals(app+"/files/home") || relative.equals(app+"/files/usr")) return true;
        }
        return false;
    }
    static boolean filePlaceholder(String relative) { return FILE_PLACEHOLDERS.contains(relative); }
    static boolean content(String relative,String appPackage) {
        int slash=relative.indexOf('/');
        while (slash>=0) {
            if (root(relative.substring(0,slash),appPackage)) return true;
            slash=relative.indexOf('/',slash+1);
        }
        return false;
    }
}
