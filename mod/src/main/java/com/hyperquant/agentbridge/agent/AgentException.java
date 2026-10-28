package com.hyperquant.agentbridge.agent;

/** A request that cannot be served; the message is shown to whoever asked (harness or player). */
public class AgentException extends RuntimeException {
    public final int httpStatus;

    public AgentException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }
}
