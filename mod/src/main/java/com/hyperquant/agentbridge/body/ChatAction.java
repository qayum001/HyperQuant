package com.hyperquant.agentbridge.body;

import com.hyperquant.agentbridge.BridgeConfig;
import com.hyperquant.agentbridge.agent.AgentManager;
import com.hyperquant.agentbridge.agent.AgentPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.ForgeHooks;

import javax.annotation.Nullable;

/** chat {message, to?}: say something in public chat, or whisper to one player. */
final class ChatAction extends Action {
    private static final int MAX_LENGTH = 256;

    private final String message;
    @Nullable
    private final String to;

    ChatAction(AgentPlayer agent, Args args) {
        super("chat", agent, 5);
        String raw = args.string("message");
        // No formatting codes or control characters, same as the vanilla chat packet handler.
        StringBuilder clean = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c != '§' && c >= ' ' && c != 127) {
                clean.append(c);
            }
        }
        String text = clean.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("message is empty");
        }
        this.message = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        this.to = args.string("to", null);
    }

    @Override
    protected void update() {
        MinecraftServer server = agent.getServer();
        if (server == null) {
            fail("no server");
            return;
        }
        if (!agent.tryUseChat(AgentManager.get().currentTick(), BridgeConfig.agents.chatCooldownTicks)) {
            fail("speaking too fast; wait a moment");
            return;
        }
        if (message.startsWith("/")) {
            if (!BridgeConfig.agents.allowCommands) {
                fail("agents are not allowed to run commands on this server");
                return;
            }
            // Runs with the agent's own permissions, like a player typing it.
            int result = server.getCommandManager().executeCommand(agent, message.substring(1));
            succeed(result > 0 ? "ran " + message : "the command did not run (no permission or bad syntax)");
            return;
        }
        if (to != null) {
            EntityPlayerMP target = server.getPlayerList().getPlayerByUsername(to);
            if (target == null) {
                fail(to + " is not online");
                return;
            }
            TextComponentTranslation whisper = new TextComponentTranslation("commands.message.display.incoming",
                    agent.getDisplayName(), new TextComponentString(message));
            whisper.getStyle().setColor(TextFormatting.GRAY).setItalic(true);
            target.sendMessage(whisper);
            succeed("whispered to " + target.getName());
            return;
        }
        ITextComponent line = new TextComponentTranslation("chat.type.text", agent.getDisplayName(),
                ForgeHooks.newChatWithLinks(message));
        line = ForgeHooks.onServerChatEvent(agent.connection, message, line);
        if (line == null) {
            fail("the server blocked the message");
            return;
        }
        server.getPlayerList().sendMessage(line, false);
        succeed("said: " + message);
    }
}
