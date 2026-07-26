package com.aresstack.askai.plugin.api.service;

/**
 * Lets a workspace surface transient, non-modal messages through the host (e.g. a status strip or toast)
 * instead of popping its own dialogs. Severity is a plain enum to stay framework-free.
 */
public interface NotificationService {

    enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    void notify(Severity severity, String message);
}
