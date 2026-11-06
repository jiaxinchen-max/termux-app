package com.termux.ui.wine.domain;

import java.util.List;

/** Prepared configuration and exact immutable dependencies; independent of active pointers. */
public final class ProotBindingSnapshot {
    private final String winePackage;
    private final String dxWrapper;
    private final RuntimeTranslator translator;
    private final String box64ComponentId;
    private final String recipeSha256;
    private final List<ProotComponentBinding> bindings;

    public ProotBindingSnapshot(String winePackage, String dxWrapper, RuntimeTranslator translator,
                                String box64ComponentId, String recipeSha256,
                                List<ProotComponentBinding> bindings) {
        if (winePackage == null || !winePackage.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") ||
            dxWrapper == null || !dxWrapper.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") ||
            translator == null || box64ComponentId == null ||
            !box64ComponentId.matches("(?:[A-Za-z0-9][A-Za-z0-9._-]{0,127})?") ||
            recipeSha256 == null || !recipeSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid_proot_snapshot");
        }
        if ((winePackage.equals("rootfs_hangover_11.16") && translator != RuntimeTranslator.HANGOVER) ||
            ((winePackage.equals("rootfs_wine_10.0") || winePackage.equals("rootfs_proton_11.0")) &&
                translator != RuntimeTranslator.BOX64)) {
            throw invalid();
        }
        this.bindings = ProotComponentBinding.immutableBindings(bindings);
        this.winePackage = winePackage;
        this.dxWrapper = dxWrapper;
        this.translator = translator;
        this.box64ComponentId = box64ComponentId;
        this.recipeSha256 = recipeSha256;
        String expectedBox64 = box64ComponentId.isEmpty() ? "rootfs_box64_0.4.5" : box64ComponentId;
        boolean wine = winePackage.equals("rootfs_hangover_11.16");
        boolean dx = dxWrapper.equals("rootfs-wined3d");
        boolean box64 = translator != RuntimeTranslator.BOX64;
        for (ProotComponentBinding b : this.bindings) {
            switch (b.getRole()) {
                case WINE:
                    if (wine || !winePackage.equals(b.getComponentId())) throw invalid();
                    wine = true;
                    break;
                case BOX64:
                    if (box64 || !expectedBox64.equals(b.getComponentId())) throw invalid();
                    box64 = true;
                    break;
                default:
                    if (dxWrapper.equals("rootfs-wined3d") || !dxWrapper.equals(b.getComponentId())) throw invalid();
                    dx = true;
            }
        }
        if (!wine || !dx || !box64 || (translator != RuntimeTranslator.BOX64 && !box64ComponentId.isEmpty())) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("proot_snapshot_dependency_mismatch");
    }
    public String getWinePackage() { return winePackage; }
    public String getDxWrapper() { return dxWrapper; }
    public RuntimeTranslator getTranslator() { return translator; }
    public String getBox64ComponentId() { return box64ComponentId; }
    public String getRecipeSha256() { return recipeSha256; }
    public List<ProotComponentBinding> getBindings() { return bindings; }
}
