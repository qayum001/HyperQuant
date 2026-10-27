package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.eyes.Observer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;

/** Hitting and right-clicking entities. */
final class EntityActions {
    private EntityActions() {}

    private static Entity resolve(AgentPlayer agent, Args args) {
        Entity e = agent.world.getEntityByID(args.integer("entity_id"));
        if (e == null || e.isDead) {
            throw new IllegalArgumentException("no entity with id " + args.integer("entity_id") + " (it may have despawned)");
        }
        if (e == agent) {
            throw new IllegalArgumentException("that is the agent itself");
        }
        return e;
    }

    /** attack {entity_id, times?}: swing at an entity, waiting for the attack cooldown like a careful player. */
    static final class Attack extends Action {
        private final Entity target;
        private final int times;
        private int hits;

        Attack(AgentPlayer agent, Args args) {
            super("attack", agent, 20 * 30);
            this.target = resolve(agent, args);
            this.times = args.integer("times", 1, 1, 50);
            if (target instanceof EntityItem || target instanceof EntityXPOrb || target instanceof EntityArrow) {
                throw new IllegalArgumentException("that entity cannot be attacked");
            }
        }

        @Override
        protected void update() {
            if (target.isDead || (target instanceof EntityLivingBase && ((EntityLivingBase) target).getHealth() <= 0F)) {
                data.addProperty("hits", hits);
                succeed(target.getName() + " is dead after " + hits + " hit(s)");
                return;
            }
            String problem = Interaction.entityProblem(agent, target);
            if (problem != null) {
                data.addProperty("hits", hits);
                fail(problem);
                return;
            }
            agent.lookAt(target.posX, target.posY + target.height * 0.5D, target.posZ);
            if (agent.getCooledAttackStrength(0.5F) < 1.0F) {
                return; // a full-strength hit needs the cooldown to recharge
            }
            agent.attackTargetEntityWithCurrentItem(target);
            agent.swingArm(EnumHand.MAIN_HAND);
            agent.markPlayerActive();
            hits++;
            if (hits >= times) {
                data.addProperty("hits", hits);
                if (target instanceof EntityLivingBase) {
                    data.addProperty("target_health", ((EntityLivingBase) target).getHealth());
                }
                succeed("hit " + target.getName() + " " + hits + " time(s)");
            }
        }
    }

    /** interact_entity {entity_id}: right-click an entity (trade, feed, shear, milk, ride...). */
    static final class Interact extends Action {
        private final Entity target;

        Interact(AgentPlayer agent, Args args) {
            super("interact_entity", agent, 5);
            this.target = resolve(agent, args);
        }

        @Override
        protected void update() {
            String problem = Interaction.entityProblem(agent, target);
            if (problem != null) {
                fail(problem);
                return;
            }
            agent.lookAt(target.posX, target.posY + target.height * 0.5D, target.posZ);
            for (EnumHand hand : EnumHand.values()) {
                if (agent.interactOn(target, hand) == EnumActionResult.SUCCESS) {
                    agent.swingArm(hand);
                    if (agent.openContainer != agent.inventoryContainer) {
                        data.add("container", Observer.container(agent));
                        succeed("opened " + target.getName() + "'s screen; its slots are listed in data.container");
                    } else {
                        succeed("interacted with " + target.getName());
                    }
                    return;
                }
            }
            fail("right-clicking " + target.getName() + " did nothing (maybe it needs a specific item in hand)");
        }
    }
}
