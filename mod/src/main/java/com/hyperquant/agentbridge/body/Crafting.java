package com.hyperquant.agentbridge.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.hyperquant.agentbridge.eyes.Describe;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe knowledge (what a player looks up in JEI) and the bookkeeping for filling a crafting grid by hand.
 * Works with any container that uses vanilla crafting slots, including most modded crafting tables.
 */
public final class Crafting {
    private Crafting() {}

    public static final class Grid {
        public final Slot result;
        public final Slot[] cells;
        public final int width;
        public final int height;

        Grid(Slot result, Slot[] cells, int width, int height) {
            this.result = result;
            this.cells = cells;
            this.width = width;
            this.height = height;
        }
    }

    /** The crafting grid of an open container (the 2x2 inventory grid, a crafting table...), or null. */
    @Nullable
    public static Grid findGrid(Container container) {
        Slot result = null;
        InventoryCrafting matrix = null;
        List<Slot> cellSlots = new ArrayList<>();
        for (Slot slot : container.inventorySlots) {
            if (slot instanceof SlotCrafting) {
                if (result == null) {
                    result = slot;
                }
            } else if (slot.inventory instanceof InventoryCrafting) {
                matrix = (InventoryCrafting) slot.inventory;
                cellSlots.add(slot);
            }
        }
        if (result == null || matrix == null) {
            return null;
        }
        Slot[] cells = new Slot[matrix.getSizeInventory()];
        for (Slot slot : cellSlots) {
            if (slot.inventory == matrix && slot.getSlotIndex() < cells.length) {
                cells[slot.getSlotIndex()] = slot;
            }
        }
        return new Grid(result, cells, matrix.getWidth(), matrix.getHeight());
    }

    public static List<IRecipe> recipesFor(ItemQuery query) {
        List<IRecipe> out = new ArrayList<>();
        for (IRecipe recipe : ForgeRegistries.RECIPES.getValuesCollection()) {
            if (recipe.isDynamic()) {
                continue;
            }
            ItemStack output = recipe.getRecipeOutput();
            if (!output.isEmpty() && query.matches(output)) {
                out.add(recipe);
            }
        }
        return out;
    }

    public static final class Plan {
        /** {grid cell index, container slot number holding the ingredient} */
        public final List<int[]> placements = new ArrayList<>();
        public final List<String> missing = new ArrayList<>();

        public boolean ok() {
            return missing.isEmpty() && !placements.isEmpty();
        }
    }

    /** Decides which inventory slot supplies each ingredient for one craft. */
    public static Plan plan(IRecipe recipe, int gridWidth, List<Slot> sources) {
        Plan plan = new Plan();
        List<Ingredient> ingredients = recipe.getIngredients();
        boolean shaped = recipe instanceof IShapedRecipe;
        int recipeWidth = shaped ? ((IShapedRecipe) recipe).getRecipeWidth() : gridWidth;
        Map<Slot, Integer> left = new HashMap<>();
        for (Slot s : sources) {
            left.put(s, s.getStack().getCount());
        }
        int nextCell = 0;
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (isBlank(ingredient)) {
                continue;
            }
            int cell = shaped ? (i / recipeWidth) * gridWidth + (i % recipeWidth) : nextCell++;
            Slot source = null;
            for (Slot s : sources) {
                if (left.get(s) > 0 && ingredient.apply(s.getStack())) {
                    source = s;
                    break;
                }
            }
            if (source == null) {
                plan.missing.add(describe(ingredient));
                continue;
            }
            left.merge(source, -1, Integer::sum);
            plan.placements.add(new int[]{cell, source.slotNumber});
        }
        return plan;
    }

    private static boolean isBlank(Ingredient ingredient) {
        return ingredient == Ingredient.EMPTY || ingredient.apply(ItemStack.EMPTY);
    }

    public static String describe(Ingredient ingredient) {
        ItemStack[] options = ingredient.getMatchingStacks();
        if (options.length == 0) {
            return "(an ingredient no item satisfies)";
        }
        String name = displayable(options[0]).getDisplayName();
        return options.length > 1 ? name + " (or similar)" : name;
    }

    /** Ore-dictionary ingredients use a wildcard damage value that has no name of its own. */
    private static ItemStack displayable(ItemStack stack) {
        if (stack.getMetadata() == OreDictionary.WILDCARD_VALUE) {
            ItemStack copy = stack.copy();
            copy.setItemDamage(0);
            return copy;
        }
        return stack;
    }

    public static JsonObject recipeJson(IRecipe recipe) {
        JsonObject o = new JsonObject();
        o.add("output", Describe.item(recipe.getRecipeOutput()));
        boolean shaped = recipe instanceof IShapedRecipe;
        o.addProperty("shaped", shaped);
        if (shaped) {
            o.addProperty("width", ((IShapedRecipe) recipe).getRecipeWidth());
            o.addProperty("height", ((IShapedRecipe) recipe).getRecipeHeight());
        }
        o.addProperty("needs_crafting_table", !recipe.canFit(2, 2));
        JsonArray slots = new JsonArray();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (isBlank(ingredient)) {
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject ing = new JsonObject();
            String name = describe(ingredient);
            ing.addProperty("name", name);
            JsonArray options = new JsonArray();
            ItemStack[] stacks = ingredient.getMatchingStacks();
            for (int i = 0; i < stacks.length && i < 4; i++) {
                options.add(Describe.itemId(stacks[i]) + (stacks[i].getHasSubtypes()
                        && stacks[i].getMetadata() != OreDictionary.WILDCARD_VALUE ? "@" + stacks[i].getMetadata() : ""));
            }
            ing.add("options", options);
            slots.add(ing);
            totals.merge(name, 1, Integer::sum);
        }
        o.add("ingredients", slots);
        JsonObject totalsJson = new JsonObject();
        totals.forEach(totalsJson::addProperty);
        o.add("totals", totalsJson);
        return o;
    }

    public static JsonArray smeltingFor(ItemQuery query) {
        JsonArray out = new JsonArray();
        for (Map.Entry<ItemStack, ItemStack> e : FurnaceRecipes.instance().getSmeltingList().entrySet()) {
            if (query.matches(e.getValue())) {
                JsonObject o = new JsonObject();
                o.add("input", Describe.item(displayable(e.getKey())));
                o.add("output", Describe.item(e.getValue()));
                out.add(o);
            }
        }
        return out;
    }
}
