import dev.blinkwhite.remoteinventory.network.payload.RemoteExchangeResultPayload.SlotSnapshot;
import me.aleksilassila.litematica.printer.utils.RemoteContainerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteInventoryDeltaParityTest extends RefactorTestEnvironment {
    @Test void slotDeltaKeepsBoundsEmptyUnknownItemsAndMinimumCount() throws Exception {
        mc.player = mock(LocalPlayer.class);
        Inventory inventory = mock(Inventory.class);
        when(mc.player.getInventory()).thenReturn(inventory);
        var apply = RemoteContainerUtils.class.getDeclaredMethod("applyInventoryDelta", List.class);
        apply.setAccessible(true);
        apply.invoke(null, List.of(new SlotSnapshot(-1, "minecraft:stone", 4),
                new SlotSnapshot(36, "minecraft:stone", 4),
                new SlotSnapshot(0, "", 9),
                new SlotSnapshot(1, "minecraft:stone", 0),
                new SlotSnapshot(2, "minecraft:dirt", 12),
                new SlotSnapshot(3, "refactor:missing_item", 5)));
        verify(inventory).setItem(0, ItemStack.EMPTY);
        ArgumentCaptor<ItemStack> stone = ArgumentCaptor.forClass(ItemStack.class);
        ArgumentCaptor<ItemStack> dirt = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).setItem(eq(1), stone.capture());
        verify(inventory).setItem(eq(2), dirt.capture());
        assertTrue(stone.getValue().is(Items.STONE));
        assertEquals(1, stone.getValue().getCount());
        assertTrue(dirt.getValue().is(Items.DIRT));
        assertEquals(12, dirt.getValue().getCount());
        verifyNoMoreInteractions(inventory);
        mc.player = null;
        apply.invoke(null, List.of(new SlotSnapshot(0, "minecraft:stone", 4)));
        verifyNoMoreInteractions(inventory);
    }
}
