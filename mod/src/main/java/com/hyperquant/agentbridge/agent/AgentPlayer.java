package com.hyperquant.agentbridge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hyperquant.agentbridge.body.Action;
import com.mojang.authlib.GameProfile;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.FoodStats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * An agent's avatar: a normal server-side player whose "client" is the agent harness.
 *
 * <p>For a human player the client simulates movement and sends positions to the server. An agent has no
 * client, so this class runs the same vanilla physics on the server instead: actions only set movement
 * intents (forward, strafe, jump, ...), exactly like pressing movement keys.
 */
public class AgentPlayer extends EntityPlayerMP {
    public final Ears ears = new Ears();
    private final Deque<JsonObject> damageLog = new ArrayDeque<>();

    // Movement intent written by the current action and applied before every physics tick.
    public float forwardIntent;
    public float strafeIntent;
    public boolean jumpIntent;
    public boolean sneakIntent;
    public boolean sprintIntent;

    @Nullable
    private Action action;
    @Nullable
    private JsonObject lastResult;
    private long diedAtTick = -1;
    private int lastChatTick = -100000;

    private double lastReportedX = Double.NaN;
    private double lastReportedY = Double.NaN;
    private double lastReportedZ = Double.NaN;

    public AgentPlayer(MinecraftServer server, WorldServer world, GameProfile profile) {
        super(server, world, profile, new PlayerInteractionManager(world));
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        applyMovementIntent();
        // For a real player this is triggered by its network connection every tick.
        onUpdateEntity();
        reportMovement();
    }

    /** Vanilla skips fall damage here because the client reports falls; agents have no client. */
    @Override
    protected void updateFallState(double y, boolean onGroundIn, IBlockState state, BlockPos pos) {
        handleFalling(y, onGroundIn);
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        float before = getHealth();
        boolean hurt = super.attackEntityFrom(source, amount);
        if (hurt) {
            JsonObject o = new JsonObject();
            o.addProperty("time_ms", System.currentTimeMillis());
            o.addProperty("type", source.getDamageType());
            Entity by = source.getTrueSource();
            if (by != null) {
                o.addProperty("by", by.getName());
                o.addProperty("by_entity_id", by.getEntityId());
            }
            o.addProperty("health_lost", Math.max(0F, before - getHealth()));
            synchronized (damageLog) {
                damageLog.addLast(o);
                while (damageLog.size() > 10) {
                    damageLog.pollFirst();
                }
            }
        }
        return hurt;
    }

    @Override
    public void onDeath(DamageSource cause) {
        // Read before super.onDeath(), which resets the combat tracker.
        String message = getCombatTracker().getDeathMessage().getUnformattedText();
        super.onDeath(cause);
        if (getHealth() <= 0F) {
            diedAtTick = getServerWorld().getTotalWorldTime();
            cancelAction("the agent died");
            AgentManager.get().onAgentDeath(this, message);
        }
    }

    /** Agents have no socket; the vanilla implementation would crash parsing the embedded channel's address. */
    @Override
    public String getPlayerIP() {
        return "127.0.0.1";
    }

    private void applyMovementIntent() {
        if (getHealth() <= 0F) {
            clearMovement();
        }
        float scale = sneakIntent ? 0.3F : 1.0F;
        if (isHandActive()) {
            scale *= 0.2F; // eating, drawing a bow, blocking: same slowdown a client applies
        }
        moveForward = forwardIntent * scale;
        moveStrafing = strafeIntent * scale;
        setJumping(jumpIntent);
        setSneaking(sneakIntent);
        setSprinting(sprintIntent && forwardIntent > 0.8F && !sneakIntent && !isHandActive()
                && getFoodStats().getFoodLevel() > 6 && !collidedHorizontally);
    }

    public void clearMovement() {
        forwardIntent = 0F;
        strafeIntent = 0F;
        jumpIntent = false;
        sneakIntent = false;
        sprintIntent = false;
    }

    private void reportMovement() {
        if (posX != lastReportedX || posY != lastReportedY || posZ != lastReportedZ) {
            // Keeps chunk loading and player tracking up to date, as for a player whose client moved.
            getServer().getPlayerList().serverUpdateMovingPlayer(this);
            lastReportedX = posX;
            lastReportedY = posY;
            lastReportedZ = posZ;
        }
    }

    public void face(float yaw, float pitch) {
        rotationYaw = MathHelper.wrapDegrees(yaw);
        rotationPitch = MathHelper.clamp(pitch, -90F, 90F);
        setRotationYawHead(rotationYaw);
    }

    public void lookAt(double x, double y, double z) {
        double dx = x - posX;
        double dy = y - (posY + getEyeHeight());
        double dz = z - posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180D / Math.PI)) - 90F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * (180D / Math.PI));
        face(yaw, pitch);
    }

    // ---- actions (server thread only) ----

    void startAction(Action next) {
        if (action != null) {
            action.cancel("replaced by " + next.name);
            lastResult = action.result();
        }
        action = next;
    }

    void tickAction() {
        if (action == null) {
            return;
        }
        action.tick();
        if (!action.isRunning()) {
            lastResult = action.result();
            action = null;
        }
    }

    public void cancelAction(String reason) {
        if (action != null) {
            action.cancel(reason);
            lastResult = action.result();
            action = null;
        }
        clearMovement();
    }

    @Nullable
    public JsonObject actionStatus() {
        return action != null ? action.result() : lastResult;
    }

    public boolean isBusy() {
        return action != null;
    }

    // ---- death and respawn ----

    long diedAtTick() {
        return diedAtTick;
    }

    /** Puts a dead agent back on its feet at its spawn point, like the vanilla respawn screen does. */
    void reviveAtSpawn() {
        setHealth(getMaxHealth());
        foodStats = new FoodStats();
        deathTime = 0;
        isDead = false;
        fallDistance = 0F;
        extinguish();
        clearActivePotions();
        setAir(300);
        if (!world.getGameRules().getBoolean("keepInventory")) {
            experienceLevel = 0;
            experienceTotal = 0;
            experience = 0F;
        }
        diedAtTick = -1;

        int respawnDim = getServerWorld().provider.getRespawnDimension(this);
        WorldServer target = getServer().getWorld(respawnDim);
        BlockPos spawn = null;
        BlockPos bed = getBedLocation(respawnDim);
        if (bed != null) {
            spawn = getBedSpawnLocation(target, bed, isSpawnForced(respawnDim));
        }
        if (spawn == null) {
            spawn = target.provider.getRandomizedSpawnPoint();
        }
        final BlockPos at = spawn;
        if (dimension != respawnDim) {
            changeDimension(respawnDim, (world, entity, yaw) ->
                    entity.setLocationAndAngles(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, entity.rotationYaw, 0F));
        }
        connection.setPlayerLocation(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, rotationYaw, 0F);
    }

    // ---- misc state used by the eyes and the body ----

    public JsonArray recentDamage(long maxAgeMs) {
        long now = System.currentTimeMillis();
        JsonArray out = new JsonArray();
        synchronized (damageLog) {
            for (JsonObject o : damageLog) {
                long age = now - o.get("time_ms").getAsLong();
                if (age <= maxAgeMs) {
                    JsonObject copy = new JsonObject();
                    copy.addProperty("seconds_ago", age / 1000);
                    copy.add("type", o.get("type"));
                    if (o.has("by")) {
                        copy.add("by", o.get("by"));
                        copy.add("by_entity_id", o.get("by_entity_id"));
                    }
                    copy.add("health_lost", o.get("health_lost"));
                    out.add(copy);
                }
            }
        }
        return out;
    }

    /** Rate limit for chat; returns false if the agent spoke too recently. */
    public boolean tryUseChat(int currentTick, int cooldown) {
        if (currentTick - lastChatTick < cooldown) {
            return false;
        }
        lastChatTick = currentTick;
        return true;
    }
}
