package com.termux.localgames.activity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TopTabBarHeightTest {

    @Test
    public void liveStatusBarInsetWinsOverTheResource() {
        assertEquals(72, TopTabBarHeight.resolve(72, 63, 54));
    }

    @Test
    public void statusBarResourceIsUsedWhenTheWindowGetsNoInset() {
        // A window fitted between the bars is not given the status bar inset (0).
        assertEquals(63, TopTabBarHeight.resolve(0, 63, 54));
    }

    @Test
    public void unreadableSourcesFallBackToTheDefault() {
        assertEquals(54, TopTabBarHeight.resolve(0, 0, 54));
        assertEquals(54, TopTabBarHeight.resolve(-1, -1, 54));
    }

    @Test
    public void heightFollowsTheDeviceRatherThanAFixedValue() {
        assertEquals(48, TopTabBarHeight.resolve(0, 48, 54));
        assertEquals(96, TopTabBarHeight.resolve(0, 96, 54));
    }

    @Test
    public void theResultIsNeverZero() {
        assertEquals(1, TopTabBarHeight.resolve(0, 0, 0));
    }

    @Test
    public void aWrongLayoutHeightIsReplacedByTheResolvedOne() {
        // 136 px is what the bar showed when it kept a stale/default height; 74 px the status bar.
        assertTrue(TopTabBarHeight.needsApply(136, TopTabBarHeight.resolve(74, 0, 63)));
    }

    @Test
    public void anAlreadyCorrectHeightIsNotAppliedAgain() {
        assertFalse(TopTabBarHeight.needsApply(74, TopTabBarHeight.resolve(74, 74, 63)));
    }

    @Test
    public void aFreshInsetDispatchCorrectsTheHeightOfAnEarlierResourceFallback() {
        int early = TopTabBarHeight.resolve(0, 70, 63);
        int afterInsets = TopTabBarHeight.resolve(74, 70, 63);
        assertTrue(TopTabBarHeight.needsApply(early, afterInsets));
        assertFalse(TopTabBarHeight.needsApply(afterInsets, TopTabBarHeight.resolve(74, 70, 63)));
    }

    @Test
    public void everyPathResolvingTheSameSourcesEndsAtTheSameHeight() {
        // Library and Settings tabs, cold start and rotation all resolve from the same inputs.
        int library = TopTabBarHeight.resolve(74, 70, 63);
        int settings = TopTabBarHeight.resolve(74, 70, 63);
        assertEquals(library, settings);
    }

    @Test
    public void anUnusableTargetNeverChangesTheBar() {
        assertFalse(TopTabBarHeight.needsApply(74, 0));
        assertFalse(TopTabBarHeight.needsApply(74, -1));
    }

    @Test
    public void aForcedPassAppliesEvenWhenTheHeightLooksUnchanged() {
        // The current value may have been resolved for the previous orientation (136 px portrait
        // height that happens to equal the stale target): visibility/config passes must not skip.
        assertTrue(TopTabBarHeight.needsApply(74, 74, true));
        assertFalse(TopTabBarHeight.needsApply(74, 74, false));
    }

    @Test
    public void aForcedPassStillNeverAppliesAnUnusableTarget() {
        assertFalse(TopTabBarHeight.needsApply(74, 0, true));
        assertFalse(TopTabBarHeight.needsApply(74, -1, true));
    }

    @Test
    public void aPortraitInsetIsNotReusedAfterRotatingToLandscape() {
        // 136 px was dispatched while portrait; the window is landscape now.
        assertEquals(-1, TopTabBarHeight.usableCachedInset(136, false, true));
        assertEquals(-1, TopTabBarHeight.usableCachedInset(74, true, false));
    }

    @Test
    public void anInsetFromTheCurrentOrientationIsUsed() {
        assertEquals(74, TopTabBarHeight.usableCachedInset(74, true, true));
        assertEquals(0, TopTabBarHeight.usableCachedInset(0, true, true));
    }

    @Test
    public void anUnknownCachedInsetIsNotUsable() {
        assertEquals(-1, TopTabBarHeight.usableCachedInset(-1, true, true));
    }

    @Test
    public void rotationToLandscapeResolvesTheLandscapeHeightNotTheCachedPortraitOne() {
        int cached = TopTabBarHeight.usableCachedInset(136, false, true);
        int resolved = TopTabBarHeight.resolve(Math.max(cached, 0), 74, 63);
        assertEquals(74, resolved);
        // The bar still carries the 136 px it was given in portrait: the pass must change it.
        assertTrue(TopTabBarHeight.needsApply(136, resolved, false));
        assertTrue(TopTabBarHeight.needsApply(74, resolved, true));
    }

    @Test
    public void rootInsetsAreOnlyTrustedAfterTheFirstDispatchSinceTheConfigChange() {
        assertFalse(TopTabBarHeight.rootInsetsTrusted(false));
        assertTrue(TopTabBarHeight.rootInsetsTrusted(true));
    }

    @Test
    public void onlyAVisibleBarIsSizedSoItIsSizedWhenItBecomesVisible() {
        assertFalse(TopTabBarHeight.shouldApplyNow(false));
        assertTrue(TopTabBarHeight.shouldApplyNow(true));
    }

    @Test
    public void theOrientationSpecificStatusBarDimenIsPreferred() {
        assertArrayEquals(new String[] {"status_bar_height_landscape", "status_bar_height"},
            TopTabBarHeight.statusBarResourceNames(true));
        assertArrayEquals(new String[] {"status_bar_height_portrait", "status_bar_height"},
            TopTabBarHeight.statusBarResourceNames(false));
    }
}
