package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.eyes.Describe;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Walks the avatar along a planned path by "holding keys": face the next waypoint, press forward, jump when
 * the next step is higher. Re-plans when the agent gets stuck or pushed off course.
 */
public final class Navigator {
    public enum State { MOVING, ARRIVED, FAILED }

    private static final int MAX_REPLANS = 5;
    private static final int STUCK_TICKS = 40;

    private final AgentPlayer agent;
    private BlockPos goal;
    private double range;
    private boolean sprint;

    @Nullable
    private List<BlockPos> path;
    private boolean pathComplete;
    private int index;
    private int replans;
    private double bestProgress = Double.MAX_VALUE;
    private int ticksWithoutProgress;
    private String failure = "";

    public Navigator(AgentPlayer agent) {
        this.agent = agent;
    }

    /** Sets (or moves) the destination. Re-plans only if the goal actually changed. */
    public void setGoal(BlockPos goal, double range, boolean sprint) {
        if (goal.equals(this.goal) && range == this.range) {
            this.sprint = sprint;
            return;
        }
        this.goal = goal;
        this.range = range;
        this.sprint = sprint;
        this.path = null;
        this.replans = 0;
    }

    public String failure() {
        return failure;
    }

    public boolean arrived() {
        double dx = agent.posX - (goal.getX() + 0.5D);
        double dz = agent.posZ - (goal.getZ() + 0.5D);
        double dy = agent.posY - goal.getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (range < 1.0D) {
            return horizontal < 0.6D && dy > -0.6D && dy < 1.0D;
        }
        return Math.sqrt(horizontal * horizontal + dy * dy) <= range + 0.5D;
    }

    public State tick() {
        if (arrived()) {
            agent.clearMovement();
            return State.ARRIVED;
        }
        if (path == null) {
            if (!agent.onGround && !agent.isInWater()) {
                agent.clearMovement(); // wait to land before planning
                return State.MOVING;
            }
            if (!plan()) {
                return State.FAILED;
            }
        }
        if (index >= path.size()) {
            if (pathComplete && replans < MAX_REPLANS) {
                replans++;
                path = null;
                return State.MOVING;
            }
            agent.clearMovement();
            failure = "no walkable path to " + Describe.pos(goal) + "; stopped at the closest reachable point "
                    + Describe.pos(agent.getPosition()) + String.format(" (%.1f blocks away)", Math.sqrt(agent.getDistanceSqToCenter(goal)));
            return State.FAILED;
        }
        return follow();
    }

    private boolean plan() {
        BlockPos start = new BlockPos(agent.posX, agent.posY + 0.2D, agent.posZ);
        Pathfinder.Path found = new Pathfinder(agent.world).find(start, goal, range);
        if (found == null) {
            failure = "no walkable path from " + Describe.pos(start) + " towards " + Describe.pos(goal)
                    + " (blocked, too far, or in unloaded terrain)";
            return false;
        }
        path = found.nodes;
        pathComplete = found.complete;
        index = 0;
        bestProgress = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        return true;
    }

    private State follow() {
        BlockPos waypoint = path.get(index);
        double dx = waypoint.getX() + 0.5D - agent.posX;
        double dz = waypoint.getZ() + 0.5D - agent.posZ;
        double dy = waypoint.getY() - agent.posY;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean last = index == path.size() - 1;
        boolean levelOk = agent.isInWater() ? Math.abs(dy) < 1.2D : dy > -0.7D && dy < 0.6D;

        if (horizontal < (last ? 0.35D : 0.5D) && levelOk) {
            index++;
            bestProgress = Double.MAX_VALUE;
            ticksWithoutProgress = 0;
            return State.MOVING;
        }

        double progress = horizontal + Math.abs(dy);
        if (progress < bestProgress - 0.05D) {
            bestProgress = progress;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > STUCK_TICKS || horizontal > 4.0D) {
            agent.clearMovement();
            if (replans >= MAX_REPLANS) {
                failure = "got stuck near " + Describe.pos(agent.getPosition()) + " while walking to " + Describe.pos(goal);
                return State.FAILED;
            }
            replans++;
            path = null;
            return State.MOVING;
        }

        float yaw = (float) (MathHelper.atan2(dz, dx) * (180D / Math.PI)) - 90F;
        agent.face(yaw, 0F);
        agent.forwardIntent = last && horizontal < 1.0D ? (float) Math.max(0.3D, horizontal) : 1.0F;
        boolean climb = dy > 0.5D;
        agent.jumpIntent = (climb && agent.onGround && horizontal < 1.8D)
                || (agent.isInWater() && dy > -0.8D)
                || (agent.collidedHorizontally && agent.onGround && dy > -0.5D);
        agent.sprintIntent = sprint && path.size() - index > 3;
        return State.MOVING;
    }
}
