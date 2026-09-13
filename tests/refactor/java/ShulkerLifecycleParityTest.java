import com.google.gson.JsonObject;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShulkerLifecycleParityTest extends RefactorTestEnvironment {
    JsonObject saved;
    Method finish;
    Inventory inventory;
    AbstractContainerMenu container;
    int closeScreen;

    @BeforeEach void setup() throws Exception {
        saved = new JsonObject();
        fi.dy.masa.malilib.config.ConfigUtils.writeConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
        closeScreen = ModUtils.closeScreen;
        mc.player = mock(LocalPlayer.class);
        mc.gameMode = mock(MultiPlayerGameMode.class);
        inventory = mock(Inventory.class);
        when(mc.player.getInventory()).thenReturn(inventory);
        container = mock(AbstractContainerMenu.class);
        when(container.getCarried()).thenReturn(ItemStack.EMPTY);
        mc.player.containerMenu = container;
        var inventoryMenu = net.minecraft.world.entity.player.Player.class.getDeclaredField("inventoryMenu");
        inventoryMenu.setAccessible(true);
        inventoryMenu.set(mc.player, mock(InventoryMenu.class));
        finish = QuickShulkerUtils.class.getDeclaredMethod("finishShulkerOperation", LocalPlayer.class);
        finish.setAccessible(true);
        finish.invoke(null, mc.player);
        clearInvocations(mc.player);
        returns().clear();
        QuickShulkerUtils.setShulkerCooldown(0);
    }

    @AfterEach void cleanup() throws Exception {
        returns().clear();
        finish.invoke(null, mc.player);
        QuickShulkerUtils.setShulkerCooldown(0);
        ModUtils.closeScreen = closeScreen;
        fi.dy.masa.malilib.config.ConfigUtils.readConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
    }

    @Test void emptyCursorClosesAfterExactlyTwoTicks() throws Exception {
        finish.invoke(null, new Object[]{null});
        QuickShulkerUtils.tick();
        verify(mc.player, never()).closeContainer();
        QuickShulkerUtils.tick();
        verify(mc.player, times(1)).closeContainer();
        QuickShulkerUtils.tick();
        verify(mc.player, times(1)).closeContainer();
    }

    @Test void carriedItemDelaysCloseUntilTenthRetry() throws Exception {
        when(container.getCarried()).thenReturn(new ItemStack(Items.STONE));
        finish.invoke(null, new Object[]{null});
        for (int tick = 0; tick < 10; tick++) QuickShulkerUtils.tick();
        verify(mc.player, never()).closeContainer();
        QuickShulkerUtils.tick();
        verify(mc.player, times(1)).closeContainer();
    }

    @Test void fullInventoryReturnsRequestsInFifoOrderAndClearsReturnCooldown() throws Exception {
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
        Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(true);
        Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.PLUGIN);
        when(inventory.getContainerSize()).thenReturn(36);
        when(inventory.getItem(anyInt())).thenReturn(new ItemStack(Items.STONE));
        when(inventory.getItem(9)).thenReturn(new ItemStack(Items.BLUE_SHULKER_BOX));
        when(inventory.getItem(10)).thenReturn(new ItemStack(Items.RED_SHULKER_BOX));
        NonNullList<Slot> slots = NonNullList.create();
        for (int i = 0; i < 63; i++) {
            Slot slot = mock(Slot.class);
            when(slot.getItem()).thenReturn(ItemStack.EMPTY);
            slots.add(slot);
        }
        var field = AbstractContainerMenu.class.getDeclaredField("slots");
        field.setAccessible(true);
        field.set(container, slots);
        returns().add(request(Items.BLUE_SHULKER_BOX));
        returns().add(request(Items.RED_SHULKER_BOX));
        try (var contents = mockStatic(fi.dy.masa.malilib.util.InventoryUtils.class)) {
            contents.when(() -> fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(any(), eq(-1)))
                    .thenReturn(NonNullList.create());
            for (int expectedSlot : new int[]{9, 10}) {
                assertTrue(QuickShulkerUtils.requestShulkerItem(mc.player, new Item[]{Items.DIRT}));
                assertEquals(expectedSlot, QuickShulkerUtils.getShulkerBoxSlot());
                assertTrue(QuickShulkerUtils.isOpenHandler());
                QuickShulkerUtils.switchFromShulker();
                assertFalse(QuickShulkerUtils.isOpenHandler());
                assertEquals(-1, QuickShulkerUtils.getShulkerBoxSlot());
                assertEquals(0, QuickShulkerUtils.getShulkerCooldown());
            }
            assertTrue(returns().isEmpty());
        }
    }

    // Prepare existing return requests without depending on the private tracking class name.
    private Object request(Item boxItem) throws Exception {
        Class<?> requestClass = Class.forName(QuickShulkerUtils.class.getName() + "$ReturnRequest");
        var constructor = requestClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var trackedConstructor = constructor.getParameterTypes()[1].getDeclaredConstructor(Item.class, List.class);
        trackedConstructor.setAccessible(true);
        return constructor.newInstance(Items.STONE, trackedConstructor.newInstance(boxItem, List.of()));
    }

    @SuppressWarnings("unchecked")
    private Queue<Object> returns() throws Exception {
        var field = QuickShulkerUtils.class.getDeclaredField("itemsToReturn");
        field.setAccessible(true);
        return (Queue<Object>) field.get(null);
    }
}
