package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.eyes.Describe;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/** Getting around: walking, looking, waiting, picking up dropped items. */
final class MovementActions {
    private MovementActions() {}

    /** move_to {x,y,z | entity_id, range?, sprint?} */
    static final class MoveTo extends Action {
        private final Navigator navigator;
        @Nullable
        private final Entity entity;
        private BlockPos target;
        private final double range;
        private final boolean sprint;

        MoveTo(AgentPlayer agent, Args args) {
            super("move_to", agent, 20 * 120);
            this.navigator = new Navigator(agent);
            this.sprint = args.bool("sprint", false);
            if (args.has("entity_id")) {
                entity = agent.world.getEntityByID(args.integer("entity_id"));
                if (entity == null || entity.isDead) {
                    throw new IllegalArgumentException("no entity with id " + args.integer("entity_id"));
                }
                target = new BlockPos(entity);
                range = Math.max(1.0D, args.number("range", 2.0D));
            } else {
                entity = null;
                target = args.blockPos();
                double wanted = args.number("range", 0.0D);
                // A solid target (a tree, a chest) cannot be stood in; stop next to it instead.
                boolean standable = new Pathfinder(agent.world).canStand(target);
                range = standable ? wanted : Math.max(wanted, 1.5D);
                if (!standable) {
                    data.addProperty("note", "target block is solid, walking next to it");
                }
            }
            navigator.setGoal(target, range, sprint);
        }

        @Override
        protected void update() {
            if (entity != null) {
                if (entity.isDead) {
                    fail(entity.getName() + " is gone");
                    return;
                }
                BlockPos now = new BlockPos(entity);
                if (now.distanceSq(target) > 4) {
                    target = now;
                    navigator.setGoal(target, range, sprint);
                }
            }
            switch (navigator.tick()) {
                case ARRIVED:
                    data.add("position", Describe.posJson(agent.getPosition()));
                    succeed("arrived at " + Describe.pos(agent.getPosition()));
                    break;
                case FAILED:
                    data.add("position", Describe.posJson(agent.getPosition()));
                    fail(navigator.failure());
                    break;
                default:
                    break;
            }
        }
    }

    /** collect_items {radius?, max?}: walk over visible dropped items so they get picked up. */
    static final class CollectItems extends Action {
        private final Navigator navigator;
        private final double radius;
        private final int max;
        private final Map<Integer, Integer> attempts = new HashMap<>();
        @Nullable
        private EntityItem current;
        private int picked;

        CollectItems(AgentPlayer agent, Args args) {
            super("collect_items", agent, 20 * 90);
            this.navigator = new Navigator(agent);
            this.radius = Math.min(16D, args.number("radius", 8D));
            this.max = args.integer("max", 64, 1, 1000);
        }

        @Override
        protected void update() {
            if (current != null && current.isDead) {
                picked++;
                current = null;
            }
            if (picked >= max) {
                finishCollecting();
                return;
            }
            if (current == null) {
                current = nearestVisibleItem();
                if (current == null) {
                    finishCollecting();
                    return;
                }
                attempts.merge(current.getEntityId(), 1, Integer::sum);
            }
            navigator.setGoal(new BlockPos(current), 0.0D, false);
            Navigator.State state = navigator.tick();
            if (state == Navigator.State.FAILED || (state == Navigator.State.ARRIVED && ticks() % 20 == 0)) {
                // Could not get there, or standing on it without picking it up (inventory full?): try another.
                current = null;
            }
        }

        private void finishCollecting() {
            data.addProperty("picked_up_stacks", picked);
            if (picked == 0) {
                fail(attempts.isEmpty() ? "no dropped items visible within " + (int) radius + " blocks"
                        : "could not pick up the visible items (unreachable or inventory full)");
            } else {
                succeed("picked up " + picked + " item stack(s)");
            }
        }

        @Nullable
        private EntityItem nearestVisibleItem() {
            EntityItem best = null;
            double bestDistance = Double.MAX_VALUE;
            for (EntityItem item : agent.world.getEntitiesWithinAABB(EntityItem.class, agent.getEntityBoundingBox().grow(radius))) {
                if (item.isDead || attempts.getOrDefault(item.getEntityId(), 0) >= 2 || !agent.canEntityBeSeen(item)) {
                    continue;
                }
                double d = agent.getDistanceSq(item);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = item;
                }
            }
            return best;
        }
    }

    /** look {x,y,z | entity_id | yaw,pitch} */
    static final class Look extends Action {
        Look(AgentPlayer agent, Args args) {
            super("look", agent, 5);
            if (args.has("entity_id")) {
                Entity e = agent.world.getEntityByID(args.integer("entity_id"));
                if (e == null) {
                    throw new IllegalArgumentException("no entity with id " + args.integer("entity_id"));
                }
                agent.lookAt(e.posX, e.posY + e.getEyeHeight(), e.posZ);
            } else if (args.hasPos()) {
                BlockPos p = args.blockPos();
                agent.lookAt(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
            } else if (args.has("yaw")) {
                agent.face((float) args.number("yaw", 0), (float) args.number("pitch", 0));
            } else {
                throw new IllegalArgumentException("give x,y,z or entity_id or yaw/pitch");
            }
        }

        @Override
        protected void update() {
            data.addProperty("yaw", Describe.round(agent.rotationYaw, 1));
            data.addProperty("pitch", Describe.round(agent.rotationPitch, 1));
            data.addProperty("facing", agent.getHorizontalFacing().getName());
            succeed("now facing " + agent.getHorizontalFacing().getName());
        }
    }

    /** wait {ticks} */
    static final class Wait extends Action {
        private final int duration;

        Wait(AgentPlayer agent, Args args) {
            super("wait", agent, 1000);
            this.duration = args.integer("ticks", 20, 1, 600);
        }

        @Override
        protected void update() {
            if (ticks() >= duration) {
                succeed("waited " + duration + " ticks");
            }
        }
    }
}
