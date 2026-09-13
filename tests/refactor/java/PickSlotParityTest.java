import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PickSlotParityTest extends RefactorTestEnvironment {
    @Test
    void missingPlayerFailsWithoutAccessingPickSlots() {
        mc.player = null;
        try (var access = mockStatic(InventoryUtilsAccessor.class)) {
            assertEquals(InventoryUtils.PickResult.FAIL, InventoryUtils.checkPickSlotAvailable(-1, mc));
            assertFalse(InventoryUtils.setPickedItemToHand(-1, ItemStack.EMPTY, mc));
            access.verifyNoInteractions();
        }
    }

    @Test
    void hotbarShortCircuitsConfigurationAndSwitchesOnlyDuringExecution() {
        mc.player = mock(LocalPlayer.class);
        Inventory inventory = mock(Inventory.class);
        when(mc.player.getInventory()).thenReturn(inventory);
        try (var access = mockStatic(InventoryUtilsAccessor.class);
             var utils = mockStatic(InventoryUtils.class, CALLS_REAL_METHODS)) {
            utils.when(() -> InventoryUtils.setHotbarSlot(4, inventory)).thenAnswer(call -> null);
            utils.clearInvocations();
            assertEquals(InventoryUtils.PickResult.SUCCESS, InventoryUtils.checkPickSlotAvailable(4, mc));
            utils.verify(() -> InventoryUtils.setHotbarSlot(4, inventory), never());
            assertTrue(InventoryUtils.setPickedItemToHand(4, ItemStack.EMPTY, mc));
            utils.verify(() -> InventoryUtils.setHotbarSlot(4, inventory));
            access.verifyNoInteractions();
        }
    }

    @Test
    void preflightPreservesEmptySlotThenReplacementPriorityAndFailureReasons() {
        mc.player = mock(LocalPlayer.class);
        Inventory inventory = mock(Inventory.class);
        when(mc.player.getInventory()).thenReturn(inventory);
        try (var access = mockStatic(InventoryUtilsAccessor.class)) {
            access.when(InventoryUtilsAccessor::getPICK_BLOCKABLE_SLOTS).thenReturn(List.of());
            assertEquals(InventoryUtils.PickResult.FAIL_NO_PICK_SLOTS_CONFIGURED,
                    InventoryUtils.checkPickSlotAvailable(12, mc));
            access.verify(() -> InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory), never());
            access.when(InventoryUtilsAccessor::getPICK_BLOCKABLE_SLOTS).thenReturn(List.of(2, 3));
            access.when(() -> InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory)).thenReturn(2);
            assertEquals(InventoryUtils.PickResult.SUCCESS, InventoryUtils.checkPickSlotAvailable(12, mc));
            access.verify(() -> InventoryUtilsAccessor.getPickBlockTargetSlot(mc.player), never());
            access.when(() -> InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory)).thenReturn(-1);
            access.when(() -> InventoryUtilsAccessor.getPickBlockTargetSlot(mc.player)).thenReturn(3);
            assertEquals(InventoryUtils.PickResult.SUCCESS, InventoryUtils.checkPickSlotAvailable(-1, mc));
            access.when(() -> InventoryUtilsAccessor.getPickBlockTargetSlot(mc.player)).thenReturn(-1);
            assertEquals(InventoryUtils.PickResult.FAIL_NO_SUITABLE_SLOT_FOUND,
                    InventoryUtils.checkPickSlotAvailable(12, mc));
            verify(inventory, never()).setItem(anyInt(), any());
        }
    }
}
