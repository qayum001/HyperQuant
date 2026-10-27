package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;

import javax.annotation.Nullable;
import java.util.Locale;

/** Things done with items in hand or in the inventory. */
final class ItemActions {
    private ItemActions() {}

    /** use_item {hold_ticks?}: right-click with the held item while not aiming at a block (eat, drink, throw, bow). */
    static final class UseItem extends Action {
        private final int holdTicks;
        @Nullable
        private String used;

        UseItem(AgentPlayer agent, Args args) {
            super("use_item", agent, 20 * 15);
            this.holdTicks = args.integer("hold_ticks", 0, 0, 200);
        }

        @Override
        protected void update() {
            if (used == null) {
                for (EnumHand hand : EnumHand.values()) {
                    ItemStack stack = agent.getHeldItem(hand);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String name = stack.getDisplayName();
                    if (agent.interactionManager.processRightClick(agent, agent.world, stack, hand) == EnumActionResult.SUCCESS) {
                        used = name;
                        break;
                    }
                }
                if (used == null) {
                    fail(agent.getHeldItemMainhand().isEmpty() && agent.getHeldItemOffhand().isEmpty()
                            ? "nothing in hand to use" : "using the held item did nothing (not hungry? nothing to use it on?)");
                } else if (!agent.isHandActive()) {
                    succeed("used " + used);
                }
                return;
            }
            if (!agent.isHandActive()) {
                succeed("finished using " + used);
            } else if (holdTicks > 0 && ticks() > holdTicks) {
                agent.stopActiveHand(); // e.g. release a bow
                succeed("released " + used + " after " + holdTicks + " ticks");
            }
        }

        @Override
        protected void cleanup() {
            if (agent.isHandActive()) {
                agent.stopActiveHand();
            }
        }
    }

    /** equip {item, slot?=mainhand|offhand|head|chest|legs|feet} */
    static final class Equip extends Action {
        private final ItemQuery item;
        private final String slot;

        Equip(AgentPlayer agent, Args args) {
            super("equip", agent, 5);
            this.item = ItemQuery.from(args, "item");
            this.slot = args.string("slot", "mainhand").toLowerCase(Locale.ROOT);
        }

        @Override
        protected void update() {
            if (slot.equals("mainhand") || slot.equals("hand")) {
                String problem = Inventories.holdInMainHand(agent, item);
                if (problem != null) {
                    fail(problem);
                } else {
                    succeed("holding " + agent.getHeldItemMainhand().getDisplayName() + " in hotbar slot " + agent.inventory.currentItem);
                }
                return;
            }
            if (slot.equals("offhand")) {
                if (item.matches(agent.getHeldItemOffhand())) {
                    succeed("already holding it in the off hand");
                    return;
                }
                String problem = Inventories.holdInMainHand(agent, item);
                if (problem != null) {
                    fail(problem);
                    return;
                }
                Inventories.swapHands(agent); // the "swap hands" key
                succeed("holding " + agent.getHeldItemOffhand().getDisplayName() + " in the off hand");
                return;
            }
            int index = Inventories.find(agent.inventory, item);
            if (index < 0) {
                fail("no " + item + " in the inventory");
                return;
            }
            ItemStack stack = agent.inventory.mainInventory.get(index);
            EntityEquipmentSlot target = EntityLiving.getSlotForItemStack(stack);
            if (target.getSlotType() != EntityEquipmentSlot.Type.ARMOR) {
                fail(stack.getDisplayName() + " cannot be worn");
                return;
            }
            if (!slot.equals("armor") && !slot.equals(target.getName())) {
                fail(stack.getDisplayName() + " is worn on the " + target.getName() + ", not the " + slot);
                return;
            }
            // In the inventory screen: pick it up, click the armor slot (swaps with what is worn), put the rest back.
            Inventories.closeScreen(agent);
            int from = Inventories.playerContainerSlot(index);
            int armorSlot = 8 - target.getIndex();
            Inventories.click(agent, from, 0, ClickType.PICKUP);
            Inventories.click(agent, armorSlot, 0, ClickType.PICKUP);
            Inventories.click(agent, from, 0, ClickType.PICKUP);
            Inventories.returnCursor(agent);
            ItemStack worn = agent.getItemStackFromSlot(target);
            if (item.matches(worn)) {
                succeed("wearing " + worn.getDisplayName());
            } else {
                fail("could not equip " + stack.getDisplayName());
            }
        }
    }

    /** select_slot {slot 0-8} */
    static final class SelectSlot extends Action {
        private final int slot;

        SelectSlot(AgentPlayer agent, Args args) {
            super("select_slot", agent, 5);
            this.slot = args.integer("slot", 0, 0, 8);
        }

        @Override
        protected void update() {
            agent.inventory.currentItem = slot;
            agent.markPlayerActive();
            ItemStack held = agent.getHeldItemMainhand();
            succeed("selected hotbar slot " + slot + " (" + (held.isEmpty() ? "empty" : held.getDisplayName()) + ")");
        }
    }

    /** drop {item | slot, count?, toward_entity?}: throw items out of the inventory. */
    static final class Drop extends Action {
        @Nullable
        private final ItemQuery item;
        private final int slot;
        private final int count;
        @Nullable
        private final Entity toward;

        Drop(AgentPlayer agent, Args args) {
            super("drop", agent, 5);
            this.item = args.has("item") ? ItemQuery.from(args, "item") : null;
            this.slot = args.integer("slot", -1, -1, Inventories.MAIN - 1);
            this.count = args.integer("count", 0, 0, 64 * 36);
            if (item == null && slot < 0) {
                throw new IllegalArgumentException("give item or slot");
            }
            if (args.has("toward_entity")) {
                toward = agent.world.getEntityByID(args.integer("toward_entity"));
                if (toward == null) {
                    throw new IllegalArgumentException("no entity with id " + args.integer("toward_entity"));
                }
            } else {
                toward = null;
            }
        }

        @Override
        protected void update() {
            if (toward != null) {
                agent.lookAt(toward.posX, toward.posY + toward.getEyeHeight() * 0.5D, toward.posZ);
            }
            int remaining = count > 0 ? count : Integer.MAX_VALUE;
            int dropped = 0;
            String name = null;
            for (int i = 0; i < Inventories.MAIN && remaining > 0; i++) {
                ItemStack stack = agent.inventory.mainInventory.get(i);
                if (stack.isEmpty() || (slot >= 0 ? i != slot : !item.matches(stack))) {
                    continue;
                }
                name = stack.getDisplayName();
                int n = Math.min(remaining, stack.getCount());
                ItemStack thrown = agent.inventory.decrStackSize(i, n);
                agent.dropItem(thrown, true); // the same as throwing it out of the inventory screen
                dropped += n;
                remaining -= n;
            }
            if (dropped == 0) {
                fail(slot >= 0 ? "slot " + slot + " is empty" : "no " + item + " in the inventory");
            } else {
                data.addProperty("dropped", dropped);
                succeed("dropped " + dropped + " x " + name + (toward != null ? " toward " + toward.getName() : ""));
            }
        }
    }
}
