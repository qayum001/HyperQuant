package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.eyes.Observer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Working inside an open screen (chest, furnace, crafting table, modded machine). Slot numbers are the
 * container's own, as listed in the observation's "container" section.
 */
final class ContainerActions {
    private ContainerActions() {}

    private static Slot slot(AgentPlayer agent, Args args, String key) {
        int index = args.integer(key);
        Container container = agent.openContainer;
        if (index < 0 || index >= container.inventorySlots.size()) {
            throw new IllegalArgumentException(key + " must be a slot number between 0 and " + (container.inventorySlots.size() - 1));
        }
        return container.getSlot(index);
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : stack.getCount() + " x " + stack.getDisplayName();
    }

    /** container_click {slot, button?, mode?}: a raw mouse click on a slot. */
    static final class Click extends Action {
        private final int slot;
        private final int button;
        private final ClickType mode;

        Click(AgentPlayer agent, Args args) {
            super("container_click", agent, 5);
            this.slot = args.integer("slot");
            if (slot != -999 && (slot < 0 || slot >= agent.openContainer.inventorySlots.size())) {
                throw new IllegalArgumentException("no slot " + slot + " in the open container");
            }
            String m = args.string("mode", "pickup").toUpperCase(Locale.ROOT);
            try {
                this.mode = ClickType.valueOf(m);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("mode must be pickup, quick_move, swap, throw or pickup_all");
            }
            if (mode == ClickType.CLONE || mode == ClickType.QUICK_CRAFT) {
                throw new IllegalArgumentException("mode " + m.toLowerCase(Locale.ROOT) + " is not supported");
            }
            this.button = args.integer("button", 0, 0, 8);
        }

        @Override
        protected void update() {
            Inventories.click(agent, slot, button, mode);
            data.add("container", Observer.container(agent));
            succeed("clicked slot " + slot + "; holding " + describe(agent.inventory.getItemStack()) + " on the cursor");
        }
    }

    /** transfer {from, to, count?}: move items between two slots of the open container. */
    static final class Transfer extends Action {
        private final Slot from;
        private final Slot to;
        private final int count;

        Transfer(AgentPlayer agent, Args args) {
            super("transfer", agent, 5);
            this.from = slot(agent, args, "from");
            this.to = slot(agent, args, "to");
            this.count = args.integer("count", 0, 0, 64);
        }

        @Override
        protected void update() {
            Inventories.returnCursor(agent);
            if (!from.getHasStack()) {
                fail("slot " + from.slotNumber + " is empty");
                return;
            }
            ItemStack before = from.getStack().copy();
            int available = before.getCount();
            Inventories.click(agent, from.slotNumber, 0, ClickType.PICKUP);
            if (count == 0 || count >= available) {
                Inventories.click(agent, to.slotNumber, 0, ClickType.PICKUP); // drop the whole stack
            } else {
                for (int i = 0; i < count; i++) {
                    Inventories.click(agent, to.slotNumber, 1, ClickType.PICKUP); // right click: one item
                }
            }
            if (!agent.inventory.getItemStack().isEmpty()) {
                Inventories.click(agent, from.slotNumber, 0, ClickType.PICKUP); // put the rest back
            }
            Inventories.returnCursor(agent);
            data.add("container", Observer.container(agent));
            ItemStack after = from.getStack();
            int moved = available - (ItemStack.areItemsEqual(after, before) ? after.getCount() : 0);
            if (moved <= 0) {
                fail("nothing moved; slot " + to.slotNumber + " does not accept " + before.getDisplayName() + " or is full");
            } else {
                succeed("moved " + moved + " x " + before.getDisplayName() + " from slot " + from.slotNumber
                        + " to slot " + to.slotNumber + " (now holds " + describe(to.getStack()) + ")");
            }
        }
    }

    /** quick_move {slot}: shift-click a slot. */
    static final class QuickMove extends Action {
        private final Slot slot;

        QuickMove(AgentPlayer agent, Args args) {
            super("quick_move", agent, 5);
            this.slot = slot(agent, args, "slot");
        }

        @Override
        protected void update() {
            ItemStack before = slot.getStack().copy();
            if (before.isEmpty()) {
                fail("slot " + slot.slotNumber + " is empty");
                return;
            }
            Inventories.click(agent, slot.slotNumber, 0, ClickType.QUICK_MOVE);
            data.add("container", Observer.container(agent));
            if (ItemStack.areItemStacksEqual(before, slot.getStack())) {
                fail("nothing moved (no room, or the item does not fit anywhere)");
            } else {
                succeed("shift-clicked " + describe(before) + " out of slot " + slot.slotNumber);
            }
        }
    }

    /** close_container: press Escape. */
    static final class Close extends Action {
        Close(AgentPlayer agent, Args args) {
            super("close_container", agent, 5);
        }

        @Override
        protected void update() {
            boolean open = agent.openContainer != agent.inventoryContainer;
            Inventories.closeScreen(agent);
            succeed(open ? "closed the screen" : "no screen was open");
        }
    }

    /**
     * craft {item, count?}: fill the open crafting grid (inventory 2x2, or a crafting table) from the inventory
     * and take the result, one craft per tick, like a player clicking.
     */
    static final class Craft extends Action {
        private final ItemQuery item;
        private final int wanted;
        private int crafted;
        @Nullable
        private String outputName;

        Craft(AgentPlayer agent, Args args) {
            super("craft", agent, 20 * 30);
            this.item = ItemQuery.from(args, "item");
            this.wanted = args.integer("count", 1, 1, 640);
        }

        @Override
        protected void update() {
            Crafting.Grid grid = Crafting.findGrid(agent.openContainer);
            if (grid == null) {
                fail("the open screen has no crafting grid; close_container, or use_block a crafting table");
                return;
            }
            Inventories.returnCursor(agent);
            if (!clearGrid(grid)) {
                fail("the inventory is full, so the crafting grid cannot be emptied");
                return;
            }
            if (crafted >= wanted) {
                succeed("crafted " + crafted + " x " + outputName);
                return;
            }
            List<IRecipe> recipes = Crafting.recipesFor(item);
            if (recipes.isEmpty()) {
                fail(soFar() + "no crafting recipe makes " + item + " (it may be smelted or the name is wrong; try lookup_recipe)");
                return;
            }
            List<Slot> sources = Inventories.playerSlots(agent.openContainer, agent.inventory);
            Crafting.Plan plan = null;
            Crafting.Plan firstFitting = null;
            for (IRecipe recipe : recipes) {
                if (!recipe.canFit(grid.width, grid.height)) {
                    continue;
                }
                Crafting.Plan p = Crafting.plan(recipe, grid.width, sources);
                if (p.ok()) {
                    plan = p;
                    break;
                }
                if (firstFitting == null) {
                    firstFitting = p;
                }
            }
            if (plan == null) {
                if (firstFitting != null) {
                    fail(soFar() + "missing ingredients for " + item + ": " + String.join(", ", firstFitting.missing));
                } else {
                    fail(soFar() + item + " needs a 3x3 crafting table; walk to one and use_block it first");
                }
                return;
            }
            for (int[] placement : plan.placements) {
                Slot cell = placement[0] < grid.cells.length ? grid.cells[placement[0]] : null;
                if (cell == null) {
                    fail("unexpected crafting grid layout");
                    return;
                }
                Inventories.click(agent, placement[1], 0, ClickType.PICKUP);
                Inventories.click(agent, cell.slotNumber, 1, ClickType.PICKUP);
                Inventories.click(agent, placement[1], 0, ClickType.PICKUP);
                Inventories.returnCursor(agent);
            }
            ItemStack result = grid.result.getStack().copy();
            if (result.isEmpty() || !item.matches(result)) {
                fail("placed the ingredients but the grid shows " + describe(result) + " instead of " + item);
                return;
            }
            int before = Inventories.count(agent.inventory, item);
            Inventories.click(agent, grid.result.slotNumber, 0, ClickType.QUICK_MOVE);
            int made = Inventories.count(agent.inventory, item) - before;
            if (made <= 0) {
                fail(soFar() + "could not take the crafted " + result.getDisplayName() + " (inventory full?)");
                return;
            }
            crafted += made;
            outputName = result.getDisplayName();
            data.addProperty("crafted", crafted);
        }

        private String soFar() {
            return crafted > 0 ? "crafted " + crafted + " x " + outputName + ", then: " : "";
        }

        /** Shift-clicks everything out of the grid; false if something could not be moved. */
        private boolean clearGrid(Crafting.Grid grid) {
            for (Slot cell : grid.cells) {
                if (cell != null && cell.getHasStack()) {
                    Inventories.click(agent, cell.slotNumber, 0, ClickType.QUICK_MOVE);
                    if (cell.getHasStack()) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        protected void cleanup() {
            Crafting.Grid grid = Crafting.findGrid(agent.openContainer);
            if (grid != null) {
                clearGrid(grid);
            }
            Inventories.returnCursor(agent);
        }
    }
}
