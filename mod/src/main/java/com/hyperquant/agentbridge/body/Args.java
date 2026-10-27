package com.hyperquant.agentbridge.body;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** Typed access to an action's JSON arguments. Problems become {@link IllegalArgumentException}s for the brain. */
public final class Args {
    private final JsonObject json;

    public Args(@Nullable JsonObject json) {
        this.json = json != null ? json : new JsonObject();
    }

    public boolean has(String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }

    public int integer(String key) {
        if (!has(key)) {
            throw new IllegalArgumentException("missing argument '" + key + "'");
        }
        try {
            return (int) Math.floor(json.get(key).getAsDouble());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number");
        }
    }

    public int integer(String key, int fallback) {
        return has(key) ? integer(key) : fallback;
    }

    public int integer(String key, int fallback, int min, int max) {
        int value = integer(key, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException("argument '" + key + "' must be between " + min + " and " + max);
        }
        return value;
    }

    public double number(String key, double fallback) {
        if (!has(key)) {
            return fallback;
        }
        try {
            return json.get(key).getAsDouble();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("argument '" + key + "' must be a number");
        }
    }

    public boolean bool(String key, boolean fallback) {
        if (!has(key)) {
            return fallback;
        }
        JsonElement e = json.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        String s = e.getAsString().trim().toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }

    public String string(String key) {
        if (!has(key) || json.get(key).getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException("missing argument '" + key + "'");
        }
        return json.get(key).getAsString().trim();
    }

    @Nullable
    public String string(String key, @Nullable String fallback) {
        return has(key) ? string(key) : fallback;
    }

    public BlockPos blockPos() {
        return new BlockPos(integer("x"), integer("y"), integer("z"));
    }

    public boolean hasPos() {
        return has("x") && has("y") && has("z");
    }
}
