package me.aleksilassila.litematica.printer.utils;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShulkerContentsTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void contentMatchingIgnoresOrderAndKeepsDuplicateMultiplicity() {
        var stone = new ItemStack(Items.STONE, 2);
        var dirt = new ItemStack(Items.DIRT, 3);
        assertTrue(ShulkerContents.sameContents(List.of(stone, dirt), List.of(dirt.copy(), stone.copy())));
        assertFalse(ShulkerContents.sameContents(List.of(stone, stone), List.of(stone, dirt)));
        assertFalse(ShulkerContents.sameContents(List.of(stone), List.of(stone, stone)));
        // Existing MaLiLib comparison ignores count; preserve that behavior.
        assertTrue(ShulkerContents.sameContents(List.of(stone), List.of(new ItemStack(Items.STONE, 1))));
    }

    @Test void snapshotsDiscardEmptySlotsAndDoNotAliasSourceStacks() {
        var source = new ItemStack(Items.STONE, 4);
        var copy = ShulkerContents.copyNonEmptyStacks(List.of(ItemStack.EMPTY, source));
        assertEquals(1, copy.size());
        source.setCount(1);
        assertEquals(4, copy.getFirst().getCount());
        assertNotSame(source, copy.getFirst());
    }
}
