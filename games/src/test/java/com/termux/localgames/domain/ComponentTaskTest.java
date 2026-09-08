package com.termux.localgames.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ComponentTaskTest {

    private static final String SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void pausedDownloadCanResumeWithoutChangingIdentity() {
        ComponentTask queued = ComponentTask.queued("component-task-1", "wine", 9,
            "https://example.invalid/wine.tar.xz", 100, SHA256);
        ComponentTask downloading = queued.transition(ComponentTaskState.DOWNLOADING,
            40, "etag-1", "Wed, 27 Aug 2026 00:00:00 GMT", "", "");
        ComponentTask paused = downloading.transition(ComponentTaskState.PAUSED,
            40, downloading.getEtag(), downloading.getLastModified(), "", "");
        ComponentTask resumed = paused.transition(ComponentTaskState.DOWNLOADING,
            40, paused.getEtag(), paused.getLastModified(), "", "");

        assertEquals("component-task-1", resumed.getTaskId());
        assertEquals(40, resumed.getDownloadedBytes());
        assertEquals(ComponentTaskState.QUEUED, queued.getState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void downloadedBytesCannotExceedExpectedSize() {
        new ComponentTask("component-task-1", "wine", 9,
            "https://example.invalid/wine.tar.xz", 100, SHA256, 101,
            "", "", ComponentTaskState.DOWNLOADING, "", "");
    }

    @Test(expected = IllegalStateException.class)
    public void installedTaskCannotTransition() {
        ComponentTask task = new ComponentTask("component-task-1", "wine", 9,
            "https://example.invalid/wine.tar.xz", 100, SHA256, 100,
            "etag-1", "", ComponentTaskState.INSTALLED, "", "");

        task.transition(ComponentTaskState.DOWNLOADING, 100,
            task.getEtag(), task.getLastModified(), "", "");
    }
}
