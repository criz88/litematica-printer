package refactor;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.mixin.extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.mixin.extension.ConfigExtension;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlacementGuide;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.concurrent.atomic.AtomicReference;

/** Exercises real config mixins, scanning, placement packets and server-confirmed mining. */
public final class PrinterRefactorGameTest implements FabricClientGameTest {
    private static final BlockPos TARGET = new BlockPos(2, 65, 0);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksDownload();
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("fill -5 64 -5 5 64 5 minecraft:stone");
            server.runCommand("tp @a 0 65 0");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                check(Configs.Core.WORK_RANGE instanceof ConfigExtension, "MaLiLib config mixin is applied");
                Configs.Core.UPDATE_CHECK.setBooleanValue(false);
                Configs.Core.WORK_SWITCH.setBooleanValue(true);
                Configs.Print.ENABLED.setBooleanValue(false);
                Configs.Mine.ENABLED.setBooleanValue(false);
                Configs.Fill.ENABLED.setBooleanValue(false);
                Configs.Fluid.ENABLED.setBooleanValue(false);
                Configs.Trench.ENABLED.setBooleanValue(false);
                Configs.Bedrock.ENABLED.setBooleanValue(false);
                Configs.Core.WORK_RANGE.setDoubleValue(4.5);
                Configs.Core.ITERATION_TIME_LIMIT.setIntegerValue(0);
                Configs.Core.CLASSIFY_BY_BLOCK.setBooleanValue(false);
                Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
                Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
                Configs config = new Configs();
                config.save();
                Configs.Core.WORK_RANGE.setDoubleValue(1);
                config.load();
                check(Configs.Core.WORK_RANGE.getDoubleValue() == 4.5, "real config file round-trip");
            });
            for (boolean packet : new boolean[]{false, true}) {
                server.runCommand("setblock 2 65 0 minecraft:air");
                server.runCommand("item replace entity @a hotbar.0 with minecraft:stone 64");
                context.waitTicks(10);
                context.runOnClient(mc -> {
                    Configs.Placement.PRINT_USE_PACKET.setBooleanValue(packet);
                    InventoryUtils.setSelectedSlot(mc.player.getInventory(), 0);
                    Probe module = new Probe(Blocks.STONE.defaultBlockState());
                    module.tick();
                    check(module.executed, "real iterator reaches the placement target");
                });
                context.waitTicks(10);
                server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).is(Blocks.STONE),
                        "authoritative server placement, packet=" + packet));
                context.runOnClient(mc -> check(mc.level.getBlockState(TARGET).is(Blocks.STONE),
                        "server confirms placement, packet=" + packet));
                context.runOnClient(mc -> {
                    Configs.Break.BREAK_USE_PACKET.setBooleanValue(packet);
                    check(BreakUtils.INSTANCE.continueDestroyBlock(TARGET, Direction.UP, !packet)
                            == BlockBreakResult.COMPLETED, "creative mining completes");
                });
                context.waitTicks(10);
                server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).isAir(),
                        "authoritative server mining, packet=" + packet));
                context.runOnClient(mc -> check(mc.level.getBlockState(TARGET).isAir(),
                        "server confirms mining, packet=" + packet));

                for (Block block : new Block[]{Blocks.OAK_STAIRS, Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR,
                        Blocks.ROSE_BUSH, Blocks.HOPPER, Blocks.CHEST, Blocks.REPEATER, Blocks.OAK_SLAB}) {
                    String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                    server.runCommand("fill 1 65 -1 3 67 1 minecraft:air");
                    server.runCommand("setblock 2 64 0 " + (block == Blocks.ROSE_BUSH ? "minecraft:dirt" : "minecraft:stone"));
                    server.runCommand("item replace entity @a hotbar.0 with " + id + " 64");
                    context.waitTicks(10);
                    context.runOnClient(mc -> {
                        InventoryUtils.setSelectedSlot(mc.player.getInventory(), 0);
                        Probe module = new Probe(block.defaultBlockState());
                        module.tick();
                        check(module.executed, "iterator executes " + id);
                    });
                    context.waitTicks(15);
                    server.runOnServer(mcServer -> {
                        BlockState actual = mcServer.overworld().getBlockState(TARGET);
                        check(actual.equals(block.defaultBlockState()),
                                "server placement " + id + ", packet=" + packet + ", actual=" + actual);
                        if (block == Blocks.ROSE_BUSH || block == Blocks.OAK_DOOR)
                            check(mcServer.overworld().getBlockState(TARGET.above()).is(block),
                                    "upper half is generated for " + id);
                    });
                    System.out.println("REFACTOR PLACEMENT PASSED: " + id + ", packet=" + packet);
                    if (block == Blocks.OAK_DOOR || block == Blocks.OAK_TRAPDOOR || block == Blocks.REPEATER) {
                        server.runOnServer(mcServer -> {
                            BlockState changed = block == Blocks.REPEATER
                                    ? block.defaultBlockState().setValue(BlockStateProperties.DELAY, 4)
                                    : block.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
                            mcServer.overworld().setBlockAndUpdate(TARGET, changed);
                        });
                        context.waitTicks(10);
                        context.runOnClient(mc -> {
                            Probe module = new Probe(block.defaultBlockState());
                            module.tick();
                            check(module.executed, "state correction executes " + id);
                        });
                        context.waitTicks(15);
                        server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET)
                                        .equals(block.defaultBlockState()), "server state correction " + id));
                        System.out.println("REFACTOR CORRECTION PASSED: " + id + ", packet=" + packet);
                    }
                }
            }
            // A queued horizontal turn must not place after the player leaves its range.
            server.runCommand("fill 1 65 -1 3 67 1 minecraft:air");
            server.runCommand("setblock 2 65 -1 minecraft:stone");
            server.runCommand("item replace entity @a hotbar.0 with minecraft:barrel 64");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                Probe module = new Probe(Blocks.BARREL.defaultBlockState());
                module.tick();
                check(module.executed && ActionManager.INSTANCE.needWaitModifyLook, "barrel waits for horizontal turn");
                mc.player.setPos(20.5, 65, 0.5);
            });
            server.runCommand("tp @a 20 65 0");
            context.waitTicks(10);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).isAir(),
                    "queued placement is cancelled after moving out of range"));
            context.runOnClient(mc -> check(!ActionManager.INSTANCE.needWaitModifyLook, "out-of-range queue is cleared"));
            server.runCommand("tp @a 0 65 0");
            server.runCommand("fill 1 65 -1 3 67 1 minecraft:air");
            server.runCommand("item replace entity @a hotbar.0 with minecraft:ice 64");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true);
                Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(false);
                SchematicBlockContext water = new SchematicBlockContext(mc, mc.level, null, TARGET,
                        mc.level.getBlockState(TARGET), Blocks.WATER.defaultBlockState());
                check(new PlacementGuide(mc).getAction(water) == null, "creative mode skips ice-for-water placement");
            });
            server.runCommand("gamemode survival @a");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                Probe module = new Probe(Blocks.WATER.defaultBlockState());
                module.tick();
                check(module.executed, "water request places ice in survival mode");
            });
            context.waitTicks(10);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).is(Blocks.ICE),
                    "server receives intermediate ice"));
            server.runCommand("item replace entity @a hotbar.0 with minecraft:iron_pickaxe");
            context.waitTicks(10);
            context.runOnClient(mc -> {
                SchematicBlockContext water = new SchematicBlockContext(mc, mc.level, null, TARGET,
                        mc.level.getBlockState(TARGET), Blocks.WATER.defaultBlockState());
                check(new PlacementGuide(mc).getAction(water) != null && BreakUtils.INSTANCE.inQueue(TARGET),
                        "ice state queues mining for water request");
            });
            context.waitTicks(80);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET)
                            .equals(Blocks.WATER.defaultBlockState()), "survival mining generates a water source"));
            server.runCommand("item replace entity @a hotbar.0 with minecraft:oak_slab 64");
            context.waitTicks(10);
            BlockState wetSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
            context.runOnClient(mc -> {
                Probe module = new Probe(wetSlab);
                module.tick();
                check(module.executed, "water source enables waterlogged block action");
            });
            context.waitTicks(15);
            server.runOnServer(mcServer -> check(mcServer.overworld().getBlockState(TARGET).equals(wetSlab),
                    "waterlogged slab is placed into generated source"));
            System.out.println("REFACTOR WATER AND WAIT PARITY PASSED: turn cancellation, creative skip, survival ice, source and waterlogged slab");
            context.runOnClient(mc -> {
                Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(false);
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                ActionManager.INSTANCE.clearQueue();
                BreakUtils.INSTANCE.cancelAll();
            });
            System.out.println("REFACTOR GAME PARITY PASSED: config mixin, persistence, scan, placement and mining in both packet modes");
        }
    }

    private static final class Probe extends Module {
        private boolean executed;
        private final BlockState required;

        private Probe(BlockState required) {
            super("refactor-game", null, null, true);
            this.required = required;
        }
        @Override protected int getMaxExecutions() { return 1; }
        @Override protected boolean needsAreaCheck() { return false; }
        @Override public boolean canProcessPos(BlockPos pos) { return pos.equals(TARGET); }
        @Override public boolean isCorrectBlock(BlockPos pos) { return level.getBlockState(pos).equals(required); }
        @Override protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skip) {
            SchematicBlockContext context = new SchematicBlockContext(mc, level, null, pos,
                    level.getBlockState(pos), required);
            Action action = new PlacementGuide(mc).getAction(context);
            check(action != null, "placement guide creates a real action");
            Direction side = action.getValidSide(level, pos);
            check(side != null, "placement has a valid support face");
            action.queueAction(pos, side, false, player);
            ActionManager.INSTANCE.setLook(action.getPlayerLook());
            ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
            ActionManager.INSTANCE.sendQueue(player);
            executed = true;
        }
    }
}
