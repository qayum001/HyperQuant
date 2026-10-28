package com.hyperquant.agentbridge.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.TextFormatting;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/** Chat and status messages that reached the agent, i.e. what a player would read in their chat window. */
public final class Ears {
    private static final int CAPACITY = 40;

    private static final class Heard {
        final long timeMs;
        final String text;
        final boolean actionBar;

        Heard(long timeMs, String text, boolean actionBar) {
            this.timeMs = timeMs;
            this.text = text;
            this.actionBar = actionBar;
        }
    }

    private final Deque<Heard> messages = new ArrayDeque<>();

    void hear(SPacketChat packet) {
        String text;
        try {
            // The getter for the message is client-only, so read it back from the packet's wire format.
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            try {
                packet.writePacketData(buf);
                text = buf.readTextComponent().getUnformattedText();
            } finally {
                buf.release();
            }
        } catch (IOException | RuntimeException e) {
            return;
        }
        text = TextFormatting.getTextWithoutFormattingCodes(text);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        boolean actionBar = packet.getType() == ChatType.GAME_INFO;
        synchronized (messages) {
            Heard last = messages.peekLast();
            if (last != null && last.text.equals(text) && last.actionBar == actionBar) {
                messages.pollLast();
            }
            messages.addLast(new Heard(System.currentTimeMillis(), text, actionBar));
            while (messages.size() > CAPACITY) {
                messages.pollFirst();
            }
        }
    }

    /** The most recent chat lines (newest last) plus the latest action-bar message, if any. */
    public JsonArray recent(int limit, long maxAgeMs) {
        long now = System.currentTimeMillis();
        JsonArray out = new JsonArray();
        synchronized (messages) {
            Heard latestActionBar = null;
            Deque<Heard> chat = new ArrayDeque<>();
            for (Heard h : messages) {
                if (now - h.timeMs > maxAgeMs) {
                    continue;
                }
                if (h.actionBar) {
                    latestActionBar = h;
                } else {
                    chat.addLast(h);
                }
            }
            while (chat.size() > limit) {
                chat.pollFirst();
            }
            for (Heard h : chat) {
                out.add(toJson(h, now));
            }
            if (latestActionBar != null) {
                JsonObject o = toJson(latestActionBar, now);
                o.addProperty("action_bar", true);
                out.add(o);
            }
        }
        return out;
    }

    private static JsonObject toJson(Heard h, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("seconds_ago", (now - h.timeMs) / 1000);
        o.addProperty("text", h.text);
        return o;
    }
}
