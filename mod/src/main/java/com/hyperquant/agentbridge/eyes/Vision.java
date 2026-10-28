package com.hyperquant.agentbridge.eyes;

import com.hyperquant.agentbridge.BridgeConfig;
import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.body.Interaction;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the agent can see, without rendering anything: a grid of rays is cast from the eyes across the field
 * of view, like a very low-resolution camera whose "pixels" are block ids instead of colours. Only surfaces a
 * ray actually reaches are seen, so walls hide what is behind them, caves are dark without light, and there is
 * no x-ray. Entities are seen when they are inside the field of view with a clear line of sight.
 */
public final class Vision {
    /** Everything seen in one look. */
    public static final class View {
        /** block key ("id@meta") -> position -> distance from the eyes */
        public final Map<String, Map<BlockPos, Double>> blocks = new LinkedHashMap<>();
        public final Map<String, IBlockState> states = new HashMap<>();
        public final List<Entity> entities = new ArrayList<>();
        public int rays;
        public int darkRays;
        public final boolean panorama;
        public final double range;

        View(boolean panorama, double range) {
            this.panorama = panorama;
            this.range = range;
        }
    }

    private Vision() {}

    /**
     * @param panorama look all around (the agent turning its head through 360 degrees) instead of only ahead
     */
    public static View look(AgentPlayer agent, boolean panorama) {
        BridgeConfig.Vision cfg = BridgeConfig.vision;
        World world = agent.world;
        Vec3d eye = Interaction.eyes(agent);
        View view = new View(panorama, cfg.range);
        boolean nightVision = agent.isPotionActive(MobEffects.NIGHT_VISION);

        float yaw0 = agent.rotationYaw;
        float pitch0 = panorama ? 0F : agent.rotationPitch;
        float spanH = panorama ? 360F : cfg.fovHorizontal;
        int raysH = panorama ? Math.round(cfg.raysHorizontal * 360F / cfg.fovHorizontal) : cfg.raysHorizontal;
        int raysV = cfg.raysVertical;
        for (int i = 0; i < raysH; i++) {
            float yaw = yaw0 - spanH / 2F + (i + 0.5F) * spanH / raysH;
            for (int j = 0; j < raysV; j++) {
                float pitch = MathHelper.clamp(pitch0 - cfg.fovVertical / 2F + (j + 0.5F) * cfg.fovVertical / raysV, -90F, 90F);
                castRay(world, eye, yaw, pitch, cfg.range, cfg.darkSightRange, nightVision, view);
            }
        }
        seeEntities(agent, eye, yaw0, pitch0, nightVision, view);
        return view;
    }

    /** Unit look vector for a Minecraft yaw/pitch (yaw 0 = south, pitch > 0 = down). */
    public static Vec3d direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3d(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** Voxel walk (Amanatides & Woo) recording every non-air block the ray passes until something opaque stops it. */
    private static void castRay(World world, Vec3d eye, float yaw, float pitch, double range, int darkRange,
                                boolean nightVision, View view) {
        view.rays++;
        Vec3d d = direction(yaw, pitch);
        int x = MathHelper.floor(eye.x), y = MathHelper.floor(eye.y), z = MathHelper.floor(eye.z);
        int stepX = d.x > 0 ? 1 : -1, stepY = d.y > 0 ? 1 : -1, stepZ = d.z > 0 ? 1 : -1;
        double deltaX = d.x == 0 ? Double.MAX_VALUE : Math.abs(1D / d.x);
        double deltaY = d.y == 0 ? Double.MAX_VALUE : Math.abs(1D / d.y);
        double deltaZ = d.z == 0 ? Double.MAX_VALUE : Math.abs(1D / d.z);
        double maxX = d.x == 0 ? Double.MAX_VALUE : (d.x > 0 ? x + 1 - eye.x : eye.x - x) * deltaX;
        double maxY = d.y == 0 ? Double.MAX_VALUE : (d.y > 0 ? y + 1 - eye.y : eye.y - y) * deltaY;
        double maxZ = d.z == 0 ? Double.MAX_VALUE : (d.z > 0 ? z + 1 - eye.z : eye.z - z) * deltaZ;
        int seeThrough = 0;
        int liquid = 0;

        while (true) {
            int px = x, py = y, pz = z;
            double t;
            if (maxX < maxY && maxX < maxZ) {
                x += stepX;
                t = maxX;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                y += stepY;
                t = maxY;
                maxY += deltaY;
            } else {
                z += stepZ;
                t = maxZ;
                maxZ += deltaZ;
            }
            if (t > range || y < 0 || y > 255) {
                return;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!world.isBlockLoaded(pos)) {
                return;
            }
            IBlockState state = world.getBlockState(pos);
            Material material = state.getMaterial();
            if (material == Material.AIR) {
                continue;
            }
            if (!nightVision && t > darkRange && state.getLightValue() == 0
                    && world.getLightFromNeighbors(new BlockPos(px, py, pz)) == 0) {
                view.darkRays++; // pitch black: the surface is there but cannot be made out
                return;
            }
            record(view, state, pos, eye);
            if (material.isLiquid()) {
                if (++liquid > 6) {
                    return;
                }
                continue;
            }
            if (state.isOpaqueCube() || ++seeThrough > 2) {
                return; // glass, plants, fences... can be seen through a little
            }
        }
    }

    private static void record(View view, IBlockState state, BlockPos pos, Vec3d eye) {
        String key = Describe.blockId(state) + "@" + Describe.blockMeta(state);
        view.states.putIfAbsent(key, state);
        view.blocks.computeIfAbsent(key, k -> new HashMap<>())
                .computeIfAbsent(pos, p -> eye.distanceTo(new Vec3d(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D)));
    }

    private static void seeEntities(AgentPlayer agent, Vec3d eye, float yaw0, float pitch0, boolean nightVision, View view) {
        BridgeConfig.Vision cfg = BridgeConfig.vision;
        World world = agent.world;
        for (Entity e : world.getEntitiesWithinAABBExcludingEntity(agent, agent.getEntityBoundingBox().grow(cfg.range))) {
            if (e.isInvisible() && !e.isGlowing()) {
                continue;
            }
            Vec3d center = new Vec3d(e.posX, e.posY + e.height * 0.5D, e.posZ);
            double distance = eye.distanceTo(center);
            if (distance > cfg.range) {
                continue;
            }
            if (!view.panorama && !inFieldOfView(eye, center, yaw0, pitch0, cfg.fovHorizontal, cfg.fovVertical, e.width)) {
                continue;
            }
            if (world.rayTraceBlocks(eye, center, false, true, false) != null && !agent.canEntityBeSeen(e)) {
                continue;
            }
            if (!nightVision && distance > cfg.darkSightRange && !e.isBurning() && !e.isGlowing()
                    && world.getLightFromNeighbors(new BlockPos(center)) == 0) {
                continue;
            }
            view.entities.add(e);
        }
        view.entities.sort((a, b) -> Double.compare(agent.getDistanceSq(a), agent.getDistanceSq(b)));
    }

    private static boolean inFieldOfView(Vec3d eye, Vec3d target, float yaw0, float pitch0, float fovH, float fovV, float width) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180D / Math.PI)) - 90F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * (180D / Math.PI));
        // Allow for the entity's size so something half in view still counts.
        float margin = (float) Math.toDegrees(Math.atan2(width, Math.max(horizontal, 0.1D)));
        return Math.abs(MathHelper.wrapDegrees(yaw - yaw0)) <= fovH / 2F + margin
                && Math.abs(pitch - pitch0) <= fovV / 2F + margin;
    }
}
