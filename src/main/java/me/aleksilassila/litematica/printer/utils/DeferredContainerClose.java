package me.aleksilassila.litematica.printer.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Wait for pickup completion before closing the shulker screen. */
final class DeferredContainerClose {
    private static final int MAX_RETRIES = 10;
    private int ticks;
    private int retries;

    void schedule() {
        ticks = 2;
        retries = 0;
    }

    void cancel() {
        ticks = 0;
        retries = 0;
    }

    void tick(Minecraft mc) {
        if (ticks > 0 && --ticks == 0) {
            LocalPlayer player = mc.player;
            if (player == null || player.containerMenu.equals(player.inventoryMenu)) {
                retries = 0;
            } else if (player.containerMenu.getCarried().isEmpty() || ++retries >= MAX_RETRIES) {
                player.closeContainer();
                retries = 0;
            } else {
                ticks = 1;
            }
        }
    }
}
