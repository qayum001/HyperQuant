package com.hyperquant.agentbridge.agent;

import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;

/**
 * Server-side play connection of an agent. Outgoing packets would normally go to a game client; here the only
 * ones worth keeping are chat messages, which become what the agent "hears".
 */
public class AgentNetHandler extends NetHandlerPlayServer {
    private final AgentPlayer agent;

    public AgentNetHandler(MinecraftServer server, NetworkManager networkManager, AgentPlayer agent) {
        super(server, networkManager, agent);
        this.agent = agent;
    }

    @Override
    public void sendPacket(Packet<?> packet) {
        if (packet instanceof SPacketChat) {
            agent.ears.hear((SPacketChat) packet);
        }
    }

    /** Called when the agent is kicked (by an admin, an anti-cheat mod, ...). */
    @Override
    public void disconnect(ITextComponent reason) {
        AgentManager.get().onKicked(agent, reason);
    }
}
