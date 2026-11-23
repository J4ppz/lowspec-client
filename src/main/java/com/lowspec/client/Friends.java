package com.lowspec.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowspec.client.LowSpecClient.Logo;
import com.lowspec.client.LowSpecClient.Ui;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Friends + presence + "other LowSpec players" logo. Talks to your own Cloudflare Worker. */
public final class Friends {

    // ===== SET THESE after you deploy the Worker (everyone who uses your jar shares them) =====
    static final String BACKEND = "https://lowspec.japzsir.workers.dev";           // e.g. "https://lowspec.yourname.workers.dev"
    static final String KEY = "poop-tiki67";      // the same text as the KEY variable in Cloudflare
    // ==========================================================================================

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("lowspec-friends.properties");

    static String myCode = "";
    static boolean invisible;
    static final LinkedHashMap<String, String> friends = new LinkedHashMap<>();

    // Live data. Written by HTTP threads as whole immutable snapshots, read on the game thread.
    static volatile Map<String, String[]> status = Map.of();   // friend code -> {name, server}
    static volatile List<String[]> nearby = List.of();          // {code, name}
    static volatile Set<String> modNames = Set.of();            // lowercase names of LowSpec users here
    static volatile int version;

    private static KeyMapping friendsKey;
    private static int tickCount;
    private static boolean beatSent;
    private static String lastServer = "";
    private static long lastBeatMs, lastOnlineMs, lastStatusMs;

    private Friends() { }

    static boolean enabled() {
        return !BACKEND.isEmpty();
    }

    // ---------- used by the name tag mixin ----------
    public static boolean isModUser(String name) {
        return name != null && modNames.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean showLogos() {
        return LowSpecClient.Settings.has(LowSpecClient.Mod.NAMETAG)
                && LowSpecClient.Settings.flag(LowSpecClient.O.NT_LOGO);
    }

    // ---------- setup ----------
    public static void init() {
        load();
        KeyMapping.Category cat = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("lowspec", "social"));
        friendsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.lowspec.friends", InputConstants.Type.KEYSYM, InputConstants.KEY_O, cat));

        ClientTickEvents.END_CLIENT_TICK.register(Friends::tick);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (beatSent) bye(true);
        }));

                ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                Screens.getButtons(screen).add(Button.builder(Component.literal("Friends"),
                        b -> client.setScreenAndShow(new FriendsScreen(screen)))
                        .bounds(6, 6, 70, 20).build());
            }
        });
    }

    // ---------- presence loop ----------
    private static void tick(Minecraft mc) {
        while (friendsKey.consumeClick()) {
            mc.setScreenAndShow(new FriendsScreen(null));
        }
        if (!enabled() || ++tickCount % 40 != 0) return; // every ~2 seconds

        long now = System.currentTimeMillis();
        if (mc.player != null && !invisible) {
            String server = currentServer(mc);
            // write only when something changed (Cloudflare's free plan has few writes per day)
            if (!beatSent || !server.equals(lastServer) || now - lastBeatMs > 20 * 60_000L) {
                beat(mc, server);
                lastServer = server;
                lastBeatMs = now;
                beatSent = true;
            }
            if (!server.isEmpty() && now - lastOnlineMs > 30_000L) {
                lastOnlineMs = now;
                pollOnline(server);
            }
        } else if (beatSent) {
            bye(false);
            beatSent = false;
            lastServer = "";
            modNames = Set.of();
            nearby = List.of();
            version++;
        }
        if (!friends.isEmpty() && now - lastStatusMs > 45_000L) {
            lastStatusMs = now;
            pollStatus();
        }
    }

    static void refreshNow() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        lastStatusMs = now;
        if (!friends.isEmpty()) pollStatus();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String s = currentServer(mc);
            if (!s.isEmpty()) {
                lastOnlineMs = now;
                pollOnline(s);
            }
        }
    }

    static void toggleInvisible() {
        invisible = !invisible;
        save();
        if (invisible && beatSent) {
            bye(false);
            beatSent = false;
            lastServer = "";
            modNames = Set.of();
            nearby = List.of();
            version++;
        }
    }

    private static String currentServer(Minecraft mc) {
        var sd = mc.getCurrentServer();
        return sd == null ? "" : norm(sd.ip);
    }

    static String norm(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        return t.endsWith(":25565") ? t.substring(0, t.length() - 6) : t;
    }

    // ---------- network ----------
    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(BACKEND + path))
                .timeout(Duration.ofSeconds(8))
                .header("x-key", KEY)
                .header("content-type", "application/json");
    }

    private static void post(String path, JsonObject body) {
        try {
            HTTP.sendAsync(req(path).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException ignored) {
        }
    }

    private static void get(String path, Consumer<JsonObject> then) {
        try {
            HTTP.sendAsync(req(path).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> {
                        if (r.statusCode() != 200) return;
                        try {
                            then.accept(JsonParser.parseString(r.body()).getAsJsonObject());
                        } catch (RuntimeException ignored) {
                        }
                    });
        } catch (RuntimeException ignored) {
        }
    }

    private static void beat(Minecraft mc, String server) {
        JsonObject o = new JsonObject();
        o.addProperty("code", myCode);
        o.addProperty("name", mc.getUser().getName());
        o.addProperty("server", server);
        post("/beat", o);
    }

    private static void bye(boolean sync) {
        JsonObject o = new JsonObject();
        o.addProperty("code", myCode);
        if (sync) {
            try {
                HTTP.send(req("/bye").timeout(Duration.ofSeconds(3))
                                .POST(HttpRequest.BodyPublishers.ofString(o.toString())).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            }
        } else {
            post("/bye", o);
        }
    }

    private static void pollOnline(String server) {
        get("/online?server=" + URLEncoder.encode(server, StandardCharsets.UTF_8), o -> {
            List<String[]> list = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonElement e : o.getAsJsonArray("users")) {
                JsonObject u = e.getAsJsonObject();
                String code = u.get("code").getAsString();
                String name = u.get("name").getAsString();
                if (code.equals(myCode)) continue;
                list.add(new String[]{code, name});
                names.add(name.toLowerCase(Locale.ROOT));
            }
            nearby = list;
            modNames = names;
            version++;
        });
    }

    private static void pollStatus() {
        String codes = String.join(",", friends.keySet());
        get("/status?codes=" + URLEncoder.encode(codes, StandardCharsets.UTF_8), o -> {
            Map<String, String[]> m = new HashMap<>();
            for (var en : o.getAsJsonObject("status").entrySet()) {
                JsonObject u = en.getValue().getAsJsonObject();
                m.put(en.getKey(), new String[]{u.get("name").getAsString(), u.get("server").getAsString()});
            }
            status = m;
            version++;
        });
    }

    // ---------- storage ----------
    private static void load() {
        Properties p = new Properties();
        try {
            if (Files.exists(FILE)) {
                try (var in = Files.newInputStream(FILE)) {
                    p.load(in);
                }
            }
        } catch (IOException ignored) {
        }
        myCode = p.getProperty("me", "");
        if (!myCode.matches("[a-z0-9]{8,16}")) myCode = newCode();
        invisible = Boolean.parseBoolean(p.getProperty("invisible", "false"));
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("friend.")) friends.put(k.substring(7), p.getProperty(k, ""));
        }
        save();
    }

    static void save() {
        Properties p = new Properties();
        p.setProperty("me", myCode);
        p.setProperty("invisible", Boolean.toString(invisible));
        for (var e : friends.entrySet()) p.setProperty("friend." + e.getKey(), e.getValue());
        try (var out = Files.newOutputStream(FILE)) {
            p.store(out, "LowSpec friends");
        } catch (IOException ignored) {
        }
    }

    private static String newCode() {
        String a = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(a.charAt(r.nextInt(a.length())));
        return sb.toString();
    }

    static String displayName(String code) {
        String[] s = status.get(code);
        if (s != null && !s[0].isEmpty()) return s[0];
        String n = friends.get(code);
        return n == null || n.isEmpty() ? code : n;
    }

    static List<String> sortedFriends() {
        List<String> l = new ArrayList<>(friends.keySet());
        l.sort((a, b) -> {
            boolean oa = status.containsKey(a), ob = status.containsKey(b);
            if (oa != ob) return oa ? -1 : 1;
            return displayName(a).compareToIgnoreCase(displayName(b));
        });
        return l;
    }

    // ---------- Friends screen ----------
    static final class FriendsScreen extends Screen {
        private static final int ROW = 24;
        private final Screen parent;
        private int seen = -1;
        private long openNs, lastNs, lastRefresh;
        private int px, py, pw, ph, rowsF, rowsN, fY, nY, backY, half;
        private float invHover, backHover;
        private final float[] rowHover = new float[16];
        private List<String> order = List.of();
        private List<String[]> near = List.of();

        FriendsScreen(Screen parent) {
            super(Component.literal("Friends"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            order = sortedFriends();
            near = nearby;

            int slots = Math.max(2, (this.height - 8 - 160) / ROW);
            rowsF = Math.max(1, Math.min(Math.min(7, order.size() == 0 ? 1 : order.size()), slots - 1));
            rowsN = Math.max(1, Math.min(Math.min(5, near.size() == 0 ? 1 : near.size()), slots - rowsF));

            pw = Math.min(440, this.width - 16);
            half = (pw - 24 - 8) / 2;
            int top = 50;
            ph = top + 14 + rowsF * ROW + 8 + 14 + rowsN * ROW + 8 + 24 + 14;
            px = (this.width - pw) / 2;
            py = Math.max(4, (this.height - ph) / 2);
            fY = py + top + 14;
            nY = fY + rowsF * ROW + 8 + 14;
            backY = nY + rowsN * ROW + 8;

            // Invisible click targets. Drawing is custom in extractRenderState.
            for (int i = 0; i < rowsF && i < order.size(); i++) {
                String code = order.get(i);
                this.addRenderableWidget(Button.builder(Component.empty(), b -> {
                    friends.remove(code);
                    save();
                    version++;
                }).bounds(px + pw - 12 - 22, fY + i * ROW, 22, 22).build());
            }
            for (int j = 0; j < rowsN && j < near.size(); j++) {
                String[] u = near.get(j);
                if (friends.containsKey(u[0])) continue;
                this.addRenderableWidget(Button.builder(Component.empty(), b -> {
                    friends.put(u[0], u[1]);
                    save();
                    refreshNow();
                    version++;
                }).bounds(px + pw - 12 - 50, nY + j * ROW, 50, 22).build());
            }
            this.addRenderableWidget(Button.builder(Component.empty(), b -> {
                toggleInvisible();
            }).bounds(px + 12, backY, half, 24).build());
            this.addRenderableWidget(Button.builder(Component.empty(), b -> this.onClose())
                    .bounds(px + 12 + half + 8, backY, pw - 24 - half - 8, 24).build());

            if (openNs == 0) openNs = lastNs = System.nanoTime();
            seen = version;
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreenAndShow(parent);
        }

        private static String fit(Font f, String s, int maxW) {
            if (Ui.tw(f, s) <= maxW) return s;
            String t = s;
            while (t.length() > 1 && Ui.tw(f, t + "..") > maxW) t = t.substring(0, t.length() - 1);
            return t + "..";
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            if (seen != version) {
                this.rebuildWidgets();
            }
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastRefresh > 15_000L) {
                lastRefresh = nowMs;
                refreshNow();
            }

            long now = System.nanoTime();
            float dt = Math.min((now - lastNs) / 1e9f, 0.25f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 16f);
            float p = Math.min(1f, (now - openNs) / 200_000_000f);
            float e = 1f - (1f - p) * (1f - p) * (1f - p);
            int oy = (int) ((1f - e) * 10);
            Font f = this.font;
            int white = Ui.a(0xFFFFFFFF, e);
            int grey = Ui.a(0xFF9AA4C0, e);
            int mint = Ui.a(0xFF55FFAA, e);

            g.fill(0, 0, this.width, this.height, Ui.a(0x78000000, e));
            Ui.bigPanel(g, px, py + oy, pw, ph, e);

            Logo.draw(g, px + 14, py + oy + 12, 2, e);
            Ui.text(g, f, "Friends", px + 14 + 32, py + oy + 14, white, false);
            String sub = enabled() ? "Your ID: " + myCode : "Not set up yet - fill in BACKEND in Friends.java";
            Ui.text(g, f, fit(f, sub, pw - 70), px + 14 + 32, py + oy + 27, grey, false);

            Ui.text(g, f, "Your friends", px + 14, fY - 12 + oy, grey, false);
            for (int i = 0; i < rowsF; i++) {
                int ry = fY + i * ROW;
                boolean hov = Ui.in(mx, my, px + 12, ry, pw - 24, 22);
                rowHover[i] += ((hov ? 1f : 0f) - rowHover[i]) * k;
                Ui.card(g, px + 12, ry + oy, pw - 24, 22, rowHover[i], e);

                if (i < order.size()) {
                    String code = order.get(i);
                    String[] st = status.get(code);
                    boolean online = st != null;
                    Ui.rr(g, px + 22, ry + oy + 8, 6, 6, Ui.a(online ? 0xFF55FF55 : 0xFF6B7280, e));
                    Ui.text(g, f, fit(f, displayName(code), pw / 3), px + 34, ry + oy + 7, white, false);

                    String right = !online ? "Offline" : (st[1].isEmpty() ? "Online" : st[1]);
                    right = fit(f, right, pw / 2 - 50);
                    Ui.text(g, f, right, px + pw - 12 - 22 - 8 - Ui.tw(f, right), ry + oy + 7,
                            online ? mint : grey, false);

                    int bx = px + pw - 12 - 22;
                    Ui.rr(g, bx, ry + oy + 1, 22, 20, Ui.a(Ui.mix(0x30FFFFFF, 0x66FFFFFF,
                            Ui.in(mx, my, bx, ry, 22, 22) ? 1f : 0f), e));
                    Ui.text(g, f, "x", bx + 11 - Ui.tw(f, "x") / 2, ry + oy + 7, white, false);
                } else if (i == 0) {
                    Ui.text(g, f, "No friends yet - add someone from the list below", px + 22, ry + oy + 7, grey, false);
                }
            }

            Ui.text(g, f, "On this server (also using LowSpec)", px + 14, nY - 12 + oy, grey, false);
            for (int j = 0; j < rowsN; j++) {
                int ry = nY + j * ROW;
                boolean hov = Ui.in(mx, my, px + 12, ry, pw - 24, 22);
                rowHover[8 + j] += ((hov ? 1f : 0f) - rowHover[8 + j]) * k;
                Ui.card(g, px + 12, ry + oy, pw - 24, 22, rowHover[8 + j], e);

                if (j < near.size()) {
                    String[] u = near.get(j);
                    Ui.text(g, f, fit(f, u[1], pw / 2), px + 22, ry + oy + 7, white, false);
                    int bx = px + pw - 12 - 50;
                    if (friends.containsKey(u[0])) {
                        Ui.text(g, f, "Friend", bx + 25 - Ui.tw(f, "Friend") / 2, ry + oy + 7, grey, false);
                    } else {
                        Ui.rr(g, bx, ry + oy + 1, 50, 20, Ui.a(Ui.mix(0x5555FFAA, 0xAA55FFAA,
                                Ui.in(mx, my, bx, ry, 50, 22) ? 1f : 0f), e));
                        Ui.text(g, f, "Add", bx + 25 - Ui.tw(f, "Add") / 2, ry + oy + 7, white, false);
                    }
                } else if (j == 0) {
                    Ui.text(g, f, "Nobody here yet (join a multiplayer server)", px + 22, ry + oy + 7, grey, false);
                }
            }

            boolean ih = Ui.in(mx, my, px + 12, backY, half, 24);
            invHover += ((ih ? 1f : 0f) - invHover) * k;
            Ui.rrAA(g, px + 12, backY + oy, half, 24, Ui.a(Ui.mix(0x38FFFFFF, 0x70FFFFFF, invHover), e));
            String inv = "Appear offline: " + (invisible ? "ON" : "OFF");
            Ui.text(g, f, inv, px + 12 + half / 2 - Ui.tw(f, inv) / 2, backY + oy + 8, white, false);

            int bx2 = px + 12 + half + 8;
            int bw2 = pw - 24 - half - 8;
            boolean bh = Ui.in(mx, my, bx2, backY, bw2, 24);
            backHover += ((bh ? 1f : 0f) - backHover) * k;
            Ui.rrAA(g, bx2, backY + oy, bw2, 24, Ui.a(Ui.mix(0x38FFFFFF, 0x70FFFFFF, backHover), e));
            Ui.text(g, f, "Back", bx2 + bw2 / 2 - Ui.tw(f, "Back") / 2, backY + oy + 8, white, false);
        }
    }
}
