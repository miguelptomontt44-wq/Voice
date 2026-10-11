package dev.proxvoice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.proxvoice.ProxVoicePlugin.Link;
import dev.proxvoice.ProxVoicePlugin.Snap;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servidor WebSocket. Recibe audio PCM16 mono 16 kHz (frames de 20 ms) de cada
 * navegador y lo reenvia a los jugadores cercanos con volumen y panoramica.
 *
 * Paquete servidor -> cliente (little endian):
 *   int32  id del hablante | float32 volumen (0..1) | float32 pan (-1..1) | PCM16...
 */
final class VoiceServer extends WebSocketServer {

    private final ProxVoicePlugin plugin;
    private final Map<WebSocket, UUID> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, WebSocket> byPlayer = new ConcurrentHashMap<>();

    VoiceServer(InetSocketAddress addr, ProxVoicePlugin plugin) {
        super(addr);
        this.plugin = plugin;
        setReuseAddr(true);
    }

    void kick(UUID id) {
        WebSocket c = byPlayer.remove(id);
        if (c != null) {
            sessions.remove(c);
            c.close();
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake hs) {
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        UUID id = sessions.remove(conn);
        if (id != null) byPlayer.remove(id, conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject o = JsonParser.parseString(message).getAsJsonObject();
            if (!"auth".equals(o.get("type").getAsString())) return;
            Link link = plugin.tokens.get(o.get("token").getAsString());
            JsonObject reply = new JsonObject();
            if (link == null) {
                reply.addProperty("type", "error");
                reply.addProperty("message", "Enlace inválido o caducado. Usa /voice otra vez.");
                conn.send(reply.toString());
                conn.close();
                return;
            }
            WebSocket old = byPlayer.put(link.id(), conn);
            if (old != null && old != conn) {
                sessions.remove(old);
                old.close();
            }
            sessions.put(conn, link.id());
            reply.addProperty("type", "ready");
            reply.addProperty("name", link.name());
            conn.send(reply.toString());
        } catch (Exception e) {
            conn.close();
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer msg) {
        UUID speaker = sessions.get(conn);
        if (speaker == null || plugin.muted.contains(speaker)) return;
        int len = msg.remaining();
        if (len < 2 || len > 4096) return;
        Snap s = plugin.snaps.get(speaker);
        if (s == null) return;

        byte[] pcm = new byte[len];
        msg.get(pcm);
        int speakerId = speaker.hashCode();
        double max = plugin.maxDistance;
        double full = plugin.fullDistance;

        for (Map.Entry<UUID, WebSocket> e : byPlayer.entrySet()) {
            UUID id = e.getKey();
            if (id.equals(speaker)) continue;
            WebSocket target = e.getValue();
            if (!target.isOpen()) continue;
            Snap l = plugin.snaps.get(id);
            if (l == null || !l.world().equals(s.world())) continue;

            double dx = s.x() - l.x(), dy = s.y() - l.y(), dz = s.z() - l.z();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > max) continue;

            float gain = d <= full ? 1f : (float) Math.max(0, 1 - (d - full) / (max - full));

            // Panoramica: producto escalar con el vector "derecha" del oyente
            double yaw = Math.toRadians(l.yaw());
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            double h = Math.hypot(dx, dz);
            float pan = h < 0.01 ? 0f : (float) Math.max(-1, Math.min(1, (dx * rx + dz * rz) / h));

            ByteBuffer out = ByteBuffer.allocate(12 + len).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(speakerId).putFloat(gain).putFloat(pan).put(pcm).flip();
            target.send(out);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        plugin.getLogger().warning("WebSocket: " + ex);
    }

    @Override
    public void onStart() {
        plugin.getLogger().info("Servidor de voz escuchando en el puerto " + getPort());
    }
}
