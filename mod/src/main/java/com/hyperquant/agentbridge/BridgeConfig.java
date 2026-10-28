package com.hyperquant.agentbridge;

import net.minecraftforge.common.config.Config;

/** Stored in {@code config/agentbridge.cfg}. */
@Config(modid = AgentBridge.MODID)
public final class BridgeConfig {
    @Config.Comment("Local HTTP API used by the agent harness")
    public static final Api api = new Api();

    @Config.Comment("Agent avatars and what they are allowed to do")
    public static final Agents agents = new Agents();

    @Config.Comment("The agents' eyes: what they can perceive")
    public static final Vision vision = new Vision();

    @Config.Comment("Path finding for move_to")
    public static final Movement movement = new Movement();

    public static final class Api {
        @Config.Comment("Address to listen on. Keep 127.0.0.1 unless the harness runs on another machine.")
        public String bindAddress = "127.0.0.1";

        @Config.RangeInt(min = 1, max = 65535)
        public int port = 8765;

        @Config.Comment("Bearer token the harness must send. Generated on first start when empty.")
        public String token = "";
    }

    public static final class Agents {
        @Config.RangeInt(min = 1, max = 64)
        public int maxAgents = 4;

        @Config.Comment("Keep agents in survival mode no matter what their saved game mode is")
        public boolean forceSurvival = true;

        @Config.Comment("Respawn dead agents automatically, like a player clicking 'Respawn'")
        public boolean autoRespawn = true;

        @Config.RangeInt(min = 20, max = 1200)
        public int respawnDelayTicks = 40;

        @Config.Comment("Permission level needed for /agent spawn and /agent despawn (2 = op)")
        @Config.RangeInt(min = 0, max = 4)
        public int spawnPermissionLevel = 2;

        @Config.Comment("Permission level needed to give agents tasks with /agent task or @name in chat")
        @Config.RangeInt(min = 0, max = 4)
        public int taskPermissionLevel = 0;

        @Config.Comment("Allow agents to run /commands through chat. They still only get their own (non-op) permissions.")
        public boolean allowCommands = false;

        @Config.Comment("Minimum ticks between two chat messages from the same agent")
        @Config.RangeInt(min = 0, max = 200)
        public int chatCooldownTicks = 20;
    }

    public static final class Vision {
        @Config.Comment("How far the agent can see, in blocks")
        @Config.RangeInt(min = 4, max = 96)
        public int range = 32;

        @Config.RangeInt(min = 30, max = 170)
        public int fovHorizontal = 90;

        @Config.RangeInt(min = 20, max = 150)
        public int fovVertical = 70;

        @Config.Comment("Ray grid resolution across the field of view (a tiny 'camera' that returns block ids instead of pixels)")
        @Config.RangeInt(min = 8, max = 256)
        public int raysHorizontal = 64;

        @Config.RangeInt(min = 8, max = 256)
        public int raysVertical = 40;

        @Config.Comment("Blocks in near-total darkness further away than this are not seen")
        @Config.RangeInt(min = 0, max = 96)
        public int darkSightRange = 5;

        @Config.RangeInt(min = 1, max = 200)
        public int maxBlockTypes = 40;

        @Config.RangeInt(min = 1, max = 200)
        public int maxEntities = 24;
    }

    public static final class Movement {
        @Config.RangeInt(min = 8, max = 256)
        public int maxPathDistance = 96;

        @Config.RangeInt(min = 500, max = 200000)
        public int maxPathNodes = 12000;

        @Config.Comment("Highest drop the path finder accepts (fall damage starts above 3)")
        @Config.RangeInt(min = 1, max = 10)
        public int maxFall = 3;
    }

    private BridgeConfig() {}
}
