package com.termux.ui.desktop.domain;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class DesktopRestoreNamesTest {
    private List<DesktopDistro> inventory(String... names) {
        List<DesktopDistro> result = new ArrayList<>();
        for (String name : names) result.add(new DesktopDistro("d" + result.size(), name,
            DesktopRunMode.PROOT, "c" + result.size(), DesktopDe.XFCE4, DesktopGlMode.SYSTEM,
            DesktopGuestGlMode.DEFAULT, "1280x720", 60, DesktopUsbStorageMode.OFF));
        return result;
    }
    @Test public void keepsUnusedNameVerbatim() {
        assertEquals(" 工作桌面 ", DesktopRestoreNames.choose(" 工作桌面 ", inventory("工作桌面")));
    }
    @Test public void usesSmallestAvailablePositiveSuffix() {
        assertEquals("工作桌面 (2)", DesktopRestoreNames.choose("工作桌面", inventory("工作桌面", "工作桌面 (1)")));
        assertEquals("工作桌面 (1)", DesktopRestoreNames.choose("工作桌面", inventory("工作桌面", "工作桌面 (2)")));
    }
    @Test public void doesNotInterpretExistingSuffixOrPathCharacters() {
        assertEquals("a/\"b (1) (1)", DesktopRestoreNames.choose("a/\"b (1)", inventory("a/\"b (1)")));
    }
    @Test public void repeatedRestoresUseLatestInventory() {
        List<DesktopDistro> current = inventory("桌面");
        String first = DesktopRestoreNames.choose("桌面", current);
        current.addAll(inventory(first));
        assertEquals("桌面 (2)", DesktopRestoreNames.choose("桌面", current));
    }
}
