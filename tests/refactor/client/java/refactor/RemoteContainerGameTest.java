package refactor;

import dev.blinkwhite.remoteinventory.client.ContainerItemCache;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.RemoteContainerUtils;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Exercises actual remote scan, exchange and full-inventory return packets. */
public final class RemoteContainerGameTest implements FabricClientGameTest {
    private static final BlockPos CHEST = new BlockPos(2, 65, 0);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int count(Container inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void fetch(ClientGameTestContext context, Item item) {
        for (int attempt = 0; attempt < 6; attempt++) {
            context.runOnClient(mc -> RemoteContainerUtils.tryGetItemFromContainers(item));
            context.waitTicks(10);
            if (context.computeOnClient(mc -> count(mc.player.getInventory(), item) > 0)) return;
        }
        throw new AssertionError("remote exchange did not provide " + item);
    }

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("fill -3 64 -3 3 64 3 minecraft:stone");
            server.runCommand("tp @a 0 65 0");
            server.runCommand("clear @a");
            server.runCommand("setblock 2 65 0 minecraft:chest");
            server.runOnServer(mcServer -> {
                Container chest = (Container) mcServer.overworld().getBlockEntity(CHEST);
                chest.setItem(0, new ItemStack(Items.STONE, 20));
                chest.setChanged();
            });
            context.waitTicks(10);
            context.runOnClient(mc -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Print.RETURN_TO_CONTAINER_WHEN_FULL.setBooleanValue(true);
                var manager = DataManager.getSelectionManager();
                if (manager.getSelectionMode() != SelectionMode.SIMPLE) manager.switchSelectionMode();
                var area = DataManager.getSimpleArea();
                var box = area.getSubRegionBox(area.getName());
                check(box != null, "simple selection has a box");
                box.setPos1(CHEST);
                box.setPos2(CHEST);
                RemoteContainerUtils.reset();
                RemoteContainerUtils.scanContainerPos();
            });
            fetch(context, Items.STONE);
            server.runOnServer(mcServer -> {
                check(count(mcServer.getPlayerList().getPlayers().getFirst().getInventory(), Items.STONE) == 20,
                        "server inventory receives all 20 stone");
                check(count((Container) mcServer.overworld().getBlockEntity(CHEST), Items.STONE) == 0,
                        "server chest loses transferred stone");
            });
            context.runOnClient(mc -> {
                check(!RemoteContainerUtils.hasPendingExchange(), "scan and take response release pending exchange");
                check(ContainerItemCache.INSTANCE.isCached(mc.level.dimension().toString(), CHEST),
                        "real scan response populated container cache");
            });
            // Keep the tracked stone, fill every other slot, then request a different material.
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                inventory.setItem(0, new ItemStack(Items.STONE, 20));
                Container chest = (Container) mcServer.overworld().getBlockEntity(CHEST);
                chest.setItem(0, new ItemStack(Items.DIRT, 15));
                chest.setChanged();
            });
            context.waitTicks(10);
            fetch(context, Items.DIRT);
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                check(count(inventory, Items.STONE) == 0, "full inventory returns tracked stone");
                check(count(inventory, Items.DIRT) == 15, "full inventory receives requested dirt");
                check(count((Container) mcServer.overworld().getBlockEntity(CHEST), Items.STONE) == 20,
                        "server chest receives returned stone");
            });
            context.runOnClient(mc -> {
                check(!RemoteContainerUtils.hasPendingExchange(), "return response releases pending exchange");
                RemoteContainerUtils.reset();
                check(ContainerItemCache.INSTANCE.getTotalContainerCount() == 0, "reset clears actual cache");
            });
            System.out.println("REFACTOR REMOTE PARITY PASSED: scan, take, full-inventory return and reset");
        }
    }
}
