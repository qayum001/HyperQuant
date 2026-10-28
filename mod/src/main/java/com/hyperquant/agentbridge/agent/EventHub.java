package com.hyperquant.agentbridge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Things the harness needs to react to (task requests from players, deaths, ...), delivered by long polling.
 * Thread-safe: published from the server thread, read from HTTP threads.
 */
public final class EventHub {
    private static final int CAPACITY = 500;
    private static final long HARNESS_TIMEOUT_MS = 90_000;

    private final Deque<JsonObject> events = new ArrayDeque<>();
    private long nextId = 1;
    private volatile long lastPollMs;

    public synchronized long publish(String type, JsonObject payload) {
        long id = nextId++;
        payload.addProperty("id", id);
        payload.addProperty("type", type);
        payload.addProperty("time_ms", System.currentTimeMillis());
        events.addLast(payload);
        while (events.size() > CAPACITY) {
            events.pollFirst();
        }
        notifyAll();
        return id;
    }

    /**
     * Events with an id greater than {@code after}, waiting up to {@code waitMs} for the first one, plus the newest
     * id at that moment (taken together, so nothing published in between can be skipped).
     */
    public synchronized JsonObject await(long after, long waitMs) throws InterruptedException {
        lastPollMs = System.currentTimeMillis();
        long deadline = lastPollMs + Math.max(0, waitMs);
        while (newestId() == after) { // after > newest means the server restarted: answer at once
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            wait(remaining);
        }
        lastPollMs = System.currentTimeMillis();
        JsonArray out = new JsonArray();
        for (JsonObject e : events) {
            if (e.get("id").getAsLong() > after) {
                out.add(e);
            }
        }
        JsonObject result = new JsonObject();
        result.add("events", out);
        result.addProperty("last_id", newestId());
        return result;
    }

    public synchronized long newestId() {
        return nextId - 1;
    }

    /** True if a harness has polled for events recently, i.e. someone will actually handle a task. */
    public boolean harnessConnected() {
        return System.currentTimeMillis() - lastPollMs < HARNESS_TIMEOUT_MS;
    }
}
