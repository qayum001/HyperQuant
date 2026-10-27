package com.hyperquant.agentbridge.body;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which item the brain means: a registry id ("minecraft:log"), an id with metadata ("minecraft:log@2"),
 * a bare path ("log") or a display name ("Birch Wood").
 */
public final class ItemQuery {
    private static final Pattern WITH_META = Pattern.compile("^(.+?)@(\\d+)$");

    private final String text;
    private final int meta;

    private ItemQuery(String text, int meta) {
        this.text = text;
        this.meta = meta;
    }

    public static ItemQuery parse(String text, int meta) {
        String t = text.trim();
        Matcher m = WITH_META.matcher(t);
        if (m.matches()) {
            return new ItemQuery(m.group(1), Integer.parseInt(m.group(2)));
        }
        return new ItemQuery(t, meta);
    }

    public static ItemQuery from(Args args, String key) {
        return parse(args.string(key), args.integer("meta", -1));
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (meta >= 0 && stack.getMetadata() != meta) {
            return false;
        }
        ResourceLocation id = stack.getItem().getRegistryName();
        if (id != null) {
            if (id.toString().equalsIgnoreCase(text)) {
                return true;
            }
            if (!text.contains(":") && id.getPath().equalsIgnoreCase(text)) {
                return true;
            }
        }
        return stack.getDisplayName().equalsIgnoreCase(text);
    }

    @Override
    public String toString() {
        return meta >= 0 ? text + "@" + meta : text;
    }
}
