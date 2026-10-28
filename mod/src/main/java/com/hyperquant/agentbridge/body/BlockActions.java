package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.eyes.Describe;
import com.hyperquant.agentbridge.eyes.Observer;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/** Left- and right-clicking blocks. Every click goes through the same server code a real client's click does. */
final class BlockActions {
    private BlockActions() {}

    /** mine {x,y,z, auto_tool?}: hold left click on a block until it breaks. */
    static final class Mine extends Action {
        private final BlockPos pos;
        private final boolean autoTool;
        @Nullable
        private IBlockState original;
        private boolean started;
        private float progress;
        private int waitingForServer = -1;

        Mine(AgentPlayer agent, Args args) {
            super("mine", agent, 20 * 60);
            this.pos = args.blockPos();
            this.autoTool = args.bool("auto_tool", true);
        }

        @Override
        protected void update() {
            World world = agent.world;
            IBlockState now = world.getBlockState(pos);
            if (original == null) {
                if (now.getMaterial() == Material.AIR) {
                    fail("there is no block at " + Describe.pos(pos));
                    return;
                }
                if (now.getMaterial().isLiquid()) {
                    fail("liquids cannot be mined; use a bucket with use_block");
                    return;
                }
                if (now.getBlockHardness(world, pos) < 0F) {
                    fail(Describe.blockName(now) + " is unbreakable");
                    return;
                }
                String problem = Interaction.editProblem(agent, pos);
                if (problem != null) {
                    fail("cannot break blocks here: " + problem);
                    return;
                }
                original = now;
                if (autoTool) {
                    Inventories.selectBestTool(agent, now);
                }
                ItemStack tool = agent.getHeldItemMainhand();
                data.addProperty("block", Describe.blockName(now));
                data.addProperty("tool", tool.isEmpty() ? "hand" : tool.getDisplayName());
                data.addProperty("drops_expected", agent.canHarvestBlock(now));
            }
            if (now != original && now.getBlock() == original.getBlock()) {
                original = now; // same block, different state (a crop grew, a furnace lit up...)
            }
            if (now != original) {
                if (started) {
                    succeed("broke " + Describe.blockName(original) + " at " + Describe.pos(pos)
                            + (agent.canHarvestBlock(original) ? "; pick up the drops with collect_items"
                            : "; nothing dropped because it needs a better tool"));
                } else {
                    fail("the block changed before it could be mined");
                }
                return;
            }
            if (waitingForServer >= 0) {
                if (++waitingForServer > 10) {
                    fail("the server did not let " + agent.getName() + " break " + Describe.blockName(original)
                            + " (protected area or another mod cancelled it)");
                }
                return;
            }
            RayTraceResult hit = Interaction.aimAtBlock(agent, pos, null);
            if (hit == null) {
                fail(Interaction.whyUnreachable(agent, pos));
                return;
            }
            agent.lookAt(hit.hitVec.x, hit.hitVec.y, hit.hitVec.z);
            if (!started) {
                started = true;
                agent.interactionManager.onBlockClicked(pos, hit.sideHit); // breaks instantly if it can
                agent.swingArm(EnumHand.MAIN_HAND);
                return;
            }
            progress += original.getPlayerRelativeBlockHardness(agent, world, pos);
            if (ticks() % 4 == 0) {
                agent.swingArm(EnumHand.MAIN_HAND);
            }
            if (progress >= 1.0F) {
                agent.interactionManager.blockRemoving(pos);
                waitingForServer = 0;
            }
        }

        @Override
        protected void cleanup() {
            if (started && original != null && agent.world.getBlockState(pos) == original) {
                agent.interactionManager.cancelDestroyingBlock();
            }
        }
    }

    /** place {x,y,z, item?}: right-click the side of a neighbouring block with a block in hand. */
    static final class Place extends Action {
        private final BlockPos pos;
        @Nullable
        private final ItemQuery item;

        Place(AgentPlayer agent, Args args) {
            super("place", agent, 5);
            this.pos = args.blockPos();
            this.item = args.has("item") ? ItemQuery.from(args, "item") : null;
        }

        @Override
        protected void update() {
            World world = agent.world;
            IBlockState before = world.getBlockState(pos);
            if (!before.getBlock().isReplaceable(world, pos)) {
                fail("there is already " + Describe.blockName(before) + " at " + Describe.pos(pos));
                return;
            }
            String problem = Interaction.editProblem(agent, pos);
            if (problem != null) {
                fail("cannot place blocks here: " + problem);
                return;
            }
            if (item != null) {
                problem = Inventories.holdInMainHand(agent, item);
                if (problem != null) {
                    fail(problem);
                    return;
                }
            }
            ItemStack held = agent.getHeldItemMainhand();
            if (held.isEmpty()) {
                fail("nothing in the main hand to place");
                return;
            }
            String heldName = held.getDisplayName();
            EnumFacing[] order = {EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST, EnumFacing.UP};
            for (EnumFacing direction : order) {
                BlockPos support = pos.offset(direction);
                IBlockState supportState = world.getBlockState(support);
                if (supportState.getBlock().isReplaceable(world, support) || supportState.getMaterial().isLiquid()) {
                    continue;
                }
                EnumFacing face = direction.getOpposite(); // the support's face that touches the target
                RayTraceResult hit = Interaction.aimAtBlock(agent, support, face);
                if (hit == null) {
                    continue;
                }
                agent.lookAt(hit.hitVec.x, hit.hitVec.y, hit.hitVec.z);
                boolean wasSneaking = agent.isSneaking();
                agent.setSneaking(true); // so clicking a chest or crafting table places instead of opening it
                EnumActionResult result = agent.interactionManager.processRightClickBlock(agent, world, held,
                        EnumHand.MAIN_HAND, support, face,
                        (float) (hit.hitVec.x - support.getX()), (float) (hit.hitVec.y - support.getY()),
                        (float) (hit.hitVec.z - support.getZ()));
                agent.setSneaking(wasSneaking);
                agent.swingArm(EnumHand.MAIN_HAND);
                IBlockState after = world.getBlockState(pos);
                if (after != before) {
                    data.addProperty("placed", Describe.blockName(after));
                    succeed("placed " + Describe.blockName(after) + " at " + Describe.pos(pos));
                } else {
                    fail("could not place " + heldName + " at " + Describe.pos(pos) + " (result " + result
                            + "; is something standing there, or is it not a placeable block?)");
                }
                return;
            }
            fail("no visible block face within reach to place against at " + Describe.pos(pos)
                    + "; the target must touch a solid block you can see from where you stand");
        }
    }

    /** use_block {x,y,z, face?}: right-click a block (open chests/tables/furnaces, doors, levers, buckets...). */
    static final class UseBlock extends Action {
        private final BlockPos pos;
        @Nullable
        private final EnumFacing face;

        UseBlock(AgentPlayer agent, Args args) {
            super("use_block", agent, 5);
            this.pos = args.blockPos();
            String f = args.string("face", null);
            this.face = f == null ? null : parseFace(f);
        }

        private static EnumFacing parseFace(String name) {
            for (EnumFacing facing : EnumFacing.values()) {
                if (facing.getName().equalsIgnoreCase(name)) {
                    return facing; // EnumFacing.byName is client-only
                }
            }
            throw new IllegalArgumentException("face must be one of up, down, north, south, east, west");
        }

        @Override
        protected void update() {
            World world = agent.world;
            String problem = Interaction.editProblem(agent, pos);
            if (problem != null) {
                fail("cannot use blocks here: " + problem);
                return;
            }
            RayTraceResult hit = Interaction.aimAtBlock(agent, pos, face);
            if (hit == null) {
                fail(Interaction.whyUnreachable(agent, pos));
                return;
            }
            agent.lookAt(hit.hitVec.x, hit.hitVec.y, hit.hitVec.z);
            IBlockState before = world.getBlockState(pos);
            float hx = (float) (hit.hitVec.x - pos.getX());
            float hy = (float) (hit.hitVec.y - pos.getY());
            float hz = (float) (hit.hitVec.z - pos.getZ());
            // Same order as the vanilla client: for each hand try the block, then the item in that hand.
            String outcome = null;
            for (EnumHand hand : EnumHand.values()) {
                ItemStack stack = agent.getHeldItem(hand);
                if (agent.interactionManager.processRightClickBlock(agent, world, stack, hand, pos, hit.sideHit, hx, hy, hz)
                        == EnumActionResult.SUCCESS) {
                    outcome = "used " + Describe.blockName(before) + (stack.isEmpty() ? "" : " with " + stack.getDisplayName());
                    agent.swingArm(hand);
                    break;
                }
                if (!stack.isEmpty() && agent.interactionManager.processRightClick(agent, world, stack, hand) == EnumActionResult.SUCCESS) {
                    outcome = "used " + stack.getDisplayName() + " on " + Describe.blockName(before);
                    break;
                }
            }
            if (agent.openContainer != agent.inventoryContainer) {
                data.add("container", Observer.container(agent));
                succeed("opened " + Describe.blockName(before) + "; its slots are listed in data.container");
            } else if (outcome != null) {
                succeed(outcome);
            } else {
                fail("right-clicking " + Describe.blockName(before) + " at " + Describe.pos(pos) + " did nothing");
            }
        }
    }
}
