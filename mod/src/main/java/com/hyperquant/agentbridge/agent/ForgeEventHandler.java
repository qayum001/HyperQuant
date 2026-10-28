package com.hyperquant.agentbridge.agent;

import com.hyperquant.agentbridge.BridgeConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ForgeEventHandler {
    /** "@Bob bring me 10 oak logs" or "@Bob, stop". */
    private static final Pattern MENTION = Pattern.compile("^@([A-Za-z0-9_]{3,16})[,:]?\\s+(.+)$");

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        AgentManager manager = AgentManager.getIfRunning();
        if (event.phase == TickEvent.Phase.END && manager != null) {
            manager.tick();
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        AgentManager manager = AgentManager.getIfRunning();
        EntityPlayerMP sender = event.getPlayer();
        if (manager == null || sender instanceof AgentPlayer) {
            return; // agents talking to agents would loop
        }
        Matcher m = MENTION.matcher(event.getMessage().trim());
        if (!m.matches()) {
            return;
        }
        AgentPlayer agent = manager.get(m.group(1));
        if (agent == null) {
            return;
        }
        if (!sender.canUseCommand(BridgeConfig.agents.taskPermissionLevel, "agent")) {
            tell(sender, "You are not allowed to give tasks to agents.");
            return;
        }
        String text = m.group(2).trim();
        if (text.equalsIgnoreCase("stop") || text.equalsIgnoreCase("cancel")) {
            manager.requestStop(agent, sender.getName());
            return;
        }
        if (!manager.requestTask(agent, sender.getName(), text, "chat")) {
            tell(sender, agent.getName() + "'s brain is offline (no harness connected), the request was queued.");
        }
    }

    private static void tell(EntityPlayerMP player, String message) {
        TextComponentString text = new TextComponentString(message);
        text.getStyle().setColor(TextFormatting.GRAY);
        player.sendMessage(text);
    }
}
