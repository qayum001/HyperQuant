package com.hyperquant.agentbridge.eyes;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.hyperquant.agentbridge.BridgeConfig;
import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.body.Interaction;
import com.hyperquant.agentbridge.body.Inventories;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the raw sensory snapshot the harness's eyes receive: body state, inventory, what is in view, what
 * was heard. Everything here is something a player would know from their screen; nothing comes from places
 * the agent cannot see.
 */
public final class Observer {
    private Observer() {}

    public static JsonObject observe(AgentPlayer agent, boolean panorama) {
        JsonObject o = new JsonObject();
        o.addProperty("agent", agent.getName());
        o.addProperty("tick", agent.getServerWorld().getTotalWorldTime());
        o.add("self", self(agent));
        o.add("inventory", inventory(agent));
        o.add("surroundings", surroundings(agent));
        o.add("world", world(agent));
        o.add("view", view(agent, panorama));
        o.add("container", agent.openContainer != agent.inventoryContainer ? container(agent) : JsonNull.INSTANCE);
        JsonObject action = agent.actionStatus();
        o.add("last_action", action != null ? action : JsonNull.INSTANCE);
        o.add("heard", agent.ears.recent(8, 180_000));
        o.add("recent_damage", agent.recentDamage(30_000));
        return o;
    }

    private static JsonObject self(AgentPlayer agent) {
        JsonObject o = new JsonObject();
        o.addProperty("name", agent.getName());
        JsonArray position = new JsonArray();
        position.add(Describe.round(agent.posX, 1));
        position.add(Describe.round(agent.posY, 1));
        position.add(Describe.round(agent.posZ, 1));
        o.add("position", position);
        o.add("block_position", Describe.posJson(agent.getPosition()));
        o.addProperty("facing", agent.getHorizontalFacing().getName());
        o.addProperty("yaw", Describe.round(agent.rotationYaw, 0));
        o.addProperty("pitch", Describe.round(agent.rotationPitch, 0));
        o.addProperty("health", Describe.round(agent.getHealth(), 1));
        o.addProperty("max_health", Describe.round(agent.getMaxHealth(), 1));
        o.addProperty("food", agent.getFoodStats().getFoodLevel());
        o.addProperty("saturation", Describe.round(agent.getFoodStats().getSaturationLevel(), 1));
        o.addProperty("armor", agent.getTotalArmorValue());
        o.addProperty("xp_level", agent.experienceLevel);
        if (agent.getAir() < 300) {
            o.addProperty("air", agent.getAir());
        }
        o.addProperty("gamemode", agent.interactionManager.getGameType().getName());
        o.addProperty("on_ground", agent.onGround);
        o.addProperty("in_water", agent.isInWater());
        if (agent.isInLava()) {
            o.addProperty("in_lava", true);
        }
        if (agent.isBurning()) {
            o.addProperty("burning", true);
        }
        if (agent.getHealth() <= 0F) {
            o.addProperty("dead", true);
        }
        JsonArray effects = new JsonArray();
        for (PotionEffect effect : agent.getActivePotionEffects()) {
            JsonObject e = new JsonObject();
            ResourceLocation id = effect.getPotion().getRegistryName();
            e.addProperty("id", id == null ? "unknown" : id.toString());
            e.addProperty("level", effect.getAmplifier() + 1);
            e.addProperty("seconds", effect.getDuration() / 20);
            effects.add(e);
        }
        o.add("effects", effects);
        return o;
    }

    private static JsonObject inventory(AgentPlayer agent) {
        JsonObject o = new JsonObject();
        o.addProperty("selected_slot", agent.inventory.currentItem);
        ItemStack held = agent.getHeldItemMainhand();
        o.add("main_hand", held.isEmpty() ? JsonNull.INSTANCE : Describe.item(held));
        ItemStack off = agent.getHeldItemOffhand();
        o.add("off_hand", off.isEmpty() ? JsonNull.INSTANCE : Describe.item(off));
        JsonArray slots = new JsonArray();
        int free = 0;
        for (int i = 0; i < Inventories.MAIN; i++) {
            ItemStack stack = agent.inventory.mainInventory.get(i);
            if (stack.isEmpty()) {
                free++;
                continue;
            }
            JsonObject s = Describe.item(stack);
            s.addProperty("slot", i);
            slots.add(s);
        }
        o.add("slots", slots);
        o.addProperty("free_slots", free);
        JsonObject armor = new JsonObject();
        for (EntityEquipmentSlot slot : new EntityEquipmentSlot[]{EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
                EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET}) {
            ItemStack worn = agent.getItemStackFromSlot(slot);
            if (!worn.isEmpty()) {
                armor.add(slot.getName(), Describe.item(worn));
            }
        }
        o.add("armor", armor);
        ItemStack cursor = agent.inventory.getItemStack();
        if (!cursor.isEmpty()) {
            o.add("cursor", Describe.item(cursor));
        }
        return o;
    }

    private static JsonObject surroundings(AgentPlayer agent) {
        World world = agent.world;
        JsonObject o = new JsonObject();
        BlockPos feet = new BlockPos(agent.posX, agent.posY + 0.1D, agent.posZ);
        BlockPos below = new BlockPos(agent.posX, agent.posY - 0.2D, agent.posZ);
        o.add("standing_on", blockBrief(world, below));
        IBlockState feetState = world.getBlockState(feet);
        if (feetState.getMaterial() != Material.AIR) {
            o.add("at_feet", blockBrief(world, feet));
        }
        BlockPos eyes = new BlockPos(Interaction.eyes(agent));
        o.addProperty("light", world.getLightFromNeighbors(eyes));
        o.addProperty("sky_visible", world.canSeeSky(eyes));
        ResourceLocation biome = world.getBiome(feet).getRegistryName();
        o.addProperty("biome", biome == null ? "unknown" : biome.toString());
        return o;
    }

    private static JsonObject blockBrief(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.addProperty("id", Describe.blockId(state));
        o.addProperty("name", state.getMaterial() == Material.AIR ? "air" : Describe.blockName(state));
        o.add("pos", Describe.posJson(pos));
        return o;
    }

    private static JsonObject world(AgentPlayer agent) {
        World world = agent.world;
        JsonObject o = new JsonObject();
        o.addProperty("dimension", agent.dimension);
        o.addProperty("dimension_name", world.provider.getDimensionType().getName());
        long time = world.getWorldTime();
        long timeOfDay = time % 24000L;
        o.addProperty("time_of_day", timeOfDay);
        o.addProperty("day", time / 24000L + 1);
        o.addProperty("phase", timeOfDay < 12000 ? "day" : timeOfDay < 13800 ? "sunset" : timeOfDay < 22200 ? "night" : "sunrise");
        o.addProperty("weather", world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear");
        return o;
    }

    private static JsonObject view(AgentPlayer agent, boolean panorama) {
        BridgeConfig.Vision cfg = BridgeConfig.vision;
        Vision.View view = Vision.look(agent, panorama);
        Vec3d eye = Interaction.eyes(agent);
        double reach = Interaction.blockReach(agent);
        JsonObject o = new JsonObject();
        o.addProperty("mode", panorama ? "panorama (looked all around)" : "field of view " + cfg.fovHorizontal + " degrees ahead");
        o.addProperty("range", cfg.range);
        o.add("looking_at", lookingAt(agent, eye, reach));

        List<JsonObject> blocks = new ArrayList<>();
        for (Map.Entry<String, Map<BlockPos, Double>> e : view.blocks.entrySet()) {
            IBlockState state = view.states.get(e.getKey());
            List<Map.Entry<BlockPos, Double>> seen = new ArrayList<>(e.getValue().entrySet());
            seen.sort(Map.Entry.comparingByValue());
            BlockPos nearest = seen.get(0).getKey();
            double distance = seen.get(0).getValue();
            JsonObject b = new JsonObject();
            b.addProperty("id", Describe.blockId(state));
            int meta = Describe.blockMeta(state);
            if (meta != 0) {
                b.addProperty("meta", meta);
            }
            b.addProperty("name", Describe.blockName(state));
            b.addProperty("visible_count", seen.size());
            JsonObject n = new JsonObject();
            n.add("pos", Describe.posJson(nearest));
            n.addProperty("distance", Describe.round(distance, 1));
            double dx = nearest.getX() + 0.5D - agent.posX, dz = nearest.getZ() + 0.5D - agent.posZ;
            n.addProperty("direction", Describe.relative(agent.rotationYaw, dx, dz));
            n.addProperty("compass", Describe.compass(dx, dz));
            n.addProperty("reachable", Interaction.distanceToBox(eye, new AxisAlignedBB(nearest)) <= reach);
            b.add("nearest", n);
            JsonArray more = new JsonArray();
            for (int i = 1; i < seen.size() && i < 6; i++) {
                more.add(Describe.posJson(seen.get(i).getKey()));
            }
            if (more.size() > 0) {
                b.add("more", more);
            }
            b.addProperty("_distance", distance);
            blocks.add(b);
        }
        blocks.sort((a, b) -> Double.compare(a.get("_distance").getAsDouble(), b.get("_distance").getAsDouble()));
        JsonArray blockArray = new JsonArray();
        for (int i = 0; i < blocks.size() && i < cfg.maxBlockTypes; i++) {
            JsonObject b = blocks.get(i);
            b.remove("_distance");
            blockArray.add(b);
        }
        o.add("blocks", blockArray);

        JsonArray entities = new JsonArray();
        for (int i = 0; i < view.entities.size() && i < cfg.maxEntities; i++) {
            entities.add(entity(agent, eye, view.entities.get(i)));
        }
        o.add("entities", entities);
        if (view.darkRays > view.rays / 4) {
            o.addProperty("too_dark", "much of the view is pitch black; place torches to see");
        }
        return o;
    }

    private static JsonObject lookingAt(AgentPlayer agent, Vec3d eye, double reach) {
        Vec3d end = eye.add(Vision.direction(agent.rotationYaw, agent.rotationPitch).scale(BridgeConfig.vision.range));
        RayTraceResult hit = agent.world.rayTraceBlocks(eye, end, false, false, false);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        JsonObject o = new JsonObject();
        IBlockState state = agent.world.getBlockState(pos);
        o.addProperty("id", Describe.blockId(state));
        o.addProperty("name", Describe.blockName(state));
        o.add("pos", Describe.posJson(pos));
        o.addProperty("face", hit.sideHit.getName());
        double distance = eye.distanceTo(hit.hitVec);
        o.addProperty("distance", Describe.round(distance, 1));
        o.addProperty("reachable", distance <= reach);
        return o;
    }

    private static JsonObject entity(AgentPlayer agent, Vec3d eye, Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("entity_id", e.getEntityId());
        o.addProperty("type", Describe.entityType(e));
        o.addProperty("name", e.getName());
        JsonArray pos = new JsonArray();
        pos.add(Describe.round(e.posX, 1));
        pos.add(Describe.round(e.posY, 1));
        pos.add(Describe.round(e.posZ, 1));
        o.add("pos", pos);
        double distance = Interaction.distanceToBox(eye, e.getEntityBoundingBox());
        o.addProperty("distance", Describe.round(distance, 1));
        double dx = e.posX - agent.posX, dz = e.posZ - agent.posZ;
        o.addProperty("direction", Describe.relative(agent.rotationYaw, dx, dz));
        o.addProperty("compass", Describe.compass(dx, dz));
        o.addProperty("reachable", distance <= Interaction.entityReach(agent));
        if (e instanceof IMob) {
            o.addProperty("hostile", true);
        }
        if (e instanceof EntityPlayer) {
            o.addProperty("player", true);
            if (e instanceof AgentPlayer) {
                o.addProperty("agent", true);
            }
        }
        if (e instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) e;
            o.addProperty("health", Describe.round(living.getHealth(), 1));
            ItemStack held = living.getHeldItemMainhand();
            if (!held.isEmpty()) {
                o.addProperty("holding", held.getDisplayName());
            }
        }
        if (e instanceof EntityItem) {
            o.add("item", Describe.item(((EntityItem) e).getItem()));
        }
        return o;
    }

    /** The open screen: every slot of the container itself, plus the agent's own non-empty slots. */
    public static JsonObject container(AgentPlayer agent) {
        Container container = agent.openContainer;
        JsonObject o = new JsonObject();
        String title = null;
        JsonArray slots = new JsonArray();
        int firstPlayer = -1, lastPlayer = -1;
        for (Slot slot : container.inventorySlots) {
            boolean own = slot.inventory == agent.inventory;
            if (own) {
                if (firstPlayer < 0) {
                    firstPlayer = slot.slotNumber;
                }
                lastPlayer = slot.slotNumber;
            } else if (title == null) {
                try {
                    title = slot.inventory.getDisplayName().getUnformattedText();
                } catch (RuntimeException ignored) {
                    // some modded inventories have no name
                }
            }
            ItemStack stack = slot.getStack();
            if (own && stack.isEmpty()) {
                continue;
            }
            JsonObject s = stack.isEmpty() ? new JsonObject() : Describe.item(stack);
            s.addProperty("slot", slot.slotNumber);
            s.addProperty("owner", own ? "agent" : "container");
            if (stack.isEmpty()) {
                s.addProperty("empty", true);
            }
            String kind = slot.getClass().getSimpleName();
            if (!kind.equals("Slot") && !own) {
                s.addProperty("kind", kind);
            }
            slots.add(s);
        }
        o.addProperty("title", title != null ? title : container.getClass().getSimpleName());
        o.addProperty("type", container.getClass().getSimpleName());
        if (firstPlayer >= 0) {
            o.addProperty("agent_inventory_slots", firstPlayer + "-" + lastPlayer);
        }
        o.add("slots", slots);
        ItemStack cursor = agent.inventory.getItemStack();
        o.add("cursor", cursor.isEmpty() ? JsonNull.INSTANCE : Describe.item(cursor));
        return o;
    }
}
