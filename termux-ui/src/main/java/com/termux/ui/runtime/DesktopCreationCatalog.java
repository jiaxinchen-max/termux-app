package com.termux.ui.runtime;

import com.termux.ui.domain.DesktopDe;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopRunMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the create screens offer, given what already exists. Pure: the activities only render it.
 *
 * <p>Native desktops run their compositor directly on the host, so one instance per environment
 * is all that makes sense: created environments leave the option list. Container desktops own a
 * proot container instance each, so the same environment can be created again and again.
 */
public final class DesktopCreationCatalog {

    /** The only proot distro shipped today; more arrive as "coming soon" rows. */
    public static final String DEFAULT_CONTAINER = "debian";

    private DesktopCreationCatalog() {}

    /** Every environment in display order, creatable or not. */
    public static List<DesktopDe> environments() {
        return Collections.unmodifiableList(Arrays.asList(DesktopDe.values()));
    }

    /** Native: creatable environments that no existing native desktop uses yet. */
    public static List<DesktopDe> nativeOptions(List<DesktopDistro> existing) {
        List<DesktopDe> options = new ArrayList<>();
        for (DesktopDe de : DesktopDe.values()) {
            if (!de.isCreatable() || count(existing, null, de) > 0) continue;
            options.add(de);
        }
        return options;
    }

    /** Container: every creatable environment, repeats allowed. */
    public static List<DesktopDe> containerOptions() {
        List<DesktopDe> options = new ArrayList<>();
        for (DesktopDe de : DesktopDe.values()) if (de.isCreatable()) options.add(de);
        return options;
    }

    /** Containers offered today; the rest are rendered as "coming soon". */
    public static List<String> containers() {
        return Collections.singletonList(DEFAULT_CONTAINER);
    }

    /** How many desktops of this environment exist (in {@code container}, or native when null). */
    public static int count(List<DesktopDistro> existing, String container, DesktopDe de) {
        int count = 0;
        for (DesktopDistro distro : existing) {
            if (distro.getDe() != de) continue;
            boolean nativeMatch = container == null
                && distro.getRunMode() == DesktopRunMode.NATIVE;
            boolean containerMatch = container != null
                && distro.getRunMode() == DesktopRunMode.PROOT
                && distro.getProotDistro().startsWith(container + "-");
            if (nativeMatch || containerMatch) count++;
        }
        return count;
    }

    /**
     * The proot-distro alias of the next instance, e.g. {@code debian-xfce4-2}. The rootfs tarball
     * behind it comes from proot-distro's cache, so only the first creation ever downloads.
     */
    public static String nextInstanceAlias(List<DesktopDistro> existing, String container,
                                           DesktopDe de) {
        return container + "-" + de.getStorageValue().replace('_', '-')
            + "-" + (count(existing, container, de) + 1);
    }

    /** The container a instance alias belongs to: {@code debian-xfce4-2} -> {@code debian}. */
    private static String containerBase(String alias) {
        int cut = alias.lastIndexOf('-');
        return cut > 0 ? alias.substring(0, cut) : alias;
    }

    /** "#N" label of an existing container desktop among its container+environment siblings. */
    public static String instanceLabel(DesktopDistro distro, List<DesktopDistro> existing) {
        if (distro.getRunMode() != DesktopRunMode.PROOT) return "";
        int seen = 0;
        List<DesktopDistro> sorted = new ArrayList<>(existing);
        Collections.sort(sorted, (a, b) -> a.getId().compareTo(b.getId()));
        String base = containerBase(distro.getProotDistro());
        for (DesktopDistro other : sorted) {
            if (other.getDe() == distro.getDe()
                && containerBase(other.getProotDistro()).equals(base)) seen++;
            if (other.getId().equals(distro.getId())) break;
        }
        return "#" + Math.max(1, seen);
    }
}
