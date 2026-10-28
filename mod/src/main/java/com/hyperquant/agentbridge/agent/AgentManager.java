package com.hyperquant.agentbridge.agent;

import com.google.gson.JsonObject;
import com.hyperquant.agentbridge.AgentBridge;
import com.hyperquant.agentbridge.BridgeConfig;
import com.hyperquant.agentbridge.body.Action;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Owns the agent avatars. Everything that touches the world runs on the server thread: other threads hand
 * work over with {@link #submit}, which runs it at the end of the next server tick, where a real client's
 * packets would be processed too.
 */
public final class AgentManager {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    @Nullable
    private static AgentManager instance;

    private final MinecraftServer server;
    private final Map<String, AgentPlayer> agents = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> work = new ConcurrentLinkedQueue<>();
    public final EventHub events = new EventHub();
    private int tick;

    private AgentManager(MinecraftServer server) {
        this.server = server;
    }

    public static void init(MinecraftServer server) {
        instance = new AgentManager(server);
    }

    public static AgentManager get() {
        if (instance == null) {
            throw new IllegalStateException("The server is not running");
        }
        return instance;
    }

    @Nullable
    public static AgentManager getIfRunning() {
        return instance;
    }

    public static void shutdown() {
        AgentManager manager = instance;
        if (manager != null) {
            for (AgentPlayer agent : new ArrayList<>(manager.agents.values())) {
                manager.logOut(agent, new TextComponentString("Server closed"));
            }
            manager.agents.clear();
        }
        instance = null;
    }

    public MinecraftServer server() {
        return server;
    }

    public int currentTick() {
        return tick;
    }

    // ---- threading ----

    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        work.add(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Server tick, end phase. */
    public void tick() {
        tick++;
        for (Runnable r; (r = work.poll()) != null; ) {
            r.run();
        }
        for (AgentPlayer agent : new ArrayList<>(agents.values())) {
            if (server.getPlayerList().getPlayerByUUID(agent.getUniqueID()) != agent) {
                // Removed by something else (another mod, a crash in its connection, ...).
                agents.remove(key(agent.getName()));
                publish("despawned", agent, "removed from the server");
                continue;
            }
            if (agent.diedAtTick() >= 0) {
                long deadFor = agent.getServerWorld().getTotalWorldTime() - agent.diedAtTick();
                if (BridgeConfig.agents.autoRespawn && deadFor >= BridgeConfig.agents.respawnDelayTicks) {
                    respawn(agent);
                }
                continue;
            }
            agent.tickAction();
        }
    }

    // ---- lifecycle (server thread) ----

    @Nullable
    public AgentPlayer get(String name) {
        return agents.get(key(name));
    }

    public AgentPlayer require(String name) {
        AgentPlayer agent = get(name);
        if (agent == null) {
            throw new AgentException(404, "No agent named " + name + " is spawned");
        }
        return agent;
    }

    public Collection<AgentPlayer> all() {
        return Collections.unmodifiableCollection(agents.values());
    }

    /**
     * Logs an agent in. A returning agent appears where it logged out, with its saved inventory; a new one
     * appears at {@code at} (if given) or at the world spawn.
     */
    public AgentPlayer spawn(String name, @Nullable Vec3d at, float yaw, int dimension) {
        if (!NAME.matcher(name).matches()) {
            throw new AgentException(400, "Agent names must be 3-16 letters, digits or underscores");
        }
        if (get(name) != null) {
            throw new AgentException(409, "Agent " + name + " is already spawned");
        }
        if (server.getPlayerList().getPlayerByUsername(name) != null) {
            throw new AgentException(409, "A player named " + name + " is online");
        }
        if (agents.size() >= BridgeConfig.agents.maxAgents) {
            throw new AgentException(409, "Agent limit reached (" + BridgeConfig.agents.maxAgents + ")");
        }
        WorldServer world = server.getWorld(dimension);
        if (world == null) {
            throw new AgentException(400, "Unknown dimension " + dimension);
        }

        GameProfile profile = new GameProfile(EntityPlayer.getOfflineUUID(name), name);
        boolean returning = hasSaveData(profile);
        AgentPlayer agent = new AgentPlayer(server, world, profile);
        if (at != null && !returning) {
            agent.setLocationAndAngles(at.x, at.y, at.z, yaw, 0F);
        }
        AgentNetworkManager network = new AgentNetworkManager();
        AgentNetHandler handler = new AgentNetHandler(server, network, agent);
        // The same call that completes a real player's login: loads saved data, adds the entity to the
        // world and the tab list, and fires PlayerLoggedInEvent for other mods.
        server.getPlayerList().initializeConnectionToPlayer(network, agent, handler);
        if (agent.getHealth() <= 0F) {
            agent.reviveAtSpawn(); // logged out while dead
        }
        if (BridgeConfig.agents.forceSurvival) {
            agent.setGameType(GameType.SURVIVAL);
        }
        agents.put(key(name), agent);
        publish("spawned", agent, returning ? "returned" : "first login");
        AgentBridge.LOG.info("Agent {} spawned at {}", name, agent.getPosition());
        return agent;
    }

    public void despawn(String name, String reason) {
        AgentPlayer agent = require(name);
        agents.remove(key(name));
        logOut(agent, new TextComponentString(reason));
        publish("despawned", agent, reason);
    }

    private void logOut(AgentPlayer agent, ITextComponent reason) {
        agent.cancelAction("the agent left the game");
        agent.connection.onDisconnect(reason); // saves player data, removes the entity, "left the game"
        agent.connection.netManager.channel().close();
    }

    private void respawn(AgentPlayer dead) {
        String name = dead.getName();
        agents.remove(key(name));
        server.getPlayerList().playerLoggedOut(dead);
        dead.connection.netManager.channel().close();
        try {
            AgentPlayer fresh = spawn(name, null, 0F, 0);
            publish("respawned", fresh, "at " + fresh.getPosition());
        } catch (AgentException e) {
            AgentBridge.LOG.warn("Could not respawn agent {}: {}", name, e.getMessage());
        }
    }

    void onAgentDeath(AgentPlayer agent, String message) {
        publish("died", agent, message);
    }

    void onKicked(AgentPlayer agent, ITextComponent reason) {
        work.add(() -> {
            if (agents.get(key(agent.getName())) == agent) {
                agents.remove(key(agent.getName()));
                logOut(agent, reason);
                publish("despawned", agent, "kicked: " + reason.getUnformattedText());
            }
        });
    }

    private boolean hasSaveData(GameProfile profile) {
        File dir = new File(server.getWorld(0).getSaveHandler().getWorldDirectory(), "playerdata");
        return new File(dir, profile.getId() + ".dat").exists();
    }

    // ---- requests from players ----

    /**
     * A player asked an agent to do something. Returns false when no harness is listening, so the caller can
     * tell the player that the agent's brain is offline.
     */
    public boolean requestTask(AgentPlayer agent, String from, String text, String source) {
        JsonObject e = new JsonObject();
        e.addProperty("agent", agent.getName());
        e.addProperty("from", from);
        e.addProperty("text", text);
        e.addProperty("source", source);
        events.publish("task", e);
        return events.harnessConnected();
    }

    public void requestStop(AgentPlayer agent, String from) {
        agent.cancelAction("stopped by " + from);
        JsonObject e = new JsonObject();
        e.addProperty("agent", agent.getName());
        e.addProperty("from", from);
        events.publish("stop", e);
    }

    public void startAction(AgentPlayer agent, Action action) {
        if (agent.diedAtTick() >= 0) {
            throw new AgentException(409, agent.getName() + " is dead and waiting to respawn");
        }
        agent.startAction(action);
    }

    private void publish(String type, AgentPlayer agent, String message) {
        JsonObject e = new JsonObject();
        e.addProperty("agent", agent.getName());
        e.addProperty("message", message);
        events.publish(type, e);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
