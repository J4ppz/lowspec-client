package com.lowspec.client;

import java.io.IOException;
import java.lang.reflect.Method;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowspec.client.LowSpecClient.Logo;
import com.lowspec.client.LowSpecClient.Ui;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Friends + presence + "other LowSpec players" logo. Talks to your own Cloudflare Worker. */
public final class Friends {

    // Defaults. You can ALSO set these without rebuilding, in config/lowspec-friends.properties
    // (lines "backend=" and "key="). Everyone must use the same backend and key.
    static final String DEFAULT_BACKEND = "https://lowspec.japzsir.workers.dev/";           // e.g. "https://lowspec.yourname.workers.dev"
    static final String DEFAULT_KEY = "poop-tiki67";      // same text as the KEY variable in Cloudflare

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("lowspec-friends.properties");

    static String backend = DEFAULT_BACKEND;
    static String key = DEFAULT_KEY;
    static String myCode = "";
    static boolean invisible;
    static String crackedMode = "auto"; // auto / true / false
    private static Boolean crackedCache;

    static final ConcurrentHashMap<String, String> friends = new ConcurrentHashMap<>();   // code -> last name
    static final Set<String> crackedFriends = ConcurrentHashMap.newKeySet();

    // Live data. Written by HTTP threads as whole immutable snapshots, read on the game thread.
    static volatile Map<String, String[]> status = Map.of();   // friend code -> {name, server, cracked}
    static volatile List<String[]> nearby = List.of();          // {code, name, cracked}
    static volatile Set<String> modNames = Set.of();            // lowercase names of LowSpec users here
    static volatile int version;
    static volatile String message = "";
    static volatile long messageUntil;

    private static KeyMapping friendsKey;
    private static int tickCount;
    private static boolean beatSent;
    private static String lastServer = "";
    private static long lastBeatMs, lastOnlineMs, lastStatusMs;

    private Friends() { }

    static boolean enabled() {
        return !backend.isEmpty();
    }

    static void say(String s) {
        message = s;
        messageUntil = System.currentTimeMillis() + 7000;
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
    }

    // ---------- cracked (offline-mode) detection ----------
    static boolean isMyAccountCracked(Minecraft mc) {
        if (crackedMode.equals("true")) return true;
        if (crackedMode.equals("false")) return false;
        if (crackedCache == null) crackedCache = detectCracked(mc);
        return crackedCache;
    }

    private static boolean detectCracked(Minecraft mc) {
        try {
            Object user = mc.getUser();
            String name = mc.getUser().getName();
            for (Method m : user.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() == UUID.class) {
                    UUID id = (UUID) m.invoke(user);
                    if (id != null) {
                        UUID offline = UUID.nameUUIDFromBytes(
                                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                        if (id.equals(offline) || id.version() == 3) return true;
                    }
                }
                if (m.getReturnType() == String.class && m.getName().equals("getAccessToken")) {
                    String tok = (String) m.invoke(user);
                    if (tok == null || tok.length() < 20) return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
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

    // ---------- adding friends ----------
    static void addByCode(String raw) {
        String code = raw.trim().toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z0-9]{12}")) {
            say("An ID is exactly 12 letters/numbers");
            return;
        }
        if (code.equals(myCode)) {
            say("That's your own ID");
            return;
        }
        friends.putIfAbsent(code, code);
        save();
        version++;
        say("Added by ID. They show here once they're online");
        refreshNow();
    }

    /** Looks the name up among players who are online with LowSpec; falls back to treating it as an ID. */
    static void addByName(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return;
        say("Searching...");
        get("/find?name=" + enc(t), o -> {
            for (JsonElement e : o.getAsJsonArray("users")) {
                JsonObject u = e.getAsJsonObject();
                String code = u.get("code").getAsString();
                if (code.equals(myCode)) continue;
                String name = u.get("name").getAsString();
                friends.put(code, name);
                if (u.has("cracked") && u.get("cracked").getAsBoolean()) crackedFriends.add(code);
                else crackedFriends.remove(code);
                save();
                version++;
                say("Added " + name);
                return;
            }
            if (t.matches("[a-z0-9]{12}")) addByCode(t);
            else say("Nobody called \"" + t + "\" is online with LowSpec. Use their ID instead");
        }, () -> say("Can't reach the friends server. Check backend and key"));
    }

    // ---------- network ----------
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(backend + path))
                .timeout(Duration.ofSeconds(8))
                .header("x-key", key)
                .header("content-type", "application/json");
    }

    private static void post(String path, JsonObject body) {
        try {
            HTTP.sendAsync(req(path).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException ignored) {
        }
    }

    private static void get(String path, Consumer<JsonObject> then, Runnable fail) {
        try {
            HTTP.sendAsync(req(path).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> {
                        if (r.statusCode() != 200) {
                            if (fail != null) fail.run();
                            return;
                        }
                        try {
                            then.accept(JsonParser.parseString(r.body()).getAsJsonObject());
                        } catch (RuntimeException ignored) {
                            if (fail != null) fail.run();
                        }
                    })
                    .exceptionally(ex -> {
                        if (fail != null) fail.run();
                        return null;
                    });
        } catch (RuntimeException e) {
            if (fail != null) fail.run();
        }
    }

    private static void beat(Minecraft mc, String server) {
        JsonObject o = new JsonObject();
        o.addProperty("code", myCode);
        o.addProperty("name", mc.getUser().getName());
        o.addProperty("server", server);
        o.addProperty("cracked", isMyAccountCracked(mc));
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

    private static boolean flag(JsonObject u) {
        return u.has("cracked") && u.get("cracked").getAsBoolean();
    }

    private static void pollOnline(String server) {
        get("/online?server=" + enc(server), o -> {
            List<String[]> list = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonElement e : o.getAsJsonArray("users")) {
                JsonObject u = e.getAsJsonObject();
                String code = u.get("code").getAsString();
                String name = u.get("name").getAsString();
                if (code.equals(myCode)) continue;
                list.add(new String[]{code, name, flag(u) ? "1" : "0"});
                names.add(name.toLowerCase(Locale.ROOT));
            }
            nearby = list;
            modNames = names;
            version++;
        }, null);
    }

    private static void pollStatus() {
        String codes = String.join(",", friends.keySet());
        get("/status?codes=" + enc(codes), o -> {
            Map<String, String[]> m = new HashMap<>();
            boolean changed = false;
            for (var en : o.getAsJsonObject("status").entrySet()) {
                JsonObject u = en.getValue().getAsJsonObject();
                String code = en.getKey();
                String name = u.get("name").getAsString();
                boolean cr = flag(u);
                m.put(code, new String[]{name, u.get("server").getAsString(), cr ? "1" : "0"});
                if (!name.equals(friends.get(code))) {
                    friends.put(code, name);
                    changed = true;
                }
                if (cr != crackedFriends.contains(code)) {
                    if (cr) crackedFriends.add(code);
                    else crackedFriends.remove(code);
                    changed = true;
                }
            }
            status = m;
            if (changed) save();
            version++;
        }, null);
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
        String b = p.getProperty("backend", "").trim();
        if (!b.isEmpty()) backend = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        String k = p.getProperty("key", "").trim();
        if (!k.isEmpty()) key = k;
        crackedMode = p.getProperty("cracked", "auto").trim().toLowerCase(Locale.ROOT);

        myCode = p.getProperty("me", "");
        if (!myCode.matches("[a-z0-9]{8,16}")) myCode = newCode();
        invisible = Boolean.parseBoolean(p.getProperty("invisible", "false"));
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("friend.")) friends.put(name.substring(7), p.getProperty(name, ""));
            if (name.startsWith("friendc.") && Boolean.parseBoolean(p.getProperty(name))) {
                crackedFriends.add(name.substring(8));
            }
        }
        save();
    }

    static void save() {
        Properties p = new Properties();
        p.setProperty("backend", backend);
        p.setProperty("key", key);
        p.setProperty("cracked", crackedMode);
        p.setProperty("me", myCode);
        p.setProperty("invisible", Boolean.toString(invisible));
        for (var e : friends.entrySet()) p.setProperty("friend." + e.getKey(), e.getValue());
        for (String c : crackedFriends) p.setProperty("friendc." + c, "true");
        try (var out = Files.newOutputStream(FILE)) {
            p.store(out, "LowSpec friends - backend/key: your Cloudflare Worker address and secret word");
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
        private static final int TAG = 0xFFFFB86B;
        private final Screen parent;
        private final StringBuilder input = new StringBuilder();
        private int seen = -1;
        private long openNs, lastNs, lastRefresh;
        private int px, py, pw, ph, rowsF, rowsN, fY, nY, addLabelY, inputY, hintY, backY, half, inputW;
        private float invHover, backHover, addHover;
        private final float[] rowHover = new float[16];
        private List<String> order = List.of();
        private List<String[]> near = List.of();
        private boolean myCracked;

        FriendsScreen(Screen parent) {
            super(Component.literal("Friends"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            order = sortedFriends();
            near = nearby;
            myCracked = isMyAccountCracked(Minecraft.getInstance());

            int slots = Math.max(2, (this.height - 8 - 190) / ROW);
            rowsF = Math.max(1, Math.min(order.isEmpty() ? 1 : order.size(), slots - 1));
            rowsN = Math.max(1, Math.min(near.isEmpty() ? 1 : near.size(), Math.min(4, slots - rowsF)));

            pw = Math.min(440, this.width - 16);
            half = (pw - 24 - 8) / 2;
            int fYr = 64;
            int nYr = fYr + rowsF * ROW + 20;
            int addLabelR = nYr + rowsN * ROW + 6;
            int inputYr = addLabelR + 14;
            int hintYr = inputYr + 25;
            int backYr = hintYr + 15;
            ph = backYr + 36;
            px = (this.width - pw) / 2;
            py = Math.max(4, (this.height - ph) / 2);
            fY = py + fYr;
            nY = py + nYr;
            addLabelY = py + addLabelR;
            inputY = py + inputYr;
            hintY = py + hintYr;
            backY = py + backYr;
            inputW = pw - 24 - 8 - 60;

            // Invisible click targets. Drawing is custom in extractRenderState.
            for (int i = 0; i < rowsF && i < order.size(); i++) {
                String code = order.get(i);
                this.addRenderableWidget(Button.builder(Component.empty(), b -> {
                    friends.remove(code);
                    crackedFriends.remove(code);
                    save();
                    version++;
                }).bounds(px + pw - 12 - 22, fY + i * ROW, 22, 22).build());
            }
            for (int j = 0; j < rowsN && j < near.size(); j++) {
                String[] u = near.get(j);
                if (friends.containsKey(u[0])) continue;
                this.addRenderableWidget(Button.builder(Component.empty(), b -> {
                    friends.put(u[0], u[1]);
                    if (u[2].equals("1")) crackedFriends.add(u[0]);
                    save();
                    refreshNow();
                    version++;
                }).bounds(px + pw - 12 - 50, nY + j * ROW, 50, 22).build());
            }
            this.addRenderableWidget(Button.builder(Component.empty(), b -> submit())
                    .bounds(px + pw - 12 - 60, inputY, 60, 22).build());
            this.addRenderableWidget(Button.builder(Component.empty(), b -> toggleInvisible())
                    .bounds(px + 12, backY, half, 24).build());
            this.addRenderableWidget(Button.builder(Component.empty(), b -> this.onClose())
                    .bounds(px + 12 + half + 8, backY, pw - 24 - half - 8, 24).build());

            if (openNs == 0) openNs = lastNs = System.nanoTime();
            seen = version;
        }

        private void submit() {
            String t = input.toString().trim();
            if (t.isEmpty()) return;
            if (!enabled()) {
                say("Not set up yet - put your Worker address in backend= in lowspec-friends.properties");
                return;
            }
            addByName(t);
            input.setLength(0);
        }

        private static char keyChar(int k) {
            if (k >= 65 && k <= 90) return (char) ('a' + (k - 65));
            if (k >= 48 && k <= 57) return (char) ('0' + (k - 48));
            if (k >= 320 && k <= 329) return (char) ('0' + (k - 320));
            if (k == 45) return '_';
            return 0;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            int k = event.key();
            if (k == 259) { // backspace
                if (input.length() > 0) input.setLength(input.length() - 1);
                return true;
            }
            if (k == 257 || k == 335) { // enter
                submit();
                return true;
            }
            char c = keyChar(k);
            if (c != 0) {
                if (input.length() < 16) input.append(c);
                return true;
            }
            return super.keyPressed(event);
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
            int tag = Ui.a(TAG, e);

            g.fill(0, 0, this.width, this.height, Ui.a(0x78000000, e));
            Ui.bigPanel(g, px, py + oy, pw, ph, e);

            Logo.draw(g, px + 14, py + oy + 12, 2, e);
            Ui.text(g, f, "Friends", px + 14 + 32, py + oy + 14, white, false);
            String sub = enabled()
                    ? "Your ID: " + myCode + (myCracked ? "  (cracked account)" : "")
                    : "Not set up yet - see backend= in lowspec-friends.properties";
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

                    String nm = fit(f, displayName(code), pw / 4);
                    Ui.text(g, f, nm, px + 34, ry + oy + 7, white, false);
                    int after = px + 34 + Ui.tw(f, nm);
                    if (crackedFriends.contains(code)) {
                        Ui.text(g, f, " (cracked)", after, ry + oy + 7, tag, false);
                        after += Ui.tw(f, " (cracked)");
                    }

                    String right = !online ? "Offline" : (st[1].isEmpty() ? "Online" : st[1]);
                    int maxRight = px + pw - 12 - 22 - 8 - (after + 10);
                    right = fit(f, right, Math.max(30, maxRight));
                    Ui.text(g, f, right, px + pw - 12 - 22 - 8 - Ui.tw(f, right), ry + oy + 7,
                            online ? mint : grey, false);

                    int bx = px + pw - 12 - 22;
                    Ui.rr(g, bx, ry + oy + 1, 22, 20, Ui.a(Ui.mix(0x30FFFFFF, 0x66FFFFFF,
                            Ui.in(mx, my, bx, ry, 22, 22) ? 1f : 0f), e));
                    Ui.text(g, f, "x", bx + 11 - Ui.tw(f, "x") / 2, ry + oy + 7, white, false);
                } else if (i == 0) {
                    Ui.text(g, f, "No friends yet - type a name or ID below", px + 22, ry + oy + 7, grey, false);
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
                    String nm = fit(f, u[1], pw / 3);
                    Ui.text(g, f, nm, px + 22, ry + oy + 7, white, false);
                    if (u[2].equals("1")) {
                        Ui.text(g, f, " (cracked)", px + 22 + Ui.tw(f, nm), ry + oy + 7, tag, false);
                    }
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

            // ----- add a friend: text box + button -----
            Ui.text(g, f, "Add a friend", px + 14, addLabelY + oy, grey, false);
            Ui.rr(g, px + 11, inputY + oy - 1, inputW + 2, 24, Ui.a(0x8855FFAA, e));
            Ui.rr(g, px + 12, inputY + oy, inputW, 22, Ui.a(0xFF0A0F1A, e));
            boolean caret = (System.nanoTime() / 500_000_000L) % 2 == 0;
            if (input.length() == 0) {
                Ui.text(g, f, caret ? "|" : " ", px + 18, inputY + oy + 7, mint, false);
                Ui.text(g, f, "player name or 12-letter ID", px + 24, inputY + oy + 7,
                        Ui.a(0xFF566074, e), false);
            } else {
                String shown = input.toString() + (caret ? "|" : "");
                Ui.text(g, f, shown, px + 18, inputY + oy + 7, white, false);
            }
            boolean ah = Ui.in(mx, my, px + pw - 12 - 60, inputY, 60, 22);
            addHover += ((ah ? 1f : 0f) - addHover) * k;
            Ui.rr(g, px + pw - 12 - 60, inputY + oy, 60, 22,
                    Ui.a(Ui.mix(0x7755FFAA, 0xCC55FFAA, addHover), e));
            Ui.text(g, f, "Add", px + pw - 12 - 30 - Ui.tw(f, "Add") / 2, inputY + oy + 7, white, false);

            boolean msgOn = nowMs < messageUntil && !message.isEmpty();
            String hint = msgOn ? message : "Press Enter to add. Names must be online with LowSpec; IDs work any time";
            Ui.text(g, f, fit(f, hint, pw - 28), px + 14, hintY + oy, msgOn ? mint : grey, false);

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
