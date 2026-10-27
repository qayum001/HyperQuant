package com.hyperquant.agentbridge.body;

import com.google.gson.JsonObject;
import com.hyperquant.agentbridge.AgentBridge;
import com.hyperquant.agentbridge.agent.AgentPlayer;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One player-sized thing the body does ("walk there", "mine that block"). Runs over several server ticks,
 * on the server thread, and ends with a status and a message the brain can read.
 */
public abstract class Action {
    public enum Status { RUNNING, SUCCESS, FAILURE, CANCELLED, TIMEOUT }

    private static final AtomicLong IDS = new AtomicLong();

    public final long id = IDS.incrementAndGet();
    public final String name;
    protected final AgentPlayer agent;
    /** Extra result data for the brain, filled in by subclasses. */
    protected final JsonObject data = new JsonObject();

    private final int timeoutTicks;
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private int ticks;
    private Status status = Status.RUNNING;
    private String message = "";

    protected Action(String name, AgentPlayer agent, int timeoutTicks) {
        this.name = name;
        this.agent = agent;
        this.timeoutTicks = timeoutTicks;
    }

    /** Advances the action by one server tick. */
    public final void tick() {
        if (status != Status.RUNNING) {
            return;
        }
        if (++ticks > timeoutTicks) {
            finish(Status.TIMEOUT, "did not finish within " + timeoutTicks + " ticks");
            return;
        }
        try {
            update();
        } catch (RuntimeException e) {
            AgentBridge.LOG.warn("Action {} of {} crashed", name, agent.getName(), e);
            finish(Status.FAILURE, "internal error: " + e);
        }
    }

    /** One tick of work. Call {@link #succeed} or {@link #fail} when done. */
    protected abstract void update();

    /** Called once when the action ends for any reason. */
    protected void cleanup() {
    }

    protected final void succeed(String message) {
        finish(Status.SUCCESS, message);
    }

    protected final void fail(String message) {
        finish(Status.FAILURE, message);
    }

    public final void cancel(String reason) {
        finish(Status.CANCELLED, reason);
    }

    private void finish(Status status, String message) {
        if (this.status != Status.RUNNING) {
            return;
        }
        this.status = status;
        this.message = message;
        try {
            cleanup();
        } finally {
            agent.clearMovement();
            done.complete(result());
        }
    }

    public final boolean isRunning() {
        return status == Status.RUNNING;
    }

    protected final int ticks() {
        return ticks;
    }

    /** Completes (on the server thread) with {@link #result()} once the action has ended. */
    public final CompletableFuture<JsonObject> future() {
        return done;
    }

    /** Snapshot of the action's state. Call on the server thread. */
    public final JsonObject result() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("action", name);
        o.addProperty("status", status.name().toLowerCase(Locale.ROOT));
        o.addProperty("message", message);
        o.addProperty("ticks", ticks);
        JsonObject copy = new JsonObject();
        data.entrySet().forEach(e -> copy.add(e.getKey(), e.getValue()));
        o.add("data", copy);
        return o;
    }
}
