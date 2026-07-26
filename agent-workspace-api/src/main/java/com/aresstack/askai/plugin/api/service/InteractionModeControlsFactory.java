package com.aresstack.askai.plugin.api.service;

/**
 * Creates {@link InteractionModeControls} views bound to the host's single interaction-mode controller. A
 * workspace requests one for its own composer instead of rebuilding the selector or managing agent lists.
 */
public interface InteractionModeControlsFactory {

    InteractionModeControls create(InteractionModeControlsOptions options);
}
