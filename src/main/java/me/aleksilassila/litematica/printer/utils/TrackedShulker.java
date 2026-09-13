package me.aleksilassila.litematica.printer.utils;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** Identity and latest content snapshot of a shulker used for returning materials. */
final class TrackedShulker {
    private final Item boxItem;
    private List<ItemStack> contents;
    private int lastKnownSlot = -1;

    TrackedShulker(Item boxItem, List<ItemStack> contents) {
        this.boxItem = boxItem;
        this.contents = contents;
    }

    Item boxItem() {
        return boxItem;
    }

    List<ItemStack> contents() {
        return contents;
    }

    int lastKnownSlot() {
        return lastKnownSlot;
    }

    void setLastKnownSlot(int slot) {
        this.lastKnownSlot = slot;
    }

    void updateContents(List<ItemStack> contents) {
        this.contents = contents;
    }
}
