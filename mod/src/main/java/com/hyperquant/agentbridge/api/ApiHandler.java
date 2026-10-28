package com.hyperquant.agentbridge.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyperquant.agentbridge.AgentBridge;
import com.hyperquant.agentbridge.agent.AgentException;
import com.hyperquant.agentbridge.agent.AgentManager;
import com.hyperquant.agentbridge.agent.AgentPlayer;
import com.hyperquant.agentbridge.body.Action;
import com.hyperquant.agentbridge.body.Actions;
import com.hyperquant.agentbridge.body.Args;
import com.hyperquant.agentbridge.body.Crafting;
import com.hyperquant.agentbridge.body.ItemQuery;
import com.hyperquant.agentbridge.eyes.Describe;
import com.hyperquant.agentbridge.eyes.Observer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.math.Vec3d;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * JSON over HTTP for the agent harness. See docs/PROTOCOL.md for the full description.
 *
 * <pre>
 * GET    /v1/health
 * GET    /v1/agents                      POST /v1/agents {name, near_player? | x,y,z, dimension?}
 * GET    /v1/agents/{name}               DELETE /v1/agents/{name}
 * GET    /v1/agents/{name}/observe?panorama=true
 * POST   /v1/agents/{name}/act {action, args, wait?, timeout_ms?}
 * GET    /v1/agents/{name}/action        POST /v1/agents/{name}/stop
 * GET    /v1/events?after=ID&amp;wait_ms=25000
 * GET    /v1/recipes?item=minecraft:torch
 * </pre>
 */
final class ApiHandler implements HttpHandler {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long SERVER_TIMEOUT_MS = 30_000;
    private static final int MAX_BODY = 1 << 20;

    private final AgentManager manager;
    private final byte[] expectedAuth;

    ApiHandler(AgentManager manager, String token) {
        this.manager = manager;
        this.expectedAuth = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), expectedAuth)) {
                send(exchange, 401, error("missing or wrong bearer token"));
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath().replaceAll("^/+|/+$", "");
            String[] parts = path.split("/");
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            JsonObject body = method.equals("POST") ? readBody(exchange) : new JsonObject();
            send(exchange, 200, route(method, parts, query, body));
        } catch (AgentException e) {
            send(exchange, e.httpStatus, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            AgentBridge.LOG.warn("Agent API request failed", e);
            send(exchange, 500, error(e.toString()));
        } finally {
            exchange.close();
        }
    }

    private JsonElement route(String method, String[] p, Map<String, String> query, JsonObject body) throws Exception {
        if (p.length < 2 || !p[0].equals("v1")) {
            throw new AgentException(404, "unknown endpoint; everything lives under /v1/");
        }
        String resource = p[1];
        if (resource.equals("health") && method.equals("GET")) {
            return health();
        }
        if (resource.equals("events") && method.equals("GET")) {
            long after = Long.parseLong(query.getOrDefault("after", "0"));
            long waitMs = Math.min(60_000L, Long.parseLong(query.getOrDefault("wait_ms", "25000")));
            return manager.events.await(after, waitMs);
        }
        if (resource.equals("recipes") && method.equals("GET")) {
            String item = query.get("item");
            if (item == null || item.isEmpty()) {
                throw new IllegalArgumentException("give ?item=<id or name>");
            }
            int limit = Integer.parseInt(query.getOrDefault("limit", "5"));
            return onServer(() -> recipes(ItemQuery.parse(item, -1), limit));
        }
        if (resource.equals("agents")) {
            if (p.length == 2) {
                if (method.equals("GET")) {
                    return onServer(this::listAgents);
                }
                if (method.equals("POST")) {
                    return onServer(() -> spawn(body));
                }
            } else {
                String name = p[2];
                String sub = p.length > 3 ? p[3] : "";
                switch (method + " " + sub) {
                    case "GET ":
                        return onServer(() -> summary(manager.require(name)));
                    case "DELETE ":
                        return onServer(() -> {
                            manager.despawn(name, "Despawned by the harness");
                            return ok();
                        });
                    case "GET observe":
                        boolean panorama = Boolean.parseBoolean(query.getOrDefault("panorama", "false"));
                        return onServer(() -> Observer.observe(manager.require(name), panorama));
                    case "POST act":
                        return act(name, body);
                    case "GET action":
                        return onServer(() -> {
                            JsonObject status = manager.require(name).actionStatus();
                            return status != null ? status : new JsonObject();
                        });
                    case "POST stop":
                        return onServer(() -> {
                            manager.requestStop(manager.require(name), "the harness");
                            return ok();
                        });
                    default:
                        break;
                }
            }
        }
        throw new AgentException(404, "unknown endpoint " + method + " /" + String.join("/", p));
    }

    // ---- endpoints ----

    private JsonObject health() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("mod_version", AgentBridge.VERSION);
        o.addProperty("minecraft", "1.12.2");
        JsonArray actions = new JsonArray();
        Actions.names().forEach(actions::add);
        o.add("actions", actions);
        return o;
    }

    private JsonObject listAgents() {
        JsonArray agents = new JsonArray();
        for (AgentPlayer agent : manager.all()) {
            agents.add(summary(agent));
        }
        JsonObject o = new JsonObject();
        o.add("agents", agents);
        return o;
    }

    private JsonObject summary(AgentPlayer agent) {
        JsonObject o = new JsonObject();
        o.addProperty("name", agent.getName());
        o.add("position", Describe.posJson(agent.getPosition()));
        o.addProperty("dimension", agent.dimension);
        o.addProperty("health", Describe.round(agent.getHealth(), 1));
        o.addProperty("food", agent.getFoodStats().getFoodLevel());
        o.addProperty("dead", agent.getHealth() <= 0F);
        o.addProperty("busy", agent.isBusy());
        JsonObject action = agent.actionStatus();
        if (action != null) {
            o.add("action", action);
        }
        return o;
    }

    private JsonObject spawn(JsonObject body) {
        Args args = new Args(body);
        String name = args.string("name");
        Vec3d at = null;
        int dimension = args.integer("dimension", 0);
        float yaw = 0F;
        String near = args.string("near_player", null);
        if (near != null) {
            EntityPlayerMP player = manager.server().getPlayerList().getPlayerByUsername(near);
            if (player == null) {
                throw new AgentException(404, near + " is not online");
            }
            at = new Vec3d(player.posX, player.posY, player.posZ);
            dimension = player.dimension;
            yaw = player.rotationYaw;
        } else if (args.hasPos()) {
            at = new Vec3d(args.integer("x") + 0.5D, args.integer("y"), args.integer("z") + 0.5D);
        }
        return summary(manager.spawn(name, at, yaw, dimension));
    }

    private JsonObject act(String name, JsonObject body) throws Exception {
        Args request = new Args(body);
        String actionName = request.string("action");
        JsonObject actionArgs = body.has("args") && body.get("args").isJsonObject() ? body.getAsJsonObject("args") : new JsonObject();
        boolean wait = request.bool("wait", true);
        long timeoutMs = Math.max(0, Math.min(600_000, request.integer("timeout_ms", 120_000)));

        Action action = onServer(() -> {
            AgentPlayer agent = manager.require(name);
            Action created = Actions.create(agent, actionName, new Args(actionArgs));
            manager.startAction(agent, created);
            return created;
        });
        if (wait) {
            try {
                return action.future().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Still running: report progress; the harness can poll /action or call /stop.
            }
        }
        return onServer(action::result);
    }

    private JsonObject recipes(ItemQuery item, int limit) {
        JsonObject o = new JsonObject();
        o.addProperty("item", item.toString());
        JsonArray crafting = new JsonArray();
        List<IRecipe> found = Crafting.recipesFor(item);
        for (int i = 0; i < found.size() && i < limit; i++) {
            crafting.add(Crafting.recipeJson(found.get(i)));
        }
        o.add("crafting", crafting);
        o.add("smelting", Crafting.smeltingFor(item));
        return o;
    }

    // ---- plumbing ----

    /** Runs on the server thread and waits for the result. */
    private <T> T onServer(Supplier<T> task) throws Exception {
        try {
            return manager.submit(task).get(SERVER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } catch (TimeoutException e) {
            throw new AgentException(503, "the server did not respond in time (is it paused or lagging?)");
        }
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) {
                    throw new IllegalArgumentException("request body too large");
                }
            }
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return new JsonObject();
        }
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("request body is not valid JSON");
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static Map<String, String> parseQuery(String raw) throws UnsupportedEncodingException {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "true" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
        }
        return out;
    }

    private static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
