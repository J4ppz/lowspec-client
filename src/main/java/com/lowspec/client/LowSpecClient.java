package com.lowspec.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Properties;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public class LowSpecClient implements ClientModInitializer {

    private static KeyMapping menuKey;
    private static KeyMapping zoomKey;

    private static boolean zoomActive;
    private static int zoomBase;
    private static float zoomCur;
    private static boolean sprintForced;

    private static final HudElement NO_HUD = (graphics, delta) -> { };

    private static final Style LOGO_STYLE = Style.EMPTY.withFont(
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("lowspec", "logo")));

    // ---------- used by the mixins ----------
    public static boolean nameTagOn() {
        return Settings.has(Mod.NAMETAG) && Settings.flag(O.NT_SELF);
    }

    public static Component withLogo(Component name) {
        if (!Settings.flag(O.NT_LOGO)) return name;
        MutableComponent root = Component.empty();
        root.append(Component.literal("\uE000").withStyle(LOGO_STYLE));
        root.append(Component.literal(" "));
        root.append(name);
        return root;
    }

    @Override
    public void onInitializeClient() {
        Settings.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("lowspec", "main"));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.lowspec.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));
        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.lowspec.zoom", InputConstants.Type.KEYSYM, InputConstants.KEY_C, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.consumeClick()) {
                client.setScreenAndShow(new MenuScreen());
            }
            tickSprint(client);
            tickZoom(client);
        });

        HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS,
                original -> (Settings.has(Mod.EFFECTS) && Settings.flag(O.EFF_HIDE)) ? NO_HUD : original);

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("lowspec", "hud"),
                Hud::extract);

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                ScreenEvents.afterExtract(screen).register(Marks::title);
            } else if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
                ScreenEvents.afterExtract(screen).register(Marks::corner);
            }
        });
    }

    private static void rebindZoom(int code) {
        zoomKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(code));
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    // ---------- Toggle sprint ----------
    private static void tickSprint(Minecraft mc) {
        if (mc.player == null) return;
        boolean forward = mc.options.keyUp.isDown();
        boolean blocked = Settings.flag(O.SPRINT_ITEM) && mc.player.isUsingItem();
        if (Settings.has(Mod.SPRINT) && forward && !blocked) {
            mc.options.keySprint.setDown(true);
            sprintForced = true;
        } else if (sprintForced) {
            mc.options.keySprint.setDown(false);
            sprintForced = false;
        }
    }

    // ---------- Smooth zoom (eases by real time, every frame) ----------
    private static final float[] ZOOM_RATE = {26f, 15f, 8f};
    private static long zoomLastNs = System.nanoTime();

    private static void tickZoom(Minecraft mc) {
        updateZoom(mc);
    }

    /** Called from both the tick and the HUD, so it eases by real time, not by tick count. */
    static void updateZoom(Minecraft mc) {
        long now = System.nanoTime();
        float dt = Math.min((now - zoomLastNs) / 1e9f, 0.1f);
        zoomLastNs = now;

        var fov = mc.options.fov();
        boolean want = Settings.has(Mod.ZOOM) && zoomKey.isDown();
        float rate = ZOOM_RATE[Math.floorMod(Settings.get(O.ZOOM_SMOOTH), ZOOM_RATE.length)];
        float k = 1f - (float) Math.exp(-dt * rate);

        if (want) {
            if (!zoomActive) {
                zoomActive = true;
                zoomBase = fov.get();
                zoomCur = zoomBase;
            }
            zoomCur += (Settings.get(O.ZOOM_FOV) - zoomCur) * k;
            int target = Math.round(zoomCur);
            if (fov.get() != target) fov.set(target);
        } else if (zoomActive) {
            zoomCur += (zoomBase - zoomCur) * k;
            if (Math.abs(zoomBase - zoomCur) < 0.6f) {
                fov.set(zoomBase);
                zoomActive = false;
            } else {
                int target = Math.round(zoomCur);
                if (fov.get() != target) fov.set(target);
            }
        }
    }

    // ---------- Option definitions ----------
    static final class Opt {
        static final int BOOL = 0, CYCLE = 1, INT = 2, KEY = 3;

        final String id, label, unit;
        final int type, def, min, max, step;
        final String[] labels;

        private Opt(String id, String label, int type, int def, int min, int max, int step,
                    String unit, String[] labels) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.def = def;
            this.min = min;
            this.max = max;
            this.step = step;
            this.unit = unit;
            this.labels = labels;
        }

        static Opt bool(String id, String label, boolean def) {
            return new Opt(id, label, BOOL, def ? 1 : 0, 0, 1, 1, "", null);
        }

        static Opt cycle(String id, String label, int def, String... labels) {
            return new Opt(id, label, CYCLE, def, 0, labels.length - 1, 1, "", labels);
        }

        static Opt num(String id, String label, int def, int min, int max, int step, String unit) {
            return new Opt(id, label, INT, def, min, max, step, unit, null);
        }

        static Opt key(String id, String label) {
            return new Opt(id, label, KEY, 0, 0, 0, 0, "", null);
        }
    }

    static final class O {
        static final String[] COLOR_NAMES = {"White", "Mint", "Cyan", "Violet", "Yellow", "Pink"};
        static final int[] RGB = {0xFFFFFFFF, 0xFF55FFAA, 0xFF7CF5FF, 0xFFB79CFF, 0xFFFFE066, 0xFFFF8CC6};

        static final Opt FPS_COLOR = Opt.cycle("fps.color", "Text color", 0, COLOR_NAMES);
        static final Opt FPS_BG = Opt.bool("fps.bg", "Background", true);
        static final Opt FPS_LABEL = Opt.bool("fps.label", "Show label", true);

        static final Opt CPS_COLOR = Opt.cycle("cps.color", "Text color", 0, COLOR_NAMES);
        static final Opt CPS_BG = Opt.bool("cps.bg", "Background", true);
        static final Opt CPS_RIGHT = Opt.bool("cps.right", "Show right click", true);

        static final Opt CO_COLOR = Opt.cycle("coords.color", "Text color", 0, COLOR_NAMES);
        static final Opt CO_BG = Opt.bool("coords.bg", "Background", true);
        static final Opt CO_Y = Opt.bool("coords.y", "Show height (Y)", true);

        static final Opt ME_COLOR = Opt.cycle("mem.color", "Text color", 0, COLOR_NAMES);
        static final Opt ME_BG = Opt.bool("mem.bg", "Background", true);
        static final Opt ME_MAX = Opt.bool("mem.max", "Show max memory", true);

        static final Opt KEYS_COLOR = Opt.cycle("keys.color", "Highlight color", 0, COLOR_NAMES);
        static final Opt KEYS_BG = Opt.bool("keys.bg", "Background", true);
        static final Opt KEYS_MOUSE = Opt.bool("keys.mouse", "Show mouse buttons", true);
        static final Opt KEYS_SPACE = Opt.bool("keys.space", "Show space bar", true);

        static final Opt ARMOR_LAYOUT = Opt.cycle("armor.layout", "Layout", 0, "Vertical", "Horizontal");
        static final Opt ARMOR_LOC = Opt.cycle("armor.loc", "Location", 4,
                "Top left", "Top center", "Top right", "Middle left", "Middle right",
                "Bottom left", "Above hotbar", "Bottom right");
        static final Opt ARMOR_DUR = Opt.bool("armor.dur", "Show durability %", true);

        static final Opt EFF_HIDE = Opt.bool("eff.hide", "Hide vanilla icons", true);
        static final Opt EFF_BG = Opt.bool("eff.bg", "Background", true);
        static final Opt EFF_SIDE = Opt.cycle("eff.side", "Side", 0, "Right", "Left");

        static final Opt SPRINT_ITEM = Opt.bool("sprint.item", "Pause while using item", true);

        static final Opt ZOOM_KEY = Opt.key("zoom.key", "Zoom key");
        static final Opt ZOOM_FOV = Opt.num("zoom.fov", "Zoom FOV", 30, 10, 60, 5, "");
        static final Opt ZOOM_SMOOTH = Opt.cycle("zoom.smooth", "Smoothness", 1, "Fast", "Normal", "Slow");

        static final Opt NT_SELF = Opt.bool("nt.self", "Own tag in third person", true);
        static final Opt NT_LOGO = Opt.bool("nt.logo", "Show logo", true);

        static final Opt PERF_RD = Opt.num("perf.rd", "Render distance", 6, 2, 12, 1, "");
        static final Opt PERF_SIM = Opt.num("perf.sim", "Simulation distance", 5, 5, 12, 1, "");
        static final Opt PERF_SHADOWS = Opt.bool("perf.shadows", "Entity shadows", false);
        static final Opt PERF_ENT = Opt.num("perf.ent", "Entity distance", 50, 50, 100, 25, "%");
        static final Opt PERF_BLEND = Opt.num("perf.blend", "Biome blend", 0, 0, 4, 1, "");
    }

    // ---------- Modules ----------
    enum Mod {
        FPS("FPS counter", true, O.FPS_COLOR, O.FPS_BG, O.FPS_LABEL),
        CPS("CPS counter", true, O.CPS_COLOR, O.CPS_BG, O.CPS_RIGHT),
        COORDS("Coordinates", false, O.CO_COLOR, O.CO_BG, O.CO_Y),
        MEMORY("Memory usage", false, O.ME_COLOR, O.ME_BG, O.ME_MAX),
        KEYS("Keystrokes", true, O.KEYS_COLOR, O.KEYS_BG, O.KEYS_MOUSE, O.KEYS_SPACE),
        ARMOR("Armor HUD", true, O.ARMOR_LAYOUT, O.ARMOR_LOC, O.ARMOR_DUR),
        EFFECTS("Effect timer", true, O.EFF_HIDE, O.EFF_BG, O.EFF_SIDE),
        SPRINT("Toggle sprint", false, O.SPRINT_ITEM),
        ZOOM("Zoom", true, O.ZOOM_KEY, O.ZOOM_FOV, O.ZOOM_SMOOTH),
        NAMETAG("Name tag + logo", true, O.NT_SELF, O.NT_LOGO),
        FONT("Smooth font", false),
        PERF("Performance mode", false, O.PERF_RD, O.PERF_SIM, O.PERF_SHADOWS, O.PERF_ENT, O.PERF_BLEND);

        final String label;
        final boolean def;
        final Opt[] opts;

        Mod(String label, boolean def, Opt... opts) {
            this.label = label;
            this.def = def;
            this.opts = opts;
        }
    }

    // ---------- Settings (saved to config/lowspec.properties) ----------
    static final class Settings {
        private static final boolean[] ON = new boolean[Mod.values().length];
        private static final HashMap<String, Integer> VALS = new HashMap<>();

        static {
            for (Mod m : Mod.values()) ON[m.ordinal()] = m.def;
        }

        static int prevRender = 12, prevSim = 12, prevBlend = 2;
        static boolean prevShadows = true;
        static double prevEntity = 1.0;

        private static final Path FILE =
                FabricLoader.getInstance().getConfigDir().resolve("lowspec.properties");

        static boolean has(Mod m) {
            return ON[m.ordinal()];
        }

        static void put(Mod m, boolean v) {
            ON[m.ordinal()] = v;
        }

        static int get(Opt o) {
            return VALS.getOrDefault(o.id, o.def);
        }

        static void set(Opt o, int v) {
            VALS.put(o.id, Math.max(o.min, Math.min(o.max, v)));
        }

        static boolean flag(Opt o) {
            return get(o) == 1;
        }

        static int color(Opt o) {
            return O.RGB[Math.floorMod(get(o), O.RGB.length)];
        }

        static void load() {
            try {
                if (!Files.exists(FILE)) return;
                Properties p = new Properties();
                try (var in = Files.newInputStream(FILE)) {
                    p.load(in);
                }
                for (Mod m : Mod.values()) {
                    ON[m.ordinal()] = Boolean.parseBoolean(
                            p.getProperty("mod." + m.name(), Boolean.toString(m.def)));
                    for (Opt o : m.opts) {
                        if (o.type == Opt.KEY) continue;
                        try {
                            set(o, Integer.parseInt(p.getProperty("opt." + o.id, Integer.toString(o.def))));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                prevRender = Integer.parseInt(p.getProperty("prevRender", "12"));
                prevSim = Integer.parseInt(p.getProperty("prevSim", "12"));
                prevBlend = Integer.parseInt(p.getProperty("prevBlend", "2"));
                prevShadows = Boolean.parseBoolean(p.getProperty("prevShadows", "true"));
                prevEntity = Double.parseDouble(p.getProperty("prevEntity", "1.0"));
            } catch (IOException | NumberFormatException ignored) {
            }
        }

        static void save() {
            try {
                Properties p = new Properties();
                for (Mod m : Mod.values()) {
                    p.setProperty("mod." + m.name(), Boolean.toString(ON[m.ordinal()]));
                    for (Opt o : m.opts) {
                        if (o.type == Opt.KEY) continue;
                        p.setProperty("opt." + o.id, Integer.toString(get(o)));
                    }
                }
                p.setProperty("prevRender", Integer.toString(prevRender));
                p.setProperty("prevSim", Integer.toString(prevSim));
                p.setProperty("prevBlend", Integer.toString(prevBlend));
                p.setProperty("prevShadows", Boolean.toString(prevShadows));
                p.setProperty("prevEntity", Double.toString(prevEntity));
                try (var out = Files.newOutputStream(FILE)) {
                    p.store(out, "LowSpec Client");
                }
            } catch (IOException ignored) {
            }
        }
    }

    // ---------- Performance mode ----------
    static final class Perf {
        private static boolean looksLow(int render, int sim) {
            return render <= Settings.get(O.PERF_RD) && sim <= Settings.get(O.PERF_SIM);
        }

        private static void applyLow(Options o) {
            o.renderDistance().set(Settings.get(O.PERF_RD));
            o.simulationDistance().set(Settings.get(O.PERF_SIM));
            o.biomeBlendRadius().set(Settings.get(O.PERF_BLEND));
            o.entityShadows().set(Settings.flag(O.PERF_SHADOWS));
            o.entityDistanceScaling().set(Settings.get(O.PERF_ENT) / 100.0);
        }

        /** Re-apply the low preset after its settings changed (only while the mode is ON). */
        static void refresh() {
            if (!Settings.has(Mod.PERF)) return;
            try {
                Options o = Minecraft.getInstance().options;
                applyLow(o);
                o.save();
            } catch (RuntimeException ignored) {
            }
        }

        static void set(boolean enable) {
            Options o = Minecraft.getInstance().options;
            try {
                if (enable) {
                    int rd = o.renderDistance().get();
                    int sim = o.simulationDistance().get();
                    if (!looksLow(rd, sim)) {
                        Settings.prevRender = rd;
                        Settings.prevSim = sim;
                        Settings.prevBlend = o.biomeBlendRadius().get();
                        Settings.prevShadows = o.entityShadows().get();
                        Settings.prevEntity = o.entityDistanceScaling().get();
                    } else if (looksLow(Settings.prevRender, Settings.prevSim)) {
                        Settings.prevRender = 12;
                        Settings.prevSim = 12;
                        Settings.prevBlend = 2;
                        Settings.prevShadows = true;
                        Settings.prevEntity = 1.0;
                    }
                    applyLow(o);
                } else {
                    int rd = Settings.prevRender, sim = Settings.prevSim;
                    int blend = Settings.prevBlend;
                    boolean shadows = Settings.prevShadows;
                    double entity = Settings.prevEntity;
                    if (looksLow(rd, sim)) {
                        rd = 12;
                        sim = 12;
                        blend = 2;
                        shadows = true;
                        entity = 1.0;
                    }
                    o.renderDistance().set(rd);
                    o.simulationDistance().set(sim);
                    o.biomeBlendRadius().set(blend);
                    o.entityShadows().set(shadows);
                    o.entityDistanceScaling().set(entity);
                }
                o.save();
            } catch (RuntimeException ignored) {
            }
            Settings.put(Mod.PERF, enable);
        }
    }

    // ---------- Drawing helpers ----------
    static final class Ui {
        static final Style STYLE = Style.EMPTY.withFont(
                new FontDescription.Resource(Identifier.fromNamespaceAndPath("lowspec", "glass")));
        private static final HashMap<String, Component> PLAIN = new HashMap<>();
        private static final HashMap<String, Component> STYLED = new HashMap<>();

        static Component t(String s) {
            boolean styled = Settings.has(Mod.FONT);
            HashMap<String, Component> map = styled ? STYLED : PLAIN;
            if (map.size() > 512) map.clear();
            return map.computeIfAbsent(s,
                    k -> styled ? Component.literal(k).withStyle(STYLE) : Component.literal(k));
        }

        static int tw(Font f, String s) {
            return f.width(t(s));
        }

        static void text(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color, boolean shadow) {
            g.text(f, t(s), x, y, color, shadow);
        }

        static boolean in(int mx, int my, int x, int y, int w, int h) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        static int a(int c, float m) {
            int al = Math.round(((c >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, m)));
            return (al << 24) | (c & 0xFFFFFF);
        }

        static int mix(int c1, int c2, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = 0;
            for (int sh = 24; sh >= 0; sh -= 8) {
                int p = (c1 >>> sh) & 0xFF, q = (c2 >>> sh) & 0xFF;
                r |= (p + Math.round((q - p) * t)) << sh;
            }
            return r;
        }

        /** Cheap rounded rectangle (1px corners), 3 fills. Used for the HUD and small shapes. */
        static void rr(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
            g.fill(x + 1, y, x + w - 1, y + 1, c);
            g.fill(x, y + 1, x + w, y + h - 1, c);
            g.fill(x + 1, y + h - 1, x + w - 1, y + h, c);
        }

        /** Smooth rounded rectangle (radius 3 with anti-aliased corner pixels), 13 fills. For menus. */
        static void rrAA(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
            int e0 = a(c, 0.66f);
            int e1 = a(c, 0.60f);
            g.fill(x + 1, y, x + 2, y + 1, e0);
            g.fill(x + 2, y, x + w - 2, y + 1, c);
            g.fill(x + w - 2, y, x + w - 1, y + 1, e0);
            g.fill(x, y + 1, x + 1, y + 2, e1);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, c);
            g.fill(x + w - 1, y + 1, x + w, y + 2, e1);
            g.fill(x, y + 2, x + w, y + h - 2, c);
            g.fill(x, y + h - 2, x + 1, y + h - 1, e1);
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c);
            g.fill(x + w - 1, y + h - 2, x + w, y + h - 1, e1);
            g.fill(x + 1, y + h - 1, x + 2, y + h, e0);
            g.fill(x + 2, y + h - 1, x + w - 2, y + h, c);
            g.fill(x + w - 2, y + h - 1, x + w - 1, y + h, e0);
        }

        /** HUD panel: dark translucent box with a thin light rim on top. */
        static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
            rr(g, x, y, w, h, 0x70101820);
            g.fill(x + 2, y, x + w - 2, y + 1, 0x55FFFFFF);
        }

        /** Big menu panel with soft shadow, rim and gloss. */
        static void bigPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, float m) {
            rr(g, x + 1, y + 3, w, h, a(0x40000000, m));
            rrAA(g, x, y, w, h, a(0xE60D1220, m));
            g.fill(x + 4, y, x + w - 4, y + 1, a(0x66FFFFFF, m));
            g.fill(x + 3, y + 1, x + w - 3, y + 1 + Math.max(2, h / 8), a(0x0CFFFFFF, m));
        }

        static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, float hover, float m) {
            rrAA(g, x, y, w, h, a(mix(0x2EFFFFFF, 0x52FFFFFF, hover), m));
        }

        static void sw(GuiGraphicsExtractor g, int x, int y, float on, float m) {
            rr(g, x, y, 24, 12, a(mix(0x66FFFFFF, 0xFF55FFAA, on), m));
            rr(g, x + 2 + Math.round(on * 12), y + 2, 8, 8, a(0xFFFFFFFF, m));
        }
    }

    // ---------- Logo (drawn in code, 12x12 grid, s = pixels per cell) ----------
    static final class Logo {
        private static final int[][] RIM = {
                {2, 10}, {1, 11}, {0, 12}, {0, 12}, {0, 12}, {0, 12},
                {0, 12}, {0, 12}, {0, 12}, {0, 12}, {1, 11}, {2, 10}};

        static void draw(GuiGraphicsExtractor g, int x, int y, int s, float m) {
            for (int r = 0; r < 12; r++) {
                g.fill(x + RIM[r][0] * s, y + r * s, x + RIM[r][1] * s, y + (r + 1) * s, Ui.a(0xE6DFFBFF, m));
            }
            for (int r = 1; r <= 10; r++) {
                int a = 1, b = 11;
                if (r == 1 || r == 10) { a = 3; b = 9; }
                else if (r == 2 || r == 9) { a = 2; b = 10; }
                int c = Ui.mix(0xFF7A5CFF, 0xFF3DE0C0, (r - 1) / 9f);
                g.fill(x + a * s, y + r * s, x + b * s, y + (r + 1) * s, Ui.a(c, m));
            }
            g.fill(x + 3 * s, y + 2 * s, x + 8 * s, y + 3 * s + s / 2, Ui.a(0x55FFFFFF, m));
            g.fill(x + 4 * s, y + 3 * s, x + 6 * s, y + 9 * s, Ui.a(0xFFFFFFFF, m));
            g.fill(x + 4 * s, y + 7 * s, x + 9 * s, y + 9 * s, Ui.a(0xFFFFFFFF, m));
            g.fill(x + 8 * s, y + 3 * s, x + 10 * s, y + 5 * s, Ui.a(0xFFFFF07A, m));
        }
    }

    // ---------- Right Shift menu (module grid) ----------
    static final class MenuScreen extends Screen {
        private static final Mod[] MODS = Mod.values();
        private static final int COLS = 3;
        private final float[] on = new float[MODS.length];
        private final float[] hover = new float[MODS.length];
        private final float[] dotHover = new float[MODS.length];
        private float doneHover;
        private long openNs, lastNs;
        private int px, py, pw, ph, cw, ch, rowH, rows, doneY;

        MenuScreen() {
            super(Component.literal("LowSpec Client"));
        }

        private int cellX(int i) {
            return px + 12 + (i % COLS) * (cw + 8);
        }

        private int cellY(int i) {
            return py + 52 + (i / COLS) * rowH;
        }

        @Override
        protected void init() {
            rows = (MODS.length + COLS - 1) / COLS;
            pw = Math.min(480, this.width - 16);
            cw = (pw - 24 - (COLS - 1) * 8) / COLS;
            rowH = Math.max(24, Math.min(32, (this.height - 8 - 96) / rows));
            ch = rowH - 4;
            ph = 52 + rows * rowH + 40;
            px = (this.width - pw) / 2;
            py = Math.max(4, (this.height - ph) / 2);
            doneY = py + 52 + rows * rowH + 6;

            // Invisible vanilla buttons: they only handle clicks. All drawing is custom below.
            for (int i = 0; i < MODS.length; i++) {
                Mod mod = MODS[i];
                on[i] = Settings.has(mod) ? 1f : 0f;
                if (mod.opts.length > 0) {
                    this.addRenderableWidget(Button.builder(Component.empty(),
                            b -> Minecraft.getInstance().setScreenAndShow(new ConfigScreen(mod)))
                            .bounds(cellX(i) + cw - 16, cellY(i), 16, ch).build());
                }
            }
            for (int i = 0; i < MODS.length; i++) {
                Mod mod = MODS[i];
                int w = cw - (mod.opts.length > 0 ? 16 : 0);
                this.addRenderableWidget(Button.builder(Component.empty(), b -> {
                    boolean now = !Settings.has(mod);
                    if (mod == Mod.PERF) Perf.set(now);
                    else Settings.put(mod, now);
                    Settings.save();
                }).bounds(cellX(i), cellY(i), w, ch).build());
            }
            this.addRenderableWidget(Button.builder(Component.empty(), b -> this.onClose())
                    .bounds(px + 12, doneY, pw - 24, 24).build());

            openNs = lastNs = System.nanoTime();
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            long now = System.nanoTime();
            float dt = Math.min((now - lastNs) / 1e9f, 0.25f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 16f);
            float p = Math.min(1f, (now - openNs) / 200_000_000f);
            float e = 1f - (1f - p) * (1f - p) * (1f - p);
            int oy = (int) ((1f - e) * 10);
            Font f = this.font;

            g.fill(0, 0, this.width, this.height, Ui.a(0x78000000, e));
            Ui.bigPanel(g, px, py + oy, pw, ph, e);

            Logo.draw(g, px + 14, py + oy + 12, 2, e);
            Ui.text(g, f, "LowSpec Client", px + 14 + 32, py + oy + 14, Ui.a(0xFFFFFFFF, e), false);
            Ui.text(g, f, "Click the dots on a module to configure it", px + 14 + 32, py + oy + 27,
                    Ui.a(0xFF9AA4C0, e), false);

            for (int i = 0; i < MODS.length; i++) {
                Mod mod = MODS[i];
                boolean hasOpts = mod.opts.length > 0;
                int x = cellX(i), y = cellY(i);
                boolean hov = Ui.in(mx, my, x, y, cw - (hasOpts ? 16 : 0), ch);
                boolean dhov = hasOpts && Ui.in(mx, my, x + cw - 16, y, 16, ch);
                hover[i] += ((hov ? 1f : 0f) - hover[i]) * k;
                dotHover[i] += ((dhov ? 1f : 0f) - dotHover[i]) * k;
                on[i] += ((Settings.has(mod) ? 1f : 0f) - on[i]) * k;

                int cy = y + oy;
                Ui.card(g, x, cy, cw, ch, Math.max(hover[i], dotHover[i] * 0.6f), e);
                Ui.text(g, f, mod.label, x + 8, cy + (ch - 9) / 2, Ui.a(0xFFFFFFFF, e), false);
                Ui.sw(g, x + cw - 16 - 28, cy + (ch - 12) / 2, on[i], e);

                if (hasOpts) {
                    int dx = x + cw - 10;
                    int dy = cy + ch / 2;
                    int dc = Ui.a(Ui.mix(0x99FFFFFF, 0xFFFFFFFF, dotHover[i]), e);
                    g.fill(dx, dy - 5, dx + 2, dy - 3, dc);
                    g.fill(dx, dy - 1, dx + 2, dy + 1, dc);
                    g.fill(dx, dy + 3, dx + 2, dy + 5, dc);
                }
            }

            boolean dh = Ui.in(mx, my, px + 12, doneY, pw - 24, 24);
            doneHover += ((dh ? 1f : 0f) - doneHover) * k;
            Ui.rrAA(g, px + 12, doneY + oy, pw - 24, 24, Ui.a(Ui.mix(0x38FFFFFF, 0x70FFFFFF, doneHover), e));
            int dw = Ui.tw(f, "Done");
            Ui.text(g, f, "Done", px + pw / 2 - dw / 2, doneY + oy + 8, Ui.a(0xFFFFFFFF, e), false);
        }
    }

    // ---------- Per-module settings screen (opened with the dots) ----------
    static final class ConfigScreen extends Screen {
        private static final int ROW = 26;
        private final Mod mod;
        private final float[] hover;
        private final float[] val;
        private float backHover;
        private boolean listening;
        private long openNs, lastNs;
        private int px, py, pw, ph, backY;

        ConfigScreen(Mod mod) {
            super(Component.literal(mod.label));
            this.mod = mod;
            this.hover = new float[mod.opts.length];
            this.val = new float[mod.opts.length];
        }

        private int rowY(int i) {
            return py + 50 + i * ROW;
        }

        private void change(Opt o, int dir) {
            int v = Settings.get(o);
            switch (o.type) {
                case Opt.BOOL -> Settings.set(o, 1 - v);
                case Opt.CYCLE -> Settings.set(o, (v + 1) % o.labels.length);
                case Opt.INT -> Settings.set(o, v + dir * o.step);
                default -> { }
            }
            Settings.save();
            if (mod == Mod.PERF) Perf.refresh();
        }

        @Override
        protected void init() {
            int n = mod.opts.length;
            pw = Math.min(380, this.width - 16);
            ph = 50 + n * ROW + 44;
            px = (this.width - pw) / 2;
            py = Math.max(4, (this.height - ph) / 2);
            backY = py + 50 + n * ROW + 8;
            listening = false;

            for (int i = 0; i < n; i++) {
                Opt o = mod.opts[i];
                int ry = rowY(i);
                val[i] = o.type == Opt.BOOL ? Settings.get(o) : 0f;
                if (o.type == Opt.INT) {
                    this.addRenderableWidget(Button.builder(Component.empty(), b -> change(o, -1))
                            .bounds(px + pw - 12 - 88, ry, 22, 22).build());
                    this.addRenderableWidget(Button.builder(Component.empty(), b -> change(o, 1))
                            .bounds(px + pw - 12 - 22, ry, 22, 22).build());
                } else if (o.type == Opt.KEY) {
                    this.addRenderableWidget(Button.builder(Component.empty(), b -> listening = !listening)
                            .bounds(px + 12, ry, pw - 24, 22).build());
                } else {
                    this.addRenderableWidget(Button.builder(Component.empty(), b -> change(o, 1))
                            .bounds(px + 12, ry, pw - 24, 22).build());
                }
            }
            this.addRenderableWidget(Button.builder(Component.empty(), b -> this.onClose())
                    .bounds(px + 12, backY, pw - 24, 24).build());

            openNs = lastNs = System.nanoTime();
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreenAndShow(new MenuScreen());
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (listening) {
                int code = event.key();
                listening = false;
                if (code != InputConstants.KEY_ESCAPE) rebindZoom(code);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            long now = System.nanoTime();
            float dt = Math.min((now - lastNs) / 1e9f, 0.25f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 16f);
            float p = Math.min(1f, (now - openNs) / 200_000_000f);
            float e = 1f - (1f - p) * (1f - p) * (1f - p);
            int oy = (int) ((1f - e) * 10);
            Font f = this.font;
            int white = Ui.a(0xFFFFFFFF, e);
            int accent = Ui.a(0xFF55FFAA, e);

            g.fill(0, 0, this.width, this.height, Ui.a(0x78000000, e));
            Ui.bigPanel(g, px, py + oy, pw, ph, e);

            Logo.draw(g, px + 14, py + oy + 12, 2, e);
            Ui.text(g, f, mod.label, px + 14 + 32, py + oy + 14, white, false);
            Ui.text(g, f, "Settings", px + 14 + 32, py + oy + 27, Ui.a(0xFF9AA4C0, e), false);

            for (int i = 0; i < mod.opts.length; i++) {
                Opt o = mod.opts[i];
                int ry = rowY(i);
                boolean hov = Ui.in(mx, my, px + 12, ry, pw - 24, 22);
                hover[i] += ((hov ? 1f : 0f) - hover[i]) * k;

                int cy = ry + oy;
                Ui.card(g, px + 12, cy, pw - 24, 22, hover[i], e);
                Ui.text(g, f, o.label, px + 22, cy + 7, white, false);

                int right = px + pw - 22;
                if (o.type == Opt.BOOL) {
                    val[i] += (Settings.get(o) - val[i]) * k;
                    Ui.sw(g, right - 24 + 8, cy + 5, val[i], e);
                } else if (o.type == Opt.CYCLE) {
                    String s = "< " + o.labels[Math.floorMod(Settings.get(o), o.labels.length)] + " >";
                    Ui.text(g, f, s, right - Ui.tw(f, s) + 8, cy + 7, accent, false);
                } else if (o.type == Opt.INT) {
                    int minusX = px + pw - 12 - 88;
                    int plusX = px + pw - 12 - 22;
                    Ui.rr(g, minusX, cy + 1, 22, 20, Ui.a(Ui.mix(0x30FFFFFF, 0x66FFFFFF,
                            Ui.in(mx, my, minusX, ry, 22, 22) ? 1f : 0f), e));
                    Ui.rr(g, plusX, cy + 1, 22, 20, Ui.a(Ui.mix(0x30FFFFFF, 0x66FFFFFF,
                            Ui.in(mx, my, plusX, ry, 22, 22) ? 1f : 0f), e));
                    Ui.text(g, f, "-", minusX + 11 - Ui.tw(f, "-") / 2, cy + 7, white, false);
                    Ui.text(g, f, "+", plusX + 11 - Ui.tw(f, "+") / 2, cy + 7, white, false);
                    String s = Settings.get(o) + o.unit;
                    Ui.text(g, f, s, (minusX + 22 + plusX) / 2 - Ui.tw(f, s) / 2, cy + 7, accent, false);
                } else {
                    String s;
                    int c = accent;
                    if (listening) {
                        s = "press a key...";
                        c = Ui.a(0xFFFFE066, e);
                    } else {
                        s = zoomKey.getTranslatedKeyMessage().getString();
                    }
                    Ui.text(g, f, s, right - Ui.tw(f, s) + 8, cy + 7, c, false);
                }
            }

            boolean bh = Ui.in(mx, my, px + 12, backY, pw - 24, 24);
            backHover += ((bh ? 1f : 0f) - backHover) * k;
            Ui.rrAA(g, px + 12, backY + oy, pw - 24, 24, Ui.a(Ui.mix(0x38FFFFFF, 0x70FFFFFF, backHover), e));
            Ui.text(g, f, "Back", px + pw / 2 - Ui.tw(f, "Back") / 2, backY + oy + 8, white, false);
        }
    }

    // ---------- HUD ----------
    static final class Hud {
        private static final int X = 6, Y = 6, KEY = 18, GAP = 2;
        private static final EquipmentSlot[] SLOTS = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

        private static final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
        private static final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
        private static boolean leftWas, rightWas;
        private static final float[] anim = new float[7]; // W A S D LMB RMB SPACE

        private static float deltaSum;
        private static int deltaCount;
        private static int shownFps;
        private static long usedMb, maxMb;
        private static long windowStart = System.nanoTime();
        private static long lastNs = System.nanoTime();

        static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            long now = System.nanoTime();

            updateZoom(mc);

            float dt = Math.min((now - lastNs) / 1e9f, 0.25f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 18f);

            // FPS from the real time between frames; memory refreshed with it
            deltaSum += delta.getRealtimeDeltaTicks();
            deltaCount++;
            if (now - windowStart >= 500_000_000L) {
                if (deltaSum > 0) shownFps = Math.round(20f * deltaCount / deltaSum);
                Runtime rt = Runtime.getRuntime();
                usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
                maxMb = rt.maxMemory() >> 20;
                deltaSum = 0;
                deltaCount = 0;
                windowStart = now;
            }

            boolean left = mc.options.keyAttack.isDown();
            boolean right = mc.options.keyUse.isDown();
            track(leftClicks, left, leftWas, now);
            track(rightClicks, right, rightWas, now);
            leftWas = left;
            rightWas = right;

            int W = mc.getWindow().getGuiScaledWidth();
            int H = mc.getWindow().getGuiScaledHeight();

            // ----- stat lines -----
            int y = Y;
            if (Settings.has(Mod.FPS)) {
                String s = (Settings.flag(O.FPS_LABEL) ? "FPS  " : "") + shownFps;
                y = stat(g, font, y, s, Settings.color(O.FPS_COLOR), Settings.flag(O.FPS_BG));
            }
            if (Settings.has(Mod.CPS)) {
                String s = "CPS  " + leftClicks.size() + (Settings.flag(O.CPS_RIGHT) ? " | " + rightClicks.size() : "");
                y = stat(g, font, y, s, Settings.color(O.CPS_COLOR), Settings.flag(O.CPS_BG));
            }
            if (Settings.has(Mod.COORDS) && mc.player != null) {
                int px = (int) Math.floor(mc.player.getX());
                int py = (int) Math.floor(mc.player.getY());
                int pz = (int) Math.floor(mc.player.getZ());
                String s = Settings.flag(O.CO_Y) ? "XYZ  " + px + "  " + py + "  " + pz : "XZ  " + px + "  " + pz;
                y = stat(g, font, y, s, Settings.color(O.CO_COLOR), Settings.flag(O.CO_BG));
            }
            if (Settings.has(Mod.MEMORY)) {
                String s = "MEM  " + usedMb + (Settings.flag(O.ME_MAX) ? " / " + maxMb : "") + " MB";
                y = stat(g, font, y, s, Settings.color(O.ME_COLOR), Settings.flag(O.ME_BG));
            }

            if (Settings.has(Mod.KEYS)) keys(g, font, mc, y, left, right, k);

            if (mc.player == null) return;
            if (Settings.has(Mod.ARMOR)) armor(g, font, mc, W, H);
            if (Settings.has(Mod.EFFECTS)) effects(g, font, mc, W);
        }

        private static int stat(GuiGraphicsExtractor g, Font font, int y, String s, int color, boolean bg) {
            if (bg) {
                int w = Ui.tw(font, s) + 14;
                Ui.panel(g, X, y, w, 15);
                Ui.text(g, font, s, X + 7, y + 3, color, false);
                return y + 18;
            }
            Ui.text(g, font, s, X + 2, y + 3, color, true);
            return y + 13;
        }

        // ----- keystrokes -----
        private static void keys(GuiGraphicsExtractor g, Font font, Minecraft mc, int y,
                                 boolean left, boolean right, float k) {
            boolean bg = Settings.flag(O.KEYS_BG);
            int hl = Settings.color(O.KEYS_COLOR);
            boolean[] d = {
                    mc.options.keyUp.isDown(), mc.options.keyLeft.isDown(),
                    mc.options.keyDown.isDown(), mc.options.keyRight.isDown(),
                    left, right, mc.options.keyJump.isDown()};
            for (int i = 0; i < anim.length; i++) {
                anim[i] += ((d[i] ? 1f : 0f) - anim[i]) * k;
            }
            int row2 = y + KEY + GAP;
            key(g, font, "W", X + KEY + GAP, y, KEY, anim[0], bg, hl);
            key(g, font, "A", X, row2, KEY, anim[1], bg, hl);
            key(g, font, "S", X + KEY + GAP, row2, KEY, anim[2], bg, hl);
            key(g, font, "D", X + 2 * (KEY + GAP), row2, KEY, anim[3], bg, hl);

            int total = 3 * KEY + 2 * GAP;
            int next = row2 + KEY + GAP;
            if (Settings.flag(O.KEYS_MOUSE)) {
                int half = (total - GAP) / 2;
                key(g, font, "LMB", X, next, half, anim[4], bg, hl);
                key(g, font, "RMB", X + half + GAP, next, total - half - GAP, anim[5], bg, hl);
                next += KEY + GAP;
            }
            if (Settings.flag(O.KEYS_SPACE)) {
                key(g, font, "SPACE", X, next, total, anim[6], bg, hl);
            }
        }

        private static void key(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w,
                                float an, boolean bg, int hl) {
            if (bg) Ui.rr(g, x, y, w, KEY, 0x70101820);
            if (an > 0.02f) Ui.rr(g, x, y, w, KEY, Ui.a(hl, 0.8f * an));
            int col = Ui.mix(0xFFFFFFFF, 0xFF101820, an);
            Ui.text(g, font, label, x + (w - Ui.tw(font, label)) / 2, y + (KEY - 9) / 2, col, !bg && an < 0.5f);
        }

        // ----- armor HUD -----
        private static void armor(GuiGraphicsExtractor g, Font font, Minecraft mc, int W, int H) {
            boolean horiz = Settings.get(O.ARMOR_LAYOUT) == 1;
            int loc = Settings.get(O.ARMOR_LOC);
            boolean dur = Settings.flag(O.ARMOR_DUR);

            int bw = horiz ? 4 * 28 : (dur ? 54 : 16);
            int bh = horiz ? (dur ? 27 : 16) : 4 * 18 - 2;
            boolean right = loc == 2 || loc == 4 || loc == 7;
            boolean centerX = loc == 1 || loc == 6;
            int x = right ? W - bw - 6 : centerX ? W / 2 - bw / 2 : 6;
            int y;
            if (loc <= 2) y = 6;
            else if (loc <= 4) y = H / 2 - bh / 2;
            else if (loc == 6) y = H - bh - 62;
            else y = H - bh - 6;

            for (int i = 0; i < SLOTS.length; i++) {
                ItemStack s = mc.player.getItemBySlot(SLOTS[i]);
                if (s.isEmpty()) continue;
                if (horiz) {
                    int ix = x + i * 28 + 6;
                    g.item(s, ix, y);
                    if (dur && s.isDamageableItem()) {
                        String t = pct(s) + "%";
                        Ui.text(g, font, t, ix + 8 - Ui.tw(font, t) / 2, y + 17, pctColor(s), true);
                    }
                } else {
                    int iy = y + i * 18;
                    int ix = right ? x + bw - 16 : x;
                    g.item(s, ix, iy);
                    if (dur && s.isDamageableItem()) {
                        String t = pct(s) + "%";
                        int tx = right ? ix - 3 - Ui.tw(font, t) : ix + 19;
                        Ui.text(g, font, t, tx, iy + 4, pctColor(s), true);
                    }
                }
            }
        }

        private static int pct(ItemStack s) {
            int max = s.getMaxDamage();
            return max <= 0 ? 100 : (max - s.getDamageValue()) * 100 / max;
        }

        private static int pctColor(ItemStack s) {
            int p = pct(s);
            return p > 60 ? 0xFF55FF55 : p > 35 ? 0xFFFFFF55 : 0xFFFF5555;
        }

        // ----- effect timer -----
        private static void effects(GuiGraphicsExtractor g, Font font, Minecraft mc, int W) {
            boolean bg = Settings.flag(O.EFF_BG);
            boolean leftSide = Settings.get(O.EFF_SIDE) == 1;
            int ey = Y;
            for (MobEffectInstance e : mc.player.getActiveEffects()) {
                String name = e.getEffect().value().getDisplayName().getString();
                if (e.getAmplifier() > 0) name += " " + (e.getAmplifier() + 1);
                boolean inf = e.isInfiniteDuration();
                String line = name + "  " + (inf ? "inf" : fmt(e.getDuration()));
                int col = (!inf && e.getDuration() < 200) ? 0xFFFF6B6B : 0xFFFFFFFF;
                int w = Ui.tw(font, line) + 12;
                int ex = leftSide ? X : W - w - X;
                if (bg) {
                    Ui.panel(g, ex, ey, w, 15);
                    Ui.text(g, font, line, ex + 6, ey + 3, col, false);
                    ey += 18;
                } else {
                    Ui.text(g, font, line, ex + 2, ey + 3, col, true);
                    ey += 12;
                }
            }
        }

        private static String fmt(int ticks) {
            int s = Math.max(0, ticks) / 20;
            int sec = s % 60;
            return (s / 60) + ":" + (sec < 10 ? "0" : "") + sec;
        }

        private static void track(ArrayDeque<Long> clicks, boolean down, boolean was, long now) {
            if (down && !was) clicks.addLast(now);
            long cutoff = now - 1_000_000_000L;
            while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) clicks.pollFirst();
        }
    }

    // ---------- Watermarks (always on, no background box) ----------
    static final class Marks {
        private static final String NAME = "LowSpec Client";

        static void title(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
            Font font = Minecraft.getInstance().font;
            int tw = Ui.tw(font, NAME);
            int total = 24 + 6 + tw;
            int rx = screen.width / 2 - total / 2;
            int ry = 84;
            Logo.draw(g, rx, ry, 2, 1f);
            Ui.text(g, font, NAME, rx + 30, ry + 8, 0xFFFFFFFF, true);
        }

        static void corner(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
            Font font = Minecraft.getInstance().font;
            int tw = Ui.tw(font, NAME);
            int w = 12 + 5 + tw;
            int x = screen.width - w - 8;
            int y = screen.height - 12 - 6;
            Logo.draw(g, x, y, 1, 1f);
            Ui.text(g, font, NAME, x + 17, y + 2, 0xFFFFFFFF, true);
        }
    }
}
