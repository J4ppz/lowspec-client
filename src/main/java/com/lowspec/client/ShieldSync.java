package com.lowspec.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Turns the "full shield banners" resource pack on or off the moment the Shield banners module
 * is toggled. Everything goes through reflection, so a renamed method can't break the build:
 * if something doesn't match, it simply stops trying.
 */
public class ShieldSync implements ClientModInitializer {
    private static final String PACK = "full_shield_banners";
    private static int state = -1; // -1 unknown, 0 off, 1 on
    private static boolean gone;
    private static int wait;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(ShieldSync::tick);
    }

    private static void tick(Minecraft mc) {
        if (gone) return;
        if (++wait < 40) return; // let the game finish starting first
        boolean want = LowSpecClient.Settings.has(LowSpecClient.Mod.SHIELD);
        if (state == (want ? 1 : 0)) return;
        apply(mc, want);
    }

    @SuppressWarnings("unchecked")
    private static void apply(Minecraft mc, boolean want) {
        try {
            Object repo = Minecraft.class.getMethod("getResourcePackRepository").invoke(mc);
            Class<?> rc = repo.getClass();
            Collection<String> avail = (Collection<String>) rc.getMethod("getAvailableIds").invoke(repo);
            String id = null;
            for (String s : avail) {
                if (s.contains(PACK)) {
                    id = s;
                    break;
                }
            }
            if (id == null) {
                gone = true;
                return;
            }
            List<String> sel = new ArrayList<>((Collection<String>) rc.getMethod("getSelectedIds").invoke(repo));
            boolean has = sel.contains(id);
            if (has != want) {
                if (want) sel.add(id);
                else sel.remove(id);
                rc.getMethod("setSelected", Collection.class).invoke(repo, sel);
                Class<?> repoType = Class.forName("net.minecraft.server.packs.repository.PackRepository");
                mc.options.getClass().getMethod("updateResourcePacks", repoType).invoke(mc.options, repo);
                Minecraft.class.getMethod("reloadResourcePacks").invoke(mc);
            }
            state = want ? 1 : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            gone = true;
        }
    }
}
