package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.InteractionModeControls;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsFactory;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsOptions;

/** Creates {@link InteractionModeControls} views all bound to one shared {@link WorkspaceModeController}. */
public final class DefaultInteractionModeControlsFactory implements InteractionModeControlsFactory {

    private final WorkspaceModeController controller;

    public DefaultInteractionModeControlsFactory(WorkspaceModeController controller) {
        this.controller = controller;
    }

    @Override
    public InteractionModeControls create(InteractionModeControlsOptions options) {
        return new DefaultInteractionModeControls(controller);
    }
}
