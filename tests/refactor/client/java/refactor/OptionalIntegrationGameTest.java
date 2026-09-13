package refactor;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.compat.BedrockCompat;
import me.aleksilassila.litematica.printer.interfaces.compat.TakeItOutCompat;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/** Enabled only when the optional test-mod directory is explicitly supplied. */
public final class OptionalIntegrationGameTest implements FabricClientGameTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean bedrockContains(ClientLevel level, BlockPos target) {
        try {
            Class<?> managerClass = Class.forName("com.github.bunnyi116.bedrockminer.task.TaskManager");
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            return (boolean) managerClass.getMethod("isInTasks", ClientLevel.class, BlockPos.class)
                    .invoke(manager, level, target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot inspect actual bedrock task queue", exception);
        }
    }

    @Override public void runTest(ClientGameTestContext context) {
        check(FabricLoader.getInstance().isModLoaded("takeitout"), "TakeItOut is loaded");
        check(FabricLoader.getInstance().isModLoaded("bedrockminer"), "BedrockMiner is loaded");
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            var server = world.getServer();
            server.runCommand("gamemode survival @a");
            server.runCommand("fill -3 64 -3 3 64 3 minecraft:stone");
            server.runCommand("tp @a 0 65 0");
            server.runCommand("setblock 2 65 0 minecraft:bedrock");
            server.runCommand("clear @a");
            server.runOnServer(mcServer -> {
                ItemStack box = new ItemStack(Items.SHULKER_BOX);
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 20))));
                mcServer.getPlayerList().getPlayers().getFirst().getInventory().setItem(9, box);
            });
            context.waitTicks(10);
            context.runOnClient(mc -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
                Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.valueOf("TAKE_IT_OUT"));
                // Pinned 1.1.27 uses a three-argument payload constructor; the unchanged
                // baseline bridge resolves only (int, int). Preserve rejection here.
                check(!QuickShulkerUtils.requestShulkerItem(mc.player, new net.minecraft.world.item.Item[]{Items.STONE}),
                        "unsupported TakeItOut payload preserves baseline rejection");
                check(!TakeItOutCompat.isAwaitingItem(), "unsupported bridge does not leave a pending wait");
            });
            context.waitTicks(20);
            server.runOnServer(mcServer -> {
                var inventory = mcServer.getPlayerList().getPlayers().getFirst().getInventory();
                int stones = 0;
                for (int slot = 0; slot < 36; slot++) if (inventory.getItem(slot).is(Items.STONE))
                    stones += inventory.getItem(slot).getCount();
                check(stones == 0, "unsupported bridge does not transfer or duplicate stone");
                check(fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(inventory.getItem(9), -1)
                                .stream().mapToInt(ItemStack::getCount).sum() == 20,
                        "unsupported bridge preserves source contents");
            });
            server.runCommand("item replace entity @a hotbar.0 with minecraft:diamond_pickaxe");
            server.runCommand("enchant @a minecraft:efficiency 5");
            server.runCommand("effect give @a minecraft:haste 60 1 true");
            server.runCommand("give @a minecraft:piston 2");
            server.runCommand("give @a minecraft:redstone_torch 1");
            context.waitTicks(20);
            context.runOnClient(mc -> {
                check(!TakeItOutCompat.isAwaitingItem(), "unsupported TakeItOut remains out of wait state");
                check(BedrockCompat.isAvailable(), "BedrockMiner reflective API resolves");
                BedrockCompat.setFeatureEnable(true);
                BedrockCompat.clearTasks();
                BedrockCompat.setWorking(true);
                check(BedrockCompat.isWorking(), "bedrock worker starts");
                BlockPos target = new BlockPos(2, 65, 0);
                BedrockCompat.addToBreakList(target, mc.level);
                check(bedrockContains(mc.level, target), "bedrock task reaches real queue");
                Configs.Trench.ENABLED.setBooleanValue(true);
                ModuleManager.prepareTick();
                check(!BedrockCompat.isWorking(), "trench switch stops bedrock worker");
                check(!bedrockContains(mc.level, target), "trench switch clears bedrock queue");
                Configs.Trench.ENABLED.setBooleanValue(false);
                BedrockCompat.setFeatureEnable(false);
            });
            System.out.println("REFACTOR OPTIONAL PARITY PASSED: TakeItOut baseline rejection and BedrockMiner task cancellation");
        }
    }
}
