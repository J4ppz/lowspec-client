package com.lowspec.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
import net.minecraft.network.chat.Component;
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
                ScreenEvents.afterExtract(screen).register(Title::extract);
            }
        });
    }

    // ---------- Settings (saved to config/lowspec.properties) ----------
    static final class Settings {
        static boolean fps = true, cps = true, keys = true, title = true;
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
                title = Boolean.parseBoolean(p.getProperty("title", "true"));
            } catch (IOException ignored) {
            }
        }

        static void save() {
            try {
                Properties p = new Properties();
                p.setProperty("fps", Boolean.toString(fps));
                p.setProperty("cps", Boolean.toString(cps));
                p.setProperty("keys", Boolean.toString(keys));
                p.setProperty("title", Boolean.toString(title));
                try (var out = Files.newOutputStream(FILE)) {
                    p.store(out, "LowSpec Client");
                }
            } catch (IOException ignored) {
            }
        }
    }

    // ---------- Right Shift menu ----------
    static final class MenuScreen extends Screen {
        MenuScreen() {
            super(Component.literal("LowSpec Client"));
        }

        @Override
        protected void init() {
            int x = this.width / 2 - 100;
            int y = this.height / 2 - 55;
            toggle(x, y, "FPS counter", () -> Settings.fps, v -> Settings.fps = v);
            toggle(x, y + 24, "CPS counter", () -> Settings.cps, v -> Settings.cps = v);
            toggle(x, y + 48, "Keystrokes", () -> Settings.keys, v -> Settings.keys = v);
            toggle(x, y + 72, "Title screen branding", () -> Settings.title, v -> Settings.title = v);
            this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                    .bounds(x, y + 102, 200, 20).build());
        }

        private void toggle(int x, int y, String name, BooleanSupplier get, Consumer<Boolean> set) {
            this.addRenderableWidget(Button.builder(label(name, get.getAsBoolean()), btn -> {
                set.accept(!get.getAsBoolean());
                Settings.save();
                btn.setMessage(label(name, get.getAsBoolean()));
            }).bounds(x, y, 200, 20).build());
        }

        private static Component label(String name, boolean on) {
            return Component.literal(name + ": " + (on ? "ON" : "OFF"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            String t = "LowSpec Client";
            g.text(this.font, t, this.width / 2 - this.font.width(t) / 2, this.height / 2 - 80, 0xFF55FFAA, true);
        }
    }

    // ---------- HUD ----------
    static final class Hud {
        private static final int X = 4, Y = 4, KEY = 20, GAP = 2;
        private static final int KEY_IDLE = 0x66000000, KEY_DOWN = 0xAAFFFFFF;

        private static final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
        private static final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
        private static boolean leftWas, rightWas;

        private static float deltaSum;
        private static int deltaCount;
        private static int shownFps;
        private static long fpsWindowStart = System.nanoTime();

        static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            long now = System.nanoTime();

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

            int y = Y;
            if (Settings.fps) {
                g.text(font, "FPS: " + shownFps, X, y, 0xFFFFFFFF, true);
                y += 10;
            }
            if (Settings.cps) {
                g.text(font, "CPS: " + leftClicks.size() + " | " + rightClicks.size(), X, y, 0xFFFFFFFF, true);
                y += 10;
            }
            if (!Settings.keys) return;
            y += 4;

            int row2 = y + KEY + GAP;
            key(g, font, "W", X + KEY + GAP, y, KEY, mc.options.keyUp.isDown());
            key(g, font, "A", X, row2, KEY, mc.options.keyLeft.isDown());
            key(g, font, "S", X + KEY + GAP, row2, KEY, mc.options.keyDown.isDown());
            key(g, font, "D", X + 2 * (KEY + GAP), row2, KEY, mc.options.keyRight.isDown());

            int row3 = row2 + KEY + GAP;
            int half = (3 * KEY + GAP) / 2;
            key(g, font, "LMB", X, row3, half, left);
            key(g, font, "RMB", X + half + GAP, row3, half, right);

            int row4 = row3 + KEY + GAP;
            key(g, font, "SPACE", X, row4, 3 * KEY + 2 * GAP, mc.options.keyJump.isDown());
        }

        private static void track(ArrayDeque<Long> clicks, boolean down, boolean was, long now) {
            if (down && !was) clicks.addLast(now);
            long cutoff = now - 1_000_000_000L;
            while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) clicks.pollFirst();
        }

        private static void key(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, boolean down) {
            g.fill(x, y, x + w, y + KEY, down ? KEY_DOWN : KEY_IDLE);
            g.text(font, label, x + (w - font.width(label)) / 2, y + (KEY - 8) / 2,
                    down ? 0xFF000000 : 0xFFFFFFFF, false);
        }
    }

    // ---------- Title screen ----------
    static final class Title {
        static void extract(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
            if (!Settings.title) return;
            Font font = Minecraft.getInstance().font;
            int w = screen.width, h = screen.height;
            g.fill(0, h - 24, w, h, 0x88000000);
            g.fill(0, h - 24, w, h - 23, 0xFF55FFAA);
            g.text(font, "LowSpec Client", 6, h - 18, 0xFF55FFAA, true);
            g.text(font, "light on resources", 6 + font.width("LowSpec Client") + 6, h - 18, 0xFFAAAAAA, false);
        }
    }
}
