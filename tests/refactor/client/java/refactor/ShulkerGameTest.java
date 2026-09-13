package refactor;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/** Real QuickShulker open/content/click/close packets and moved-container identity. */
public final class ShulkerGameTest implements FabricClientGameTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ItemStack box(Item marker) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                List.of(new ItemStack(Items.STONE, 20), new ItemStack(marker))));
        return box;
    }

    private static int stones(ItemStack box) {
        return fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(box, -1).stream()
                .filter(stack -> stack.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
    }

    private static void request(ClientGameTestContext context, Item item) {
        context.runOnClient(mc -> check(QuickShulkerUtils.requestShulkerItem(mc.player, new Item[]{item}),
                "QuickShulker request accepted"));
        context.waitTicks(20);
        context.runOnClient(mc -> {
            check(!QuickShulkerUtils.isOpenHandler(), "content callback finished");
            check(mc.player.containerMenu == mc.player.inventoryMenu, "deferred close completed");
            check(mc.player.containerMenu.getCarried().isEmpty(), "cursor is empty after transfer");
        });
    }

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("clear @a");
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                inventory.setItem(9, box(Items.TORCH));
                inventory.setItem(10, box(Items.STICK));
            });
            context.waitTicks(10);
            context.runOnClient(mc -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
                Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.MOD);
                Configs.Print.SHULKER_COOLDOWN.setIntegerValue(0);
                Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(true);
            });
            request(context, Items.STONE);
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                check(inventory.getItem(0).is(Items.STONE) && inventory.getItem(0).getCount() == 20,
                        "server received stone into first free hotbar slot");
                check(stones(inventory.getItem(9)) == 0, "source box was emptied of stone");
                ItemStack source = inventory.getItem(9).copy();
                ItemStack decoy = inventory.getItem(10).copy();
                for (int slot = 1; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                inventory.setItem(9, decoy);
                inventory.setItem(11, source);
            });
            context.waitTicks(10);
            request(context, Items.DIRT);
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                check(inventory.getItem(0).isEmpty(), "return freed the material slot");
                check(stones(inventory.getItem(11)) == 20, "exact return follows moved source box");
                check(stones(inventory.getItem(9)) == 20, "similar decoy box is unchanged");
            });

            request(context, Items.STONE);
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                ItemStack emptied = inventory.getItem(9).copy();
                inventory.setItem(9, inventory.getItem(11).copy());
                inventory.setItem(11, emptied);
            });
            context.waitTicks(10);
            context.runOnClient(mc -> Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(false));
            request(context, Items.DIRT);
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                check(inventory.getItem(0).isEmpty(), "non-exact return freed the material slot");
                check(stones(inventory.getItem(9)) == 40, "non-exact return uses first available box");
                check(stones(inventory.getItem(11)) == 0, "non-exact return does not follow source box");
            });
            System.out.println("REFACTOR SHULKER PARITY PASSED: take, deferred close, moved exact return and non-exact return");
        }
    }
}
