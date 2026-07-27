package com.aresstack.askai.acp;

/** Lifecycle of one initialized ACP connection (distinct from the OS process and the logical session). */
public enum AcpConnectionState { STARTING, INITIALIZING, READY, FAILED, CLOSED }
