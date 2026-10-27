package com.hyperquant.agentbridge.api;

import com.hyperquant.agentbridge.AgentBridge;
import com.hyperquant.agentbridge.BridgeConfig;
import com.hyperquant.agentbridge.agent.AgentManager;
import com.sun.net.httpserver.HttpServer;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Starts and stops the local HTTP API (JDK built-in server, no extra dependencies). */
public final class BridgeHttpServer {
    private static HttpServer http;
    private static ExecutorService pool;

    private BridgeHttpServer() {}

    public static synchronized void start(AgentManager manager) throws IOException {
        stop();
        ensureToken();
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(BridgeConfig.api.bindAddress), BridgeConfig.api.port);
        http = HttpServer.create(address, 0);
        // Long-polling requests park a thread each, so use an unbounded pool of daemon threads.
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agentbridge-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/", new ApiHandler(manager, BridgeConfig.api.token));
        http.start();
        AgentBridge.LOG.info("Agent bridge API listening on http://{}:{}/v1/", BridgeConfig.api.bindAddress, BridgeConfig.api.port);
    }

    public static synchronized void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private static void ensureToken() {
        if (BridgeConfig.api.token == null || BridgeConfig.api.token.trim().isEmpty()) {
            BridgeConfig.api.token = new BigInteger(130, new SecureRandom()).toString(32);
            ConfigManager.sync(AgentBridge.MODID, Config.Type.INSTANCE);
            AgentBridge.LOG.info("Generated an API token for the agent harness; find it as 'token' in config/agentbridge.cfg");
        }
    }
}
