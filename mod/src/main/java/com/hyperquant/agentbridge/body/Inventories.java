package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Inventory handling the way a player does it: hotbar selection and clicks on container slots. */
public final class Inventories {
    public static final int HOTBAR = 9;
    public static final int MAIN = 36; // hotbar 0-8 + main 9-35

    private Inventories() {}

    /** Index (0-35) of the first stack matching the query, hotbar first; -1 if none. */
    public static int find(InventoryPlayer inventory, ItemQuery query) {
        for (int i = 0; i < MAIN; i++) {
            if (query.matches(inventory.mainInventory.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int count(InventoryPlayer inventory, ItemQuery query) {
        int n = 0;
        for (int i = 0; i < MAIN; i++) {
            ItemStack stack = inventory.mainInventory.get(i);
            if (query.matches(stack)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /** Slot of the player's own inventory container that shows inventory index 0-35. */
    public static int playerContainerSlot(int inventoryIndex) {
        return inventoryIndex < HOTBAR ? 36 + inventoryIndex : inventoryIndex;
    }

    /** The open container's slots that belong to the player's main inventory (not armor or off hand). */
    public static List<Slot> playerSlots(Container container, InventoryPlayer inventory) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : container.inventorySlots) {
            if (slot.inventory == inventory && slot.getSlotIndex() < MAIN) {
                out.add(slot);
            }
        }
        return out;
    }

    /** A slot click, exactly what the server does when a client clicks in a GUI. */
    public static ItemStack click(AgentPlayer agent, int slot, int button, ClickType type) {
        Container container = agent.openContainer;
        ItemStack result = container.slotClick(slot, button, type, agent);
        container.detectAndSendChanges();
        return result;
    }

    /** Puts whatever is held on the mouse cursor back into the inventory, dropping what does not fit. */
    public static void returnCursor(AgentPlayer agent) {
        ItemStack cursor = agent.inventory.getItemStack();
        if (cursor.isEmpty()) {
            return;
        }
        agent.inventory.setItemStack(ItemStack.EMPTY);
        if (!agent.inventory.addItemStackToInventory(cursor) || !cursor.isEmpty()) {
            agent.dropItem(cursor, false);
        }
    }

    /** Closes any open GUI, like pressing Escape. */
    public static void closeScreen(AgentPlayer agent) {
        if (agent.openContainer != agent.inventoryContainer) {
            agent.closeScreen();
        }
        returnCursor(agent);
    }

    /**
     * Gets the item into the main hand: selects it if it is on the hotbar, otherwise swaps it into the selected
     * hotbar slot from the inventory screen (number-key swap). Returns null on success, else the problem.
     */
    @Nullable
    public static String holdInMainHand(AgentPlayer agent, ItemQuery query) {
        InventoryPlayer inv = agent.inventory;
        if (query.matches(inv.getCurrentItem())) {
            return null;
        }
        int index = find(inv, query);
        if (index < 0) {
            return "no " + query + " in the inventory";
        }
        if (index < HOTBAR) {
            inv.currentItem = index;
            agent.markPlayerActive();
            return null;
        }
        closeScreen(agent);
        click(agent, playerContainerSlot(index), inv.currentItem, ClickType.SWAP);
        return query.matches(inv.getCurrentItem()) ? null : "could not move " + query + " to the hotbar";
    }

    /** Selects the hotbar item that breaks this block fastest (and can harvest it, if any can). */
    public static void selectBestTool(AgentPlayer agent, IBlockState state) {
        InventoryPlayer inv = agent.inventory;
        int best = inv.currentItem;
        float bestScore = toolScore(inv.mainInventory.get(best), state);
        for (int i = 0; i < HOTBAR; i++) {
            float score = toolScore(inv.mainInventory.get(i), state);
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        if (best != inv.currentItem) {
            inv.currentItem = best;
            agent.markPlayerActive();
        }
    }

    private static float toolScore(ItemStack stack, IBlockState state) {
        float speed = stack.isEmpty() ? 1F : stack.getDestroySpeed(state);
        boolean harvests = state.getMaterial().isToolNotRequired() || (!stack.isEmpty() && stack.canHarvestBlock(state));
        return (harvests ? 1000F : 0F) + speed;
    }

    public static void swapHands(AgentPlayer agent) {
        ItemStack off = agent.getHeldItem(EnumHand.OFF_HAND);
        agent.setHeldItem(EnumHand.OFF_HAND, agent.getHeldItem(EnumHand.MAIN_HAND));
        agent.setHeldItem(EnumHand.MAIN_HAND, off);
        agent.resetActiveHand();
    }
}
