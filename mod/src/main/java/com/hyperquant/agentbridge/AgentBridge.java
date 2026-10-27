package com.hyperquant.agentbridge;

import com.hyperquant.agentbridge.agent.AgentManager;
import com.hyperquant.agentbridge.agent.ForgeEventHandler;
import com.hyperquant.agentbridge.api.BridgeHttpServer;
import com.hyperquant.agentbridge.command.AgentCommand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Server-side mod that lets external AI processes ("harnesses") control player avatars.
 *
 * <p>Each agent is a real {@code EntityPlayerMP} that is logged in without a game client. The harness talks to
 * it over a local HTTP API: it asks the agent's eyes what they see and asks its body to perform player-sized
 * actions. Clients do not need this mod installed.
 */
@Mod(modid = AgentBridge.MODID, name = AgentBridge.NAME, version = AgentBridge.VERSION,
        acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]")
public class AgentBridge {
    public static final String MODID = "agentbridge";
    public static final String NAME = "HyperQuant AgentBridge";
    public static final String VERSION = "0.1.0";

    public static Logger LOG = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOG = event.getModLog();
        MinecraftForge.EVENT_BUS.register(new ForgeEventHandler());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        AgentManager.init(event.getServer());
        event.registerServerCommand(new AgentCommand());
        try {
            BridgeHttpServer.start(AgentManager.get());
        } catch (IOException e) {
            LOG.error("Could not start the agent bridge API on {}:{}", BridgeConfig.api.bindAddress, BridgeConfig.api.port, e);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        BridgeHttpServer.stop();
        AgentManager.shutdown();
    }
}
