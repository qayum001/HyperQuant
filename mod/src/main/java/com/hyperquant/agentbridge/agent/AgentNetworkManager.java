package com.hyperquant.agentbridge.agent;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

/**
 * Connection of an agent avatar. There is no game client on the other end, so packets for it are dropped.
 *
 * <p>The manager still sits in an {@link EmbeddedChannel}: FML reads attributes from every player's channel
 * when mods send network messages, and an embedded channel satisfies that without opening a socket.
 */
public class AgentNetworkManager extends NetworkManager {
    public AgentNetworkManager() {
        super(EnumPacketDirection.SERVERBOUND);
        new EmbeddedChannel(this);
    }

    @Override
    public void sendPacket(Packet<?> packetIn) {
    }

    @Override
    public void sendPacket(Packet<?> packetIn, GenericFutureListener<? extends Future<? super Void>> listener,
                           GenericFutureListener<? extends Future<? super Void>>... listeners) {
    }

    @Override
    public void processReceivedPackets() {
    }
}
