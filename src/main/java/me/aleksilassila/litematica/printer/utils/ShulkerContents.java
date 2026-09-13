package me.aleksilassila.litematica.printer.utils;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Copies and unordered multiset matching for tracked shulker contents. */
final class ShulkerContents {
    private ShulkerContents() {}

    static List<ItemStack> getShulkerContents(ItemStack stack) {
        return copyNonEmptyStacks(fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1));
    }

    static List<ItemStack> getContainerContents(AbstractContainerMenu container, int ownSlots) {
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < ownSlots; i++) {
            ItemStack stack = container.slots.get(i).getItem();
            if (!stack.isEmpty()) contents.add(stack.copy());
        }
        return contents;
    }

    static List<ItemStack> copyNonEmptyStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) copies.add(stack.copy());
        }
        return copies;
    }

    static boolean sameContents(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;

        boolean[] matched = new boolean[second.size()];
        for (ItemStack firstStack : first) {
            boolean found = false;
            for (int i = 0; i < second.size(); i++) {
                if (!matched[i] && fi.dy.masa.malilib.util.InventoryUtils
                        .areStacksEqual(firstStack, second.get(i))) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

}
