package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.WorkspaceFactory;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceCloseCallback;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceInstance;
import com.aresstack.askai.plugin.api.ui.WorkspaceLayoutContribution;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The controller isolates plugin failures by phase and answers a close request at most once. */
public class WorkspaceLifecycleControllerTest {

    private static WorkspaceCreationRequest request() {
        return new WorkspaceCreationRequest("ws-1", "", Collections.<String, String>emptyMap());
    }

    private static WorkspaceLifecycleController open(WorkspaceFactory factory) {
        WorkspaceLifecycleController.Result result =
                WorkspaceLifecycleController.open("com.x.a", factory, request(), null);
        assertTrue("expected successful open", result.isSuccess());
        return result.getController();
    }

    @Test
    public void fullLifecycleTransitionsStates() {
        FakeInstance instance = new FakeInstance();
        WorkspaceLifecycleController controller = open((req, ctx) -> instance);
        assertEquals(WorkspaceInstanceState.CREATED, controller.getState());

        controller.activate();
        assertEquals(WorkspaceInstanceState.ACTIVE, controller.getState());

        controller.deactivate();
        assertEquals(WorkspaceInstanceState.INACTIVE, controller.getState());

        controller.dispose();
        assertEquals(WorkspaceInstanceState.DISPOSED, controller.getState());
        assertEquals(1, instance.disposeCalls);

        controller.dispose(); // idempotent
        assertEquals(1, instance.disposeCalls);
    }

    @Test
    public void creationFailureIsClassified() {
        WorkspaceLifecycleController.Result result = WorkspaceLifecycleController.open(
                "com.x.a", (req, ctx) -> {
                    throw new IllegalStateException("boom");
                }, request(), null);
        assertFalse(result.isSuccess());
        assertNull(result.getController());
        assertEquals(PluginFailurePhase.WORKSPACE_CREATION, result.getFailure().getPhase());
    }

    @Test
    public void activationFailureMovesToFailed() {
        FakeInstance instance = new FakeInstance();
        instance.failOnActivate = true;
        WorkspaceLifecycleController controller = open((req, ctx) -> instance);

        controller.activate();
        assertEquals(WorkspaceInstanceState.FAILED, controller.getState());
        assertEquals(PluginFailurePhase.WORKSPACE_ACTIVATION, controller.getLastFailure().getPhase());
    }

    @Test
    public void closeCallbackIsAnsweredOnlyOnce() {
        FakeInstance instance = new FakeInstance();
        instance.answerBothWays = true; // plugin misbehaves: calls allow() then veto()
        WorkspaceLifecycleController controller = open((req, ctx) -> instance);

        RecordingCallback callback = new RecordingCallback();
        controller.requestClose(callback);

        assertEquals(1, callback.allowCalls);
        assertEquals(0, callback.vetoCalls);
    }

    private static final class RecordingCallback implements WorkspaceCloseCallback {
        int allowCalls;
        int vetoCalls;

        @Override
        public void allowClose() {
            allowCalls++;
        }

        @Override
        public void vetoClose(String reason) {
            vetoCalls++;
        }
    }

    private static final class FakeInstance implements WorkspaceInstance {
        boolean failOnActivate;
        boolean answerBothWays;
        int disposeCalls;

        @Override
        public WorkspaceLayoutContribution getLayout() {
            return null;
        }

        @Override
        public void activate() {
            if (failOnActivate) {
                throw new IllegalStateException("activate boom");
            }
        }

        @Override
        public void deactivate() {
        }

        @Override
        public boolean isDirty() {
            return false;
        }

        @Override
        public void requestClose(WorkspaceCloseCallback callback) {
            callback.allowClose();
            if (answerBothWays) {
                callback.vetoClose("late veto must be ignored");
            }
        }

        @Override
        public void dispose() {
            disposeCalls++;
        }
    }
}
