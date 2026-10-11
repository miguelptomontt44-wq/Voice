package dev.proxvoice;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class ProxVoicePlugin extends JavaPlugin implements Listener, CommandExecutor {

    /** Foto de la posicion de un jugador (se toma en el hilo principal). */
    public record Snap(UUID world, double x, double y, double z, float yaw) {}

    /** Enlace de voz de un jugador. */
    public record Link(UUID id, String name) {}

    final Map<UUID, Snap> snaps = new ConcurrentHashMap<>();
    final Map<String, Link> tokens = new ConcurrentHashMap<>();
    final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    volatile double maxDistance;
    volatile double fullDistance;

    private final SecureRandom rng = new SecureRandom();
    private HttpServer http;
    private VoiceServer voice;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxDistance = getConfig().getDouble("max-distance", 48.0);
        fullDistance = getConfig().getDouble("full-volume-distance", 4.0);
        String bind = getConfig().getString("bind-address", "0.0.0.0");
        int webPort = getConfig().getInt("web-port", 8080);
        int wsPort = getConfig().getInt("ws-port", 8081);

        try {
            byte[] page;
            try (InputStream in = getResource("index.html")) {
                page = in.readAllBytes();
            }
            http = HttpServer.create(new InetSocketAddress(bind, webPort), 0);
            http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            http.createContext("/", ex -> {
                try (ex) {
                    if (!"/".equals(ex.getRequestURI().getPath())) {
                        ex.sendResponseHeaders(404, -1);
                        return;
                    }
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, page.length);
                    ex.getResponseBody().write(page);
                }
            });
            http.start();

            voice = new VoiceServer(new InetSocketAddress(bind, wsPort), this);
            voice.start();
        } catch (Exception e) {
            getLogger().severe("No se pudieron iniciar los servidores web/voz: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("voice").setExecutor(this);
        // Foto de posiciones cada 2 ticks (100 ms) en el hilo principal
        getServer().getScheduler().runTaskTimer(this, this::snapshot, 1L, 2L);
        getLogger().info("ProxVoice listo. Web :" + webPort + "  WS :" + wsPort);
    }

    @Override
    public void onDisable() {
        try {
            if (voice != null) voice.stop(500);
        } catch (Exception ignored) {
        }
        if (http != null) http.stop(0);
    }

    private void snapshot() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            snaps.put(p.getUniqueId(),
                    new Snap(l.getWorld().getUID(), l.getX(), l.getY(), l.getZ(), l.getYaw()));
        }
        snaps.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        snaps.remove(id);
        muted.remove(id);
        tokens.values().removeIf(l -> l.id().equals(id));
        if (voice != null) voice.kick(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("mute")) {
            boolean nowMuted = muted.add(p.getUniqueId());
            if (!nowMuted) muted.remove(p.getUniqueId());
            p.sendMessage(Component.text(nowMuted ? "Micrófono silenciado." : "Micrófono activado.",
                    NamedTextColor.YELLOW));
            return true;
        }

        byte[] raw = new byte[24];
        rng.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.values().removeIf(l -> l.id().equals(p.getUniqueId())); // invalida enlaces anteriores
        tokens.put(token, new Link(p.getUniqueId(), p.getName()));

        String base = getConfig().getString("public-url", "http://localhost:8080");
        String ws = getConfig().getString("ws-public-url", "ws://localhost:8081");
        String url = base + "/?token=" + token + "&ws=" + URLEncoder.encode(ws, StandardCharsets.UTF_8);

        p.sendMessage(Component.text("Chat de voz: ", NamedTextColor.GREEN)
                .append(Component.text("[Haz clic para abrir]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))));
        p.sendMessage(Component.text("No compartas este enlace: es tu acceso personal.", NamedTextColor.GRAY));
        return true;
    }
}
