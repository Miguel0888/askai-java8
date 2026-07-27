package com.aresstack.askai.acp;

/** One initialized ACP connection over a spawned agent process. Closing is idempotent. */
public interface AcpConnection extends AutoCloseable {

    AcpConnectionState getState();

    AgentProcessHandle getProcess();

    AcpSession newSession() throws AcpException;

    @Override
    void close();
}
