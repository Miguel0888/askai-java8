package com.aresstack.askai.acp;

/** Spawns the external agent and establishes an initialized ACP connection. */
public interface AcpAgentConnector {

    AcpConnection connect(AgentLaunchSpec spec) throws AcpException;
}
