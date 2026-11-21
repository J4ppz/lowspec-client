package com.lowspec.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

public class LowSpecClient implements ClientModInitializer {

    private static KeyMapping menuKey;

    @Override
    public void onInitializeClient() {
        Settings.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("lowspec", "main"));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.lowspec.menu",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_RSHIFT,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.consumeClick()) {
                client.setScreenAndShow(new MenuScreen());
            }
        });

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

    // ---------- Settings (saved to config/lowspec.properties) ----------
    static final class Settings {
        static boolean fps = true, cps = true, keys = true, perf = false;

        static int prevRender = 12, prevSim = 12, prevBlend = 2;
        static boolean prevShadows = true;
        static double prevEntity = 1.0;

        private static final Path FILE =
                FabricLoader.getInstance().getConfigDir().resolve("lowspec.properties");

        static void load() {
            try {
                if (!Files.exists(FILE)) return;
                Properties p = new Properties();
                try (var in = Files.newInputStream(FILE)) {
                    p.load(in);
                }
                fps = Boolean.parseBoolean(p.getProperty("fps", "true"));
                cps = Boolean.parseBoolean(p.getProperty("cps", "true"));
                keys = Boolean.parseBoolean(p.getProperty("keys", "true"));
                perf = Boolean.parseBoolean(p.getProperty("perf", "false"));
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
                p.setProperty("fps", Boolean.toString(fps));
                p.setProperty("cps", Boolean.toString(cps));
                p.setProperty("keys", Boolean.toString(keys));
                p.setProperty("perf", Boolean.toString(perf));
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
        static void set(boolean on) {
            var o = Minecraft.getInstance().options;
            if (on && !Settings.perf) {
                Settings.prevRender = o.renderDistance().get();
                Settings.prevSim = o.simulationDistance().get();
                Settings.prevBlend = o.biomeBlendRadius().get();
                Settings.prevShadows = o.entityShadows().get();
                Settings.prevEntity = o.entityDistanceScaling().get();

                o.renderDistance().set(6);
                o.simulationDistance().set(5);
                o.biomeBlendRadius().set(0);
                o.entityShadows().set(false);
                o.entityDistanceScaling().set(0.5);
                o.save();
            } else if (!on && Settings.perf) {
                o.renderDistance().set(Settings.prevRender);
                o.simulationDistance().set(Settings.prevSim);
                o.biomeBlendRadius().set(Settings.prevBlend);
                o.entityShadows().set(Settings.prevShadows);
                o.entityDistanceScaling().set(Settings.prevEntity);
                o.save();
            }
            Settings.perf = on;
        }
    }

    // ---------- Glass drawing helpers ----------
    static final class Ui {
        static final Style STYLE = Style.EMPTY.withFont(
                new FontDescription.Resource(Identifier.fromNamespaceAndPath("lowspec", "glass")));
        private static final HashMap<String, Component> CACHE = new HashMap<>();

        static Component t(String s) {
            if (CACHE.size() > 512) CACHE.clear();
            return CACHE.computeIfAbsent(s, k -> Component.literal(k).withStyle(STYLE));
        }

        static int tw(Font f, String s) {
            return f.width(t(s));
        }

        static void text(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
            g.text(f, t(s), x, y, color, false);
        }

        /** Multiply a color's alpha by m (0..1). */
        static int a(int c, float m) {
            int al = Math.round(((c >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, m)));
            return (al << 24) | (c & 0xFFFFFF);
        }

        /** Blend two ARGB colors. */
        static int mix(int c1, int c2, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = 0;
            for (int sh = 24; sh >= 0; sh -= 8) {
                int p = (c1 >>> sh) & 0xFF, q = (c2 >>> sh) & 0xFF;
                r |= (p + Math.round((q - p) * t)) << sh;
            }
            return r;
        }

        /** Filled rectangle with rounded corners (radius ~3). */
        static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
            g.fill(x + 3, y, x + w - 3, y + 1, c);
            g.fill(x + 1, y + 1, x + w - 1, y + 3, c);
            g.fill(x, y + 3, x + w, y + h - 3, c);
            g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, c);
            g.fill(x + 3, y + h - 1, x + w - 3, y + h, c);
        }

        /** Frosted glass panel: shadow, tint, bright rim, soft top gloss. */
        static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h, float m) {
            round(g, x + 1, y + 2, w, h, a(0x30000000, m));
            round(g, x, y, w, h, a(0x5A0D1B2A, m));
            g.fill(x + 3, y, x + w - 3, y + 1, a(0x99FFFFFF, m));
            g.fill(x + 3, y + h - 1, x + w - 3, y + h, a(0x33FFFFFF, m));
            g.fill(x, y + 3, x + 1, y + h - 3, a(0x55FFFFFF, m));
            g.fill(x + w - 1, y + 3, x + w, y + h - 3, a(0x55FFFFFF, m));
            g.fill(x + 2, y + 1, x + w - 2, y + 1 + Math.max(2, h / 3), a(0x14FFFFFF, m));
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

    // ---------- Right Shift menu (glass) ----------
    static final class MenuScreen extends Screen {
        private static final class Toggle {
            final String name;
            final BooleanSupplier get;
            final Consumer<Boolean> set;
            float on, hover;
            int y;

            Toggle(String name, BooleanSupplier get, Consumer<Boolean> set) {
                this.name = name;
                this.get = get;
                this.set = set;
            }
        }

        private final List<Toggle> toggles = new ArrayList<>();
        private float doneHover;
        private long openNs, lastNs;
        private int px, py, pw, ph, doneY;

        MenuScreen() {
            super(Component.literal("LowSpec Client"));
        }

        @Override
        protected void init() {
            toggles.clear();
            toggles.add(new Toggle("FPS counter", () -> Settings.fps, v -> Settings.fps = v));
            toggles.add(new Toggle("CPS counter", () -> Settings.cps, v -> Settings.cps = v));
            toggles.add(new Toggle("Keystrokes", () -> Settings.keys, v -> Settings.keys = v));
            toggles.add(new Toggle("Performance mode", () -> Settings.perf, Perf::set));
            for (Toggle t : toggles) t.on = t.get.getAsBoolean() ? 1f : 0f;

            pw = 230;
            ph = 40 + toggles.size() * 26 + 34;
            px = (this.width - pw) / 2;
            py = (this.height - ph) / 2;

            // Invisible vanilla buttons: they only provide the click handling.
            // All drawing is done in extractRenderState below.
            for (int i = 0; i < toggles.size(); i++) {
                Toggle t = toggles.get(i);
                t.y = py + 40 + i * 26;
                this.addRenderableWidget(Button.builder(Component.empty(), b -> {
                    t.set.accept(!t.get.getAsBoolean());
                    Settings.save();
                }).bounds(px + 12, t.y, pw - 24, 22).build());
            }
            doneY = py + 40 + toggles.size() * 26 + 4;
            this.addRenderableWidget(Button.builder(Component.empty(), b -> this.onClose())
                    .bounds(px + 12, doneY, pw - 24, 22).build());

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

            g.fill(0, 0, this.width, this.height, Ui.a(0x70000000, e));
            Ui.glass(g, px, py + oy, pw, ph, e);

            Logo.draw(g, px + 12, py + oy + 8, 2, e);
            Ui.text(g, f, "LowSpec Client", px + 12 + 30, py + oy + 16, Ui.a(0xFFFFFFFF, e));

            for (Toggle t : toggles) {
                boolean hov = mx >= px + 12 && mx < px + pw - 12 && my >= t.y && my < t.y + 22;
                t.hover += ((hov ? 1f : 0f) - t.hover) * k;
                t.on += ((t.get.getAsBoolean() ? 1f : 0f) - t.on) * k;

                int ry = t.y + oy;
                Ui.round(g, px + 12, ry, pw - 24, 22, Ui.a(Ui.mix(0x30FFFFFF, 0x55FFFFFF, t.hover), e));
                Ui.text(g, f, t.name, px + 22, ry + 7, Ui.a(0xFFFFFFFF, e));

                int sx = px + pw - 12 - 10 - 28;
                int sy = ry + 5;
                Ui.round(g, sx, sy, 28, 12, Ui.a(Ui.mix(0x66FFFFFF, 0xFF55FFAA, t.on), e));
                Ui.round(g, sx + 2 + (int) (t.on * 16), sy + 2, 8, 8, Ui.a(0xFFFFFFFF, e));
            }

            boolean dh = mx >= px + 12 && mx < px + pw - 12 && my >= doneY && my < doneY + 22;
            doneHover += ((dh ? 1f : 0f) - doneHover) * k;
            Ui.round(g, px + 12, doneY + oy, pw - 24, 22, Ui.a(Ui.mix(0x44FFFFFF, 0x99FFFFFF, doneHover), e));
            int dw = Ui.tw(f, "Done");
            Ui.text(g, f, "Done", px + pw / 2 - dw / 2, doneY + oy + 7, Ui.a(0xFFFFFFFF, e));
        }
    }

    // ---------- HUD (glass) ----------
    static final class Hud {
        private static final int X = 6, Y = 6, KEY = 20, GAP = 3;

        private static final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
        private static final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
        private static boolean leftWas, rightWas;

        private static final float[] anim = new float[7]; // W A S D LMB RMB SPACE

        private static float deltaSum;
        private static int deltaCount;
        private static int shownFps;
        private static long fpsWindowStart = System.nanoTime();
        private static long lastNs = System.nanoTime();

        static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            long now = System.nanoTime();

            float dt = Math.min((now - lastNs) / 1e9f, 0.25f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 18f);

            // FPS from the real time between frames (20 ticks = 1 second)
            deltaSum += delta.getRealtimeDeltaTicks();
            deltaCount++;
            if (now - fpsWindowStart >= 500_000_000L) {
                if (deltaSum > 0) shownFps = Math.round(20f * deltaCount / deltaSum);
                deltaSum = 0;
                deltaCount = 0;
                fpsWindowStart = now;
            }

            boolean left = mc.options.keyAttack.isDown();
            boolean right = mc.options.keyUse.isDown();
            track(leftClicks, left, leftWas, now);
            track(rightClicks, right, rightWas, now);
            leftWas = left;
            rightWas = right;

            boolean[] d = {
                    mc.options.keyUp.isDown(), mc.options.keyLeft.isDown(),
                    mc.options.keyDown.isDown(), mc.options.keyRight.isDown(),
                    left, right, mc.options.keyJump.isDown()};
            for (int i = 0; i < anim.length; i++) {
                anim[i] += ((d[i] ? 1f : 0f) - anim[i]) * k;
            }

            int y = Y;
            if (Settings.fps || Settings.cps) {
                String l1 = "FPS  " + shownFps;
                String l2 = "CPS  " + leftClicks.size() + " | " + rightClicks.size();
                int lines = (Settings.fps ? 1 : 0) + (Settings.cps ? 1 : 0);
                int w = Math.max(Settings.fps ? Ui.tw(font, l1) : 0, Settings.cps ? Ui.tw(font, l2) : 0) + 16;
                int h = lines * 12 + 6;
                Ui.glass(g, X, y, w, h, 1f);
                int ty = y + 5;
                if (Settings.fps) {
                    Ui.text(g, font, l1, X + 8, ty, 0xFFFFFFFF);
                    ty += 12;
                }
                if (Settings.cps) {
                    Ui.text(g, font, l2, X + 8, ty, 0xFFFFFFFF);
                }
                y += h + 5;
            }
            if (!Settings.keys) return;

            int row2 = y + KEY + GAP;
            key(g, font, "W", X + KEY + GAP, y, KEY, anim[0]);
            key(g, font, "A", X, row2, KEY, anim[1]);
            key(g, font, "S", X + KEY + GAP, row2, KEY, anim[2]);
            key(g, font, "D", X + 2 * (KEY + GAP), row2, KEY, anim[3]);

            int row3 = row2 + KEY + GAP;
            int total = 3 * KEY + 2 * GAP;
            int half = (total - GAP) / 2;
            key(g, font, "LMB", X, row3, half, anim[4]);
            key(g, font, "RMB", X + half + GAP, row3, total - half - GAP, anim[5]);

            int row4 = row3 + KEY + GAP;
            key(g, font, "SPACE", X, row4, total, anim[6]);
        }

        private static void track(ArrayDeque<Long> clicks, boolean down, boolean was, long now) {
            if (down && !was) clicks.addLast(now);
            long cutoff = now - 1_000_000_000L;
            while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) clicks.pollFirst();
        }

        private static void key(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, float an) {
            Ui.round(g, x, y, w, KEY, 0x4D0D1B2A);
            if (an > 0.01f) Ui.round(g, x, y, w, KEY, Ui.a(0xD0FFFFFF, an));
            g.fill(x + 3, y, x + w - 3, y + 1, 0x77FFFFFF);
            int col = Ui.mix(0xFFFFFFFF, 0xFF0D1B2A, an);
            Ui.text(g, font, label, x + (w - Ui.tw(font, label)) / 2, y + (KEY - 9) / 2, col);
        }
    }

    // ---------- Watermarks (always on) ----------
    static final class Marks {
        private static final String NAME = "LowSpec Client";

        // centered under the "JAVA EDITION" part of the logo
        static void title(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
            Font font = Minecraft.getInstance().font;
            int tw = Ui.tw(font, NAME);
            int total = 24 + 6 + tw;
            int rx = screen.width / 2 - total / 2;
            int ry = 84;
            Ui.glass(g, rx - 8, ry - 4, total + 16, 32, 1f);
            Logo.draw(g, rx, ry, 2, 1f);
            Ui.text(g, font, NAME, rx + 30, ry + 8, 0xFFFFFFFF);
        }

        // bottom-right corner of the inventory screens
        static void corner(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
            Font font = Minecraft.getInstance().font;
            int tw = Ui.tw(font, NAME);
            int w = 6 + 12 + 5 + tw + 8;
            int h = 20;
            int x = screen.width - w - 4;
            int y = screen.height - h - 4;
            Ui.glass(g, x, y, w, h, 1f);
            Logo.draw(g, x + 6, y + 4, 1, 1f);
            Ui.text(g, font, NAME, x + 6 + 12 + 5, y + 6, 0xFFFFFFFF);
        }
    }
}
