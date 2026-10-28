package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.eyes.Describe;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The rules a player's hands follow: you can only touch what is within reach and what you can actually see.
 * These are stricter than the server's own packet checks, which assume an honest client did the aiming.
 */
public final class Interaction {
    private Interaction() {}

    public static Vec3d eyes(EntityPlayer player) {
        return new Vec3d(player.posX, player.posY + player.getEyeHeight(), player.posZ);
    }

    /** Block reach exactly as the Forge client computes it (4.5 in survival). */
    public static double blockReach(EntityPlayerMP player) {
        double attribute = player.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue();
        return player.isCreative() ? attribute : attribute - 0.5D;
    }

    public static double entityReach(EntityPlayerMP player) {
        return player.isCreative() ? 5.0D : 3.0D;
    }

    /**
     * Aims the "crosshair" at a block: tries points on the block's faces and returns the first ray from the eyes
     * that hits this block within reach. Null if the block is out of reach or hidden behind something.
     */
    @Nullable
    public static RayTraceResult aimAtBlock(EntityPlayerMP player, BlockPos pos, @Nullable EnumFacing requiredFace) {
        World world = player.world;
        Vec3d eye = eyes(player);
        double reach = blockReach(player);
        IBlockState state = world.getBlockState(pos);
        AxisAlignedBB box = state.getBoundingBox(world, pos).offset(pos);
        double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;

        List<Vec3d> points = new ArrayList<>();
        points.add(new Vec3d(cx, cy, cz));
        points.add(new Vec3d(cx, box.maxY, cz));
        points.add(new Vec3d(cx, box.minY, cz));
        points.add(new Vec3d(box.minX, cy, cz));
        points.add(new Vec3d(box.maxX, cy, cz));
        points.add(new Vec3d(cx, cy, box.minZ));
        points.add(new Vec3d(cx, cy, box.maxZ));
        points.sort(Comparator.comparingDouble(eye::distanceTo));

        for (Vec3d point : points) {
            Vec3d direction = point.subtract(eye);
            double length = direction.length();
            if (length < 1.0E-4 || length > reach + 0.5D) {
                continue;
            }
            // Aim slightly past the point so the ray enters the block.
            Vec3d end = eye.add(direction.scale((length + 0.5D) / length));
            RayTraceResult hit = world.rayTraceBlocks(eye, end, false, false, false);
            if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && pos.equals(hit.getBlockPos())
                    && eye.distanceTo(hit.hitVec) <= reach
                    && (requiredFace == null || hit.sideHit == requiredFace)) {
                return hit;
            }
        }
        return null;
    }

    /** Human-readable reason why {@link #aimAtBlock} found nothing, for the brain to act on. */
    public static String whyUnreachable(EntityPlayerMP player, BlockPos pos) {
        World world = player.world;
        Vec3d eye = eyes(player);
        double reach = blockReach(player);
        double distance = distanceToBox(eye, new AxisAlignedBB(pos));
        String what = Describe.blockName(world.getBlockState(pos)) + " at " + Describe.pos(pos);
        if (distance > reach) {
            return String.format("%s is %.1f blocks away but reach is %.1f; move closer first", what, distance, reach);
        }
        Vec3d center = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        RayTraceResult hit = world.rayTraceBlocks(eye, center, false, false, false);
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && !pos.equals(hit.getBlockPos())) {
            return what + " is hidden behind " + Describe.blockName(world.getBlockState(hit.getBlockPos()))
                    + " at " + Describe.pos(hit.getBlockPos());
        }
        return what + " cannot be seen from here";
    }

    /** Null if the agent may reach and see the entity, otherwise the reason it cannot. */
    @Nullable
    public static String entityProblem(EntityPlayerMP player, Entity target) {
        Vec3d eye = eyes(player);
        double distance = distanceToBox(eye, target.getEntityBoundingBox());
        double reach = entityReach(player);
        if (distance > reach) {
            return String.format("%s is %.1f blocks away but reach is %.1f; move closer first",
                    target.getName(), distance, reach);
        }
        Vec3d center = new Vec3d(target.posX, target.posY + target.height / 2.0D, target.posZ);
        if (!player.canEntityBeSeen(target) && player.world.rayTraceBlocks(eye, center, false, true, false) != null) {
            return target.getName() + " is not visible from here";
        }
        return null;
    }

    /** Null if the server would let a player edit this block, otherwise why not. */
    @Nullable
    public static String editProblem(EntityPlayerMP player, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (pos.getY() < 0 || server == null || pos.getY() >= server.getBuildLimit()) {
            return "outside the build height";
        }
        if (server.isBlockProtected(player.world, pos, player)) {
            return "the area is spawn-protected";
        }
        if (!player.world.getWorldBorder().contains(pos)) {
            return "outside the world border";
        }
        return null;
    }

    public static double distanceToBox(Vec3d point, AxisAlignedBB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0D), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
