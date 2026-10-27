package com.hyperquant.agentbridge.eyes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Turns game objects into compact JSON / short names for the agent's senses and action results. */
public final class Describe {
    private static final String[] COMPASS = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
    private static final String[] RELATIVE = {"ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"};
    private static final Map<IBlockState, String> BLOCK_NAMES = new ConcurrentHashMap<>();

    private Describe() {}

    public static String pos(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    public static JsonArray posJson(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    public static String blockId(IBlockState state) {
        ResourceLocation id = state.getBlock().getRegistryName();
        return id == null ? "unknown" : id.toString();
    }

    public static int blockMeta(IBlockState state) {
        return state.getBlock().getMetaFromState(state);
    }

    /** Display name of a block state, e.g. "Birch Wood Planks" rather than "Wooden Planks". */
    public static String blockName(IBlockState state) {
        return BLOCK_NAMES.computeIfAbsent(state, Describe::computeBlockName);
    }

    private static String computeBlockName(IBlockState state) {
        Block block = state.getBlock();
        try {
            Item item = Item.getItemFromBlock(block);
            if (item != Items.AIR) {
                ItemStack stack = new ItemStack(item, 1, block.damageDropped(state));
                String name = stack.getDisplayName();
                if (!name.isEmpty() && !name.startsWith("tile.") && !name.startsWith("item.")) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
            // some modded blocks do not like being turned into items; fall back to the block's name
        }
        String name = block.getLocalizedName();
        return name.startsWith("tile.") ? blockId(state) : name;
    }

    public static String itemId(ItemStack stack) {
        ResourceLocation id = stack.getItem().getRegistryName();
        return id == null ? "unknown" : id.toString();
    }

    public static JsonObject item(ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("id", itemId(stack));
        if (stack.getHasSubtypes() || stack.getMetadata() != 0 && !stack.isItemStackDamageable()) {
            o.addProperty("meta", stack.getMetadata());
        }
        o.addProperty("name", stack.getDisplayName());
        o.addProperty("count", stack.getCount());
        if (stack.isItemStackDamageable()) {
            o.addProperty("durability", (stack.getMaxDamage() - stack.getItemDamage()) + "/" + stack.getMaxDamage());
        }
        if (stack.isItemEnchanted()) {
            o.addProperty("enchanted", true);
        }
        return o;
    }

    public static String entityType(Entity entity) {
        if (entity instanceof EntityPlayer) {
            return "minecraft:player";
        }
        ResourceLocation id = EntityList.getKey(entity);
        return id == null ? entity.getClass().getSimpleName() : id.toString();
    }

    /** Compass direction from one point to another ("north-east"). */
    public static String compass(double dx, double dz) {
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180D / Math.PI)) - 90F;
        return COMPASS[octant(yaw)];
    }

    /** Direction relative to where the viewer faces ("ahead-left", "behind"). */
    public static String relative(float viewerYaw, double dx, double dz) {
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180D / Math.PI)) - 90F;
        return RELATIVE[octant(yaw - viewerYaw)];
    }

    private static int octant(float yaw) {
        float wrapped = MathHelper.wrapDegrees(yaw); // -180..180, 0 = south, 90 = west
        int i = Math.round(wrapped / 45F);
        return ((i % 8) + 8) % 8;
    }

    public static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}
