package com.lowspec.client;

import java.util.ArrayDeque;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;

public class LowSpecClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
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

    static final class Hud {
        private static final int X = 4, Y = 4, KEY = 20, GAP = 2;
        private static final int KEY_IDLE = 0x66000000, KEY_DOWN = 0xAAFFFFFF;

        private static int frames;
        private static long windowStart = System.nanoTime();
        private static String fpsText = "FPS: --";

        private static final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
        private static final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
        private static boolean leftWas, rightWas;

        static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            long now = System.nanoTime();

            frames++;
            if (now - windowStart >= 1_000_000_000L) {
                fpsText = "FPS: " + frames;
                frames = 0;
                windowStart = now;
            }

            boolean left = mc.options.keyAttack.isDown();
            boolean right = mc.options.keyUse.isDown();
            track(leftClicks, left, leftWas, now);
            track(rightClicks, right, rightWas, now);
            leftWas = left;
            rightWas = right;

            int y = Y;
            g.text(font, fpsText, X, y, 0xFFFFFFFF, true);
            y += 10;
            g.text(font, "CPS: " + leftClicks.size() + " | " + rightClicks.size(), X, y, 0xFFFFFFFF, true);
            y += 14;

            int row2 = y + KEY + GAP;
            key(g, font, "W", X + KEY + GAP, y, KEY, mc.options.keyUp);
            key(g, font, "A", X, row2, KEY, mc.options.keyLeft);
            key(g, font, "S", X + KEY + GAP, row2, KEY, mc.options.keyDown);
            key(g, font, "D", X + 2 * (KEY + GAP), row2, KEY, mc.options.keyRight);

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

        private static void key(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, KeyMapping map) {
            key(g, font, label, x, y, w, map.isDown());
        }

        private static void key(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, boolean down) {
            g.fill(x, y, x + w, y + KEY, down ? KEY_DOWN : KEY_IDLE);
            g.text(font, label, x + (w - font.width(label)) / 2, y + (KEY - 8) / 2,
                    down ? 0xFF000000 : 0xFFFFFFFF, false);
        }
    }

    static final class Title {
        static void extract(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
            Font font = Minecraft.getInstance().font;
            int w = screen.width, h = screen.height;
            g.fill(0, h - 24, w, h, 0x88000000);
            g.fill(0, h - 24, w, h - 23, 0xFF55FFAA);
            g.text(font, "LowSpec Client", 6, h - 18, 0xFF55FFAA, true);
            g.text(font, "light on resources", 6 + font.width("LowSpec Client") + 6, h - 18, 0xFFAAAAAA, false);
        }
    }
}
