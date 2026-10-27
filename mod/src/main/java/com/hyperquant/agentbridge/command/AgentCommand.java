package com.hyperquant.agentbridge.command;

import com.hyperquant.agentbridge.BridgeConfig;
import com.hyperquant.agentbridge.agent.AgentException;
import com.hyperquant.agentbridge.agent.AgentManager;
import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.eyes.Describe;
import com.google.gson.JsonObject;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * /agent spawn|despawn|task|stop|list. Giving tasks is also possible in chat: "@Bob bring me 16 oak logs".
 */
public class AgentCommand extends CommandBase {
    private static final String USAGE = "/agent spawn <name> | despawn <name> | task <name> <what to do> | stop <name> | list";

    @Override
    public String getName() {
        return "agent";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("agents");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // checked per sub-command below
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(USAGE);
        }
        AgentManager manager = AgentManager.get();
        try {
            switch (args[0].toLowerCase()) {
                case "list":
                    list(manager, sender);
                    break;
                case "spawn": {
                    requireLevel(sender, BridgeConfig.agents.spawnPermissionLevel);
                    requireArgs(args, 2);
                    Entity entity = sender.getCommandSenderEntity();
                    AgentPlayer agent = entity != null
                            ? manager.spawn(args[1], entity.getPositionVector(), entity.rotationYaw, entity.dimension)
                            : manager.spawn(args[1], null, 0F, 0);
                    reply(sender, "Agent " + agent.getName() + " joined at " + Describe.pos(agent.getPosition()), TextFormatting.GREEN);
                    break;
                }
                case "despawn":
                case "remove":
                    requireLevel(sender, BridgeConfig.agents.spawnPermissionLevel);
                    requireArgs(args, 2);
                    manager.despawn(args[1], "Despawned by " + sender.getName());
                    reply(sender, "Agent " + args[1] + " left", TextFormatting.GREEN);
                    break;
                case "task":
                case "ask": {
                    requireLevel(sender, BridgeConfig.agents.taskPermissionLevel);
                    requireArgs(args, 3);
                    AgentPlayer agent = manager.require(args[1]);
                    String text = buildString(args, 2);
                    boolean heard = manager.requestTask(agent, sender.getName(), text, "command");
                    reply(sender, heard ? "Sent to " + agent.getName() + ": " + text
                            : agent.getName() + "'s brain is offline (no harness connected); the request was queued", TextFormatting.GRAY);
                    break;
                }
                case "stop": {
                    requireLevel(sender, BridgeConfig.agents.taskPermissionLevel);
                    requireArgs(args, 2);
                    AgentPlayer agent = manager.require(args[1]);
                    manager.requestStop(agent, sender.getName());
                    reply(sender, "Told " + agent.getName() + " to stop", TextFormatting.GRAY);
                    break;
                }
                default:
                    throw new WrongUsageException(USAGE);
            }
        } catch (AgentException e) {
            throw new CommandException(e.getMessage());
        }
    }

    private static void list(AgentManager manager, ICommandSender sender) {
        if (manager.all().isEmpty()) {
            reply(sender, "No agents are spawned", TextFormatting.GRAY);
            return;
        }
        for (AgentPlayer agent : manager.all()) {
            JsonObject action = agent.actionStatus();
            String doing = agent.isBusy() && action != null ? action.get("action").getAsString() : "idle";
            reply(sender, agent.getName() + " at " + Describe.pos(agent.getPosition()) + " (dim " + agent.dimension + "), "
                    + Math.round(agent.getHealth()) + " hp, " + doing, TextFormatting.GRAY);
        }
    }

    private static void requireLevel(ICommandSender sender, int level) throws CommandException {
        if (!sender.canUseCommand(level, "agent")) {
            throw new CommandException("commands.generic.permission");
        }
    }

    private static void requireArgs(String[] args, int n) throws WrongUsageException {
        if (args.length < n) {
            throw new WrongUsageException(USAGE);
        }
    }

    private static void reply(ICommandSender sender, String message, TextFormatting color) {
        TextComponentString text = new TextComponentString(message);
        text.getStyle().setColor(color);
        sender.sendMessage(text);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "spawn", "despawn", "task", "stop", "list");
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("spawn")) {
            AgentManager manager = AgentManager.getIfRunning();
            List<String> names = new ArrayList<>();
            if (manager != null) {
                manager.all().forEach(a -> names.add(a.getName()));
            }
            return getListOfStringsMatchingLastWord(args, names);
        }
        return Collections.emptyList();
    }
}
