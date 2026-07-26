package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.WorkspaceFactory;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceCloseCallback;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceInstance;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;

/**
 * Drives one workspace instance through its host-side lifecycle on the EDT and isolates every plugin call:
 * a failure in create/activate/deactivate/dispose is captured as a phase-classified {@link PluginLoadFailure}
 * and moves the controller to {@link WorkspaceInstanceState#FAILED} instead of propagating. Close requests
 * are answered at most once via a {@link GuardedCloseCallback}.
 */
public final class WorkspaceLifecycleController {

    private final String pluginId;
    private final WorkspaceInstance instance;
    private WorkspaceInstanceState state;
    private PluginLoadFailure lastFailure;

    private WorkspaceLifecycleController(String pluginId, WorkspaceInstance instance) {
        this.pluginId = pluginId;
        this.instance = instance;
        this.state = WorkspaceInstanceState.CREATED;
    }

    /**
     * Creates the workspace via the plugin factory, isolating a creation failure. The {@link Result} carries
     * either a live controller or a phase-classified {@link PluginLoadFailure} (WORKSPACE_CREATION).
     */
    public static Result open(String pluginId, WorkspaceFactory factory,
                              WorkspaceCreationRequest request, WorkspaceHostContext context) {
        try {
            WorkspaceInstance instance = factory.createWorkspace(request, context);
            if (instance == null) {
                return Result.failed(failure(pluginId, PluginFailurePhase.WORKSPACE_CREATION,
                        "The plugin returned no workspace.", null));
            }
            return Result.created(new WorkspaceLifecycleController(pluginId, instance));
        } catch (RuntimeException | Error ex) {
            return Result.failed(failure(pluginId, PluginFailurePhase.WORKSPACE_CREATION,
                    "The workspace could not be created.", ex));
        }
    }

    public WorkspaceInstanceState getState() {
        return state;
    }

    public WorkspaceInstance getInstance() {
        return instance;
    }

    public PluginLoadFailure getLastFailure() {
        return lastFailure;
    }

    public void activate() {
        if (state == WorkspaceInstanceState.DISPOSED || state == WorkspaceInstanceState.FAILED) {
            return;
        }
        try {
            instance.activate();
            state = WorkspaceInstanceState.ACTIVE;
        } catch (RuntimeException | Error ex) {
            fail(PluginFailurePhase.WORKSPACE_ACTIVATION, "The workspace failed to activate.", ex);
        }
    }

    public void deactivate() {
        if (state != WorkspaceInstanceState.ACTIVE) {
            return;
        }
        try {
            instance.deactivate();
            state = WorkspaceInstanceState.INACTIVE;
        } catch (RuntimeException | Error ex) {
            fail(PluginFailurePhase.WORKSPACE_DEACTIVATION, "The workspace failed to deactivate.", ex);
        }
    }

    /** Asks the workspace whether it may close; the callback fires at most once. */
    public void requestClose(WorkspaceCloseCallback callback) {
        if (state == WorkspaceInstanceState.DISPOSED || state == WorkspaceInstanceState.FAILED) {
            callback.allowClose();
            return;
        }
        state = WorkspaceInstanceState.CLOSING;
        GuardedCloseCallback guarded = new GuardedCloseCallback(callback);
        try {
            instance.requestClose(guarded);
        } catch (RuntimeException | Error ex) {
            // A broken requestClose must not trap the workspace open: treat it as "allow close".
            fail(PluginFailurePhase.WORKSPACE_DISPOSAL, "The workspace failed while closing.", ex);
            guarded.allowClose();
        }
    }

    /** Idempotent; safe to call after a failure or a previous dispose. */
    public void dispose() {
        if (state == WorkspaceInstanceState.DISPOSED) {
            return;
        }
        try {
            instance.dispose();
        } catch (RuntimeException | Error ex) {
            lastFailure = failure(pluginId, PluginFailurePhase.WORKSPACE_DISPOSAL,
                    "The workspace failed to dispose cleanly.", ex);
        } finally {
            state = WorkspaceInstanceState.DISPOSED;
        }
    }

    private void fail(PluginFailurePhase phase, String message, Throwable cause) {
        this.lastFailure = failure(pluginId, phase, message, cause);
        this.state = WorkspaceInstanceState.FAILED;
    }

    private static PluginLoadFailure failure(String pluginId, PluginFailurePhase phase, String message,
                                             Throwable cause) {
        return new PluginLoadFailure("", pluginId, phase, message, cause);
    }

    /** Outcome of {@link #open}: either a live controller or a captured creation failure. */
    public static final class Result {
        private final WorkspaceLifecycleController controller;
        private final PluginLoadFailure failure;

        private Result(WorkspaceLifecycleController controller, PluginLoadFailure failure) {
            this.controller = controller;
            this.failure = failure;
        }

        static Result created(WorkspaceLifecycleController controller) {
            return new Result(controller, null);
        }

        static Result failed(PluginLoadFailure failure) {
            return new Result(null, failure);
        }

        public boolean isSuccess() {
            return controller != null;
        }

        public WorkspaceLifecycleController getController() {
            return controller;
        }

        public PluginLoadFailure getFailure() {
            return failure;
        }
    }
}
