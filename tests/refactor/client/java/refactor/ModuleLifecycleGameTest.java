package refactor;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.TrenchModeType;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Runs the actual ModuleManager: fill, range pause/resume, and trench exclusivity. */
public final class ModuleLifecycleGameTest implements FabricClientGameTest {
    private static final BlockPos TARGET = new BlockPos(2, 65, 0);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void select(BlockPos first, BlockPos second) {
        var manager = DataManager.getSelectionManager();
        if (manager.getSelectionMode() != SelectionMode.SIMPLE) manager.switchSelectionMode();
        var area = DataManager.getSimpleArea();
        var box = area.getSubRegionBox(area.getName());
        check(box != null, "simple selection has a box");
        box.setPos1(first);
        box.setPos2(second);
    }

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("fill -3 64 -3 23 64 3 minecraft:stone");
            server.runCommand("tp @a 0 65 0");
            server.runCommand("item replace entity @a hotbar.0 with minecraft:stone 64");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Print.ENABLED.setBooleanValue(false);
                Configs.Mine.ENABLED.setBooleanValue(false);
                Configs.Fluid.ENABLED.setBooleanValue(false);
                Configs.Trench.ENABLED.setBooleanValue(false);
                Configs.Bedrock.ENABLED.setBooleanValue(false);
                Configs.Core.WORK_RANGE.setDoubleValue(4.5);
                Configs.Core.ITERATION_TIME_LIMIT.setIntegerValue(0);
                Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
                Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
                Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
                Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.HANDHELD);
                Configs.Fill.FILL_BLOCK_FACING.setOptionListValue(FillModeFacingType.DOWN);
                Configs.Fill.FILL_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
                select(TARGET, TARGET);
                InventoryUtils.setSelectedSlot(mc.player.getInventory(), 0);
                Configs.Fill.ENABLED.setBooleanValue(true);
                Configs.Core.WORK_SWITCH.setBooleanValue(true);
            });
            context.waitTicks(30);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).is(Blocks.STONE),
                    "real fill module places a stone"));

            context.runOnClient(mc -> Configs.Core.WORK_SWITCH.setBooleanValue(false));
            server.runCommand("setblock 2 65 0 minecraft:air");
            server.runCommand("tp @a 20 65 0");
            context.waitTicks(10);
            context.runOnClient(mc -> Configs.Core.WORK_SWITCH.setBooleanValue(true));
            context.waitTicks(20);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).isAir(),
                    "fill pauses outside working range"));
            server.runCommand("tp @a 0 65 0");
            context.waitTicks(30);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).is(Blocks.STONE),
                    "fill resumes after returning to working range"));

            context.runOnClient(mc -> Configs.Core.WORK_SWITCH.setBooleanValue(false));
            server.runCommand("fill 1 65 -2 3 65 2 minecraft:stone");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                select(new BlockPos(1, 65, -2), new BlockPos(3, 65, 2));
                Configs.Trench.MODE.setOptionListValue(TrenchModeType.FOUR_SIDES);
                Configs.Trench.SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
                Configs.Trench.ENABLED.setBooleanValue(true);
                Configs.Core.WORK_SWITCH.setBooleanValue(true);
            });
            context.waitTicks(60);
            server.runOnServer(mcServer -> {
                for (int x = 1; x <= 3; x++) for (int z = -2; z <= 2; z++) {
                    var state = mcServer.overworld().getBlockState(new BlockPos(x, 65, z));
                    boolean center = x == 2 && z > -2 && z < 2;
                    check(center ? state.isAir() : state.is(Blocks.STONE),
                            "trench keeps walls and suppresses enabled fill at " + x + "," + z);
                }
            });
            context.runOnClient(mc -> Configs.Trench.ENABLED.setBooleanValue(false));
            context.waitTicks(40);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).is(Blocks.STONE),
                    "fill resumes after disabling trench"));
            context.runOnClient(mc -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Fill.ENABLED.setBooleanValue(false);
            });
            System.out.println("REFACTOR MODULE PARITY PASSED: fill, range pause/resume and trench exclusivity");
        }
    }
}
