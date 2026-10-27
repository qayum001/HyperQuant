package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.BridgeConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the positions a player's feet can occupy: walking, 1-block jumps, short drops and swimming.
 * It only plans; the walking itself is done with normal player movement by {@link Navigator}.
 */
public final class Pathfinder {
    public static final class Path {
        public final List<BlockPos> nodes;
        /** False when the goal was unreachable and the path ends at the closest point found. */
        public final boolean complete;

        Path(List<BlockPos> nodes, boolean complete) {
            this.nodes = nodes;
            this.complete = complete;
        }
    }

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final double g;
        final double h;
        boolean closed;

        Node(BlockPos pos, Node parent, double g, double h) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.h = h;
        }

        double f() {
            return g + h;
        }
    }

    private final World world;
    private final int maxFall = BridgeConfig.movement.maxFall;
    private final int maxNodes = BridgeConfig.movement.maxPathNodes;
    private final int maxDistance = BridgeConfig.movement.maxPathDistance;

    public Pathfinder(World world) {
        this.world = world;
    }

    /** Path from {@code start} to any position within {@code range} blocks of {@code goal}, or null. */
    @Nullable
    public Path find(BlockPos start, BlockPos goal, double range) {
        double rangeSq = range * range;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<BlockPos, Node> best = new HashMap<>();
        Node first = new Node(start, null, 0, heuristic(start, goal));
        open.add(first);
        best.put(start, first);
        Node closest = first;
        int explored = 0;

        while (!open.isEmpty() && explored < maxNodes) {
            Node node = open.poll();
            if (node.closed || best.get(node.pos) != node) {
                continue; // stale queue entry
            }
            node.closed = true;
            explored++;
            if (atGoal(node.pos, goal, rangeSq)) {
                return new Path(build(node), true);
            }
            if (node.h < closest.h) {
                closest = node;
            }
            for (Step step : neighbours(node.pos)) {
                BlockPos next = step.pos;
                if (Math.abs(next.getX() - start.getX()) > maxDistance || Math.abs(next.getZ() - start.getZ()) > maxDistance
                        || Math.abs(next.getY() - start.getY()) > maxDistance) {
                    continue;
                }
                double g = node.g + step.cost;
                Node known = best.get(next);
                if (known != null && (known.closed || known.g <= g)) {
                    continue;
                }
                Node created = new Node(next, node, g, heuristic(next, goal));
                best.put(next, created);
                open.add(created);
            }
        }
        return closest == first ? null : new Path(build(closest), false);
    }

    private static boolean atGoal(BlockPos pos, BlockPos goal, double rangeSq) {
        return rangeSq < 0.25D ? pos.equals(goal) : pos.distanceSq(goal) <= rangeSq;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distanceSq(b));
    }

    private static List<BlockPos> build(Node end) {
        List<BlockPos> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            out.add(n.pos);
        }
        Collections.reverse(out);
        return out;
    }

    private static final class Step {
        final BlockPos pos;
        final double cost;

        Step(BlockPos pos, double cost) {
            this.pos = pos;
            this.cost = cost;
        }
    }

    private List<Step> neighbours(BlockPos p) {
        List<Step> out = new ArrayList<>(12);
        for (EnumFacing dir : EnumFacing.Plane.HORIZONTAL) {
            BlockPos side = p.offset(dir);
            if (canStand(side)) {
                out.add(new Step(side, isWater(side) ? 2.5D : 1.0D));
            } else if (passable(p.up(2)) && canStand(side.up())) {
                out.add(new Step(side.up(), 2.0D)); // jump up one block
            } else if (passable(side) && passable(side.up())) {
                for (int drop = 1; drop <= maxFall; drop++) {
                    BlockPos below = side.down(drop);
                    if (canStand(below)) {
                        out.add(new Step(below, 1.0D + 0.5D * drop));
                        break;
                    }
                    if (!passable(below)) {
                        break;
                    }
                }
            }
        }
        // Diagonals, without cutting corners.
        for (EnumFacing dir : EnumFacing.Plane.HORIZONTAL) {
            BlockPos a = p.offset(dir);
            BlockPos b = p.offset(dir.rotateY());
            BlockPos diagonal = a.offset(dir.rotateY());
            if (clear(a) && clear(b) && canStand(diagonal)) {
                out.add(new Step(diagonal, isWater(diagonal) ? 3.5D : 1.414D));
            }
        }
        if (isWater(p) && (canStand(p.up()) || isWater(p.up()))) {
            out.add(new Step(p.up(), 2.0D)); // swim up
        }
        return out;
    }

    private boolean clear(BlockPos p) {
        return passable(p) && passable(p.up());
    }

    /** Can a player stand with their feet in this block? */
    public boolean canStand(BlockPos p) {
        if (p.getY() < 1 || p.getY() > 255 || !world.isBlockLoaded(p)) {
            return false;
        }
        if (!passable(p) || !passable(p.up())) {
            return false;
        }
        if (isWater(p)) {
            return true; // swimming
        }
        BlockPos below = p.down();
        IBlockState floor = world.getBlockState(below);
        if (isDangerous(floor)) {
            return false;
        }
        AxisAlignedBB box = floor.getCollisionBoundingBox(world, below);
        // Full blocks, slabs, stairs, farmland... but not fences and walls, which players cannot stand on easily.
        return box != null && box.maxY > 0.4D && box.maxY <= 1.0D;
    }

    /** Can a player's body pass through this block? */
    public boolean passable(BlockPos p) {
        if (p.getY() > 255) {
            return true;
        }
        if (p.getY() < 0 || !world.isBlockLoaded(p)) {
            return false;
        }
        IBlockState state = world.getBlockState(p);
        Material material = state.getMaterial();
        if (material == Material.LAVA || material == Material.FIRE || material == Material.WEB
                || material == Material.CACTUS || material == Material.PORTAL) {
            return false;
        }
        if (material == Material.WATER) {
            return true;
        }
        return state.getBlock().isPassable(world, p) || state.getCollisionBoundingBox(world, p) == null;
    }

    private boolean isWater(BlockPos p) {
        return world.getBlockState(p).getMaterial() == Material.WATER;
    }

    private static boolean isDangerous(IBlockState floor) {
        Material m = floor.getMaterial();
        return m == Material.LAVA || m == Material.FIRE || m == Material.CACTUS || floor.getBlock() == Blocks.MAGMA;
    }
}
