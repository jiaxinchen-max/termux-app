package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.LaunchEvent;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class LaunchEventPipelineTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void parsesAndAppliesOrderedEvent() throws Exception {
        LaunchEvent event = new LaunchEventJsonParser().parse(json(1, "RUNNING", "PRECHECK", 5));
        LaunchTask queued = LaunchTask.queued("task-1", "game-1", 100)
            .withEnvironmentFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        LaunchTask running = new LaunchTaskStateMachine().apply(queued, event);

        assertEquals(LaunchStage.PRECHECK, running.getStage());
        assertEquals(1, running.getLastEventSequence());
        assertEquals(123000, running.getUpdatedAt());
        assertEquals(321, running.getPid());
        assertSame(running, new LaunchTaskStateMachine().apply(running, event));
    }

    @Test(expected = IllegalArgumentException.class)
    public void scriptCannotClaimRunningBeforeFirstFrameIntegration() throws Exception {
        LaunchTask task = LaunchTask.queued("task-1", "game-1", 100);
        new LaunchTaskStateMachine().apply(task,
            new LaunchEventJsonParser().parse(json(1, "RUNNING", "RUNNING", 95)));
    }

    @Test
    public void leavesPartialTailForNextReplay() throws Exception {
        File file = temporary.newFile("events.jsonl");
        String first = json(1, "RUNNING", "PRECHECK", 5);
        String partial = json(2, "RUNNING", "PREPARING_PREFIX", 25);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write((first + "\n" + partial.substring(0, partial.length() - 4))
                .getBytes(StandardCharsets.UTF_8));
        }

        List<LaunchEvent> events = new LaunchEventLogReader().readAfter(file, 0);

        assertEquals(1, events.size());
        assertEquals(1, events.get(0).getSequence());
    }

    @Test
    public void parserRejectsDuplicateOrUnknownJsonFields() {
        try {
            new LaunchEventJsonParser().parse(json(1, "RUNNING", "PRECHECK", 5)
                .replace("\"logRef\":\"log\"", "\"logRef\":\"log\",\"extra\":1"));
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("fields"));
            return;
        }
        throw new AssertionError("unknown event field accepted");
    }

    @Test
    public void connectionAloneDoesNotClaimRunning() {
        LaunchTask waiting = LaunchTask.queued("task-1", "game-1", 100)
            .transition(LaunchTaskState.RUNNING, LaunchStage.WAITING_FIRST_FRAME, 90,
                321, null, "", false, "log");
        LaunchTask connected = new LaunchTaskStateMachine()
            .observeDisplayConnection(waiting, true, 110);

        assertEquals(LaunchStage.WAITING_FIRST_FRAME, connected.getStage());
        assertEquals(0, connected.getFirstFrameAt());
    }

    @Test
    public void trustedFirstFramePromotesWaitingTaskWithoutConsumingJsonSequence() {
        LaunchTask waiting = LaunchTask.queued("task-1", "game-1", 100)
            .transition(LaunchTaskState.RUNNING, LaunchStage.WAITING_FIRST_FRAME, 90,
                321, null, "", false, "log");
        LaunchTaskStateMachine machine = new LaunchTaskStateMachine();
        LaunchTask connected = machine.observeDisplayConnection(waiting, true, 110);
        LaunchTask running = machine.confirmFirstFrame(connected, 120);

        assertEquals(LaunchStage.RUNNING, running.getStage());
        assertEquals(95, running.getProgress());
        assertEquals(0, running.getLastEventSequence());
        assertEquals(120, running.getFirstFrameAt());
    }

    @Test
    public void earlyFirstFramePromotesWhenWaitingEventArrives() throws Exception {
        LaunchTask task = LaunchTask.queued("task-1", "game-1", 100)
            .withEnvironmentFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        LaunchTaskStateMachine machine = new LaunchTaskStateMachine();
        task = machine.apply(task, new LaunchEventJsonParser().parse(
            json(1, "RUNNING", "STARTING_DISPLAY", 45)));
        task = machine.confirmFirstFrame(task, 123100);
        task = machine.apply(task, new LaunchEventJsonParser().parse(
            json(2, "RUNNING", "WAITING_FIRST_FRAME", 90)));

        assertEquals(LaunchStage.RUNNING, task.getStage());
        assertEquals(2, task.getLastEventSequence());
    }

    @Test
    public void terminalTaskIgnoresLateDisplayEvidence() {
        LaunchTask terminal = LaunchTask.queued("task-1", "game-1", 100)
            .transition(LaunchTaskState.CANCELLED, LaunchStage.COMPLETE, 100,
                -1, null, "", false, "");
        LaunchTaskStateMachine machine = new LaunchTaskStateMachine();
        assertSame(terminal, machine.observeDisplayConnection(terminal, true, 110));
        assertSame(terminal, machine.confirmFirstFrame(terminal, 110));
    }

    private static String json(long sequence, String state, String stage, int progress) {
        return "{\"schemaVersion\":1,\"taskId\":\"task-1\",\"sequence\":" + sequence +
            ",\"state\":\"" + state + "\",\"stage\":\"" + stage +
            "\",\"progress\":" + progress +
            ",\"timestamp\":123000,\"pid\":321,\"exitCode\":null," +
            "\"errorCode\":\"\",\"recoverable\":false,\"message\":\"ok\"," +
            "\"logRef\":\"log\"}";
    }
}
