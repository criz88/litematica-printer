import com.google.gson.Gson;
import com.google.gson.JsonObject;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlacementParityTest extends RefactorTestEnvironment {
    @Test void placementAndCorrectionActionsMatchBeforeExtraction() throws Exception {
        JsonObject saved = new JsonObject();
        fi.dy.masa.malilib.config.ConfigUtils.writeConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
        mc.player = mock(LocalPlayer.class);
        mc.level = mock(ClientLevel.class);
        mc.gameMode = mock(MultiPlayerGameMode.class);
        when(mc.gameMode.getPlayerMode()).thenReturn(GameType.SURVIVAL);
        when(mc.level.dimension()).thenReturn(Level.OVERWORLD);
        when(mc.level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
        when(mc.level.getFluidState(any())).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(mc.level.getMaxY()).thenReturn(320);
        WorldSchematic schematic = mock(WorldSchematic.class);
        when(schematic.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(false);
        Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(true);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(true);
        Configs.Print.SAFELY_OBSERVER.setBooleanValue(false);
        try (var axes = mockStatic(AxeItemAccessor.class);
             var inventory = mockStatic(InventoryUtils.class)) {
            axes.when(AxeItemAccessor::getStrippedBlocks).thenReturn(Map.of(Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG));
            inventory.when(() -> InventoryUtils.playerHasAccessToItem(any(), any())).thenReturn(true);
            PlacementGuide guide = new PlacementGuide(mc);
            Map<String, String> hashes = new TreeMap<>();
            StringBuilder details = new StringBuilder();
            int cases = 0, actions = 0;
            Block[] blocks = {Blocks.STONE, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.OAK_DOOR,
                    Blocks.OAK_TRAPDOOR, Blocks.HOPPER, Blocks.CHEST, Blocks.REPEATER, Blocks.COMPARATOR,
                    Blocks.LEVER, Blocks.SNOW, Blocks.SEA_PICKLE, Blocks.CANDLE, Blocks.COMPOSTER,
                    Blocks.DAYLIGHT_DETECTOR, Blocks.ROSE_BUSH, Blocks.TORCH, Blocks.WALL_TORCH,
                    Blocks.AMETHYST_CLUSTER, Blocks.LADDER, Blocks.LANTERN, Blocks.END_ROD,
                    Blocks.RAIL, Blocks.PISTON, Blocks.OBSERVER, Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN,
                    Blocks.WHITE_BANNER, Blocks.WHITE_WALL_BANNER, Blocks.SKELETON_SKULL,
                    Blocks.SKELETON_WALL_SKULL, Blocks.FLOWER_POT, Blocks.POTTED_DANDELION};
            for (Block block : blocks) {
                for (String scenario : List.of("missing", "state", "wrong", "correct")) {
                    String key = scenario + "/" + BuiltInRegistries.BLOCK.getKey(block);
                    StringBuilder group = new StringBuilder();
                    for (BlockState required : block.getStateDefinition().getPossibleStates()) {
                        BlockState current = switch (scenario) {
                            case "missing" -> Blocks.AIR.defaultBlockState();
                            case "wrong" -> Blocks.COBBLESTONE.defaultBlockState();
                            case "correct" -> required;
                            default -> block.defaultBlockState();
                        };
                        BreakUtils.INSTANCE.cancelAll();
                        BlockPosCooldownManager.INSTANCE.clearAllCooldowns();
                        SchematicBlockContext context = new SchematicBlockContext(mc, mc.level, schematic,
                                BlockPos.ZERO, current, required);
                        Action action = guide.getAction(context);
                        cases++;
                        if (action != null) actions++;
                        group.append(required).append(" => ").append(describe(action, block))
                                .append(" breaks=").append(breakCount())
                                .append(" observerCooldown=").append(BlockPosCooldownManager.INSTANCE
                                        .getRemainingCooldown(mc.level, "observer", BlockPos.ZERO)).append('\n');
                    }
                    details.append(key).append('\n').append(group);
                    byte[] digest = MessageDigest.getInstance("SHA-256")
                            .digest(group.toString().getBytes(StandardCharsets.UTF_8));
                    hashes.put(key, HexFormat.of().formatHex(digest));
                }
            }
            assertTrue(cases > 500);
            assertTrue(actions > 100, "Fixture must exercise actions, not only early exits");
            Path fixtures = Path.of(System.getProperty("refactor.fixtures"));
            Path fixture = fixtures.resolve("placement-baseline.json");
            Path actual = fixtures.getParent().resolve("../../build/refactor/placement-actual.txt").normalize();
            Files.createDirectories(actual.getParent());
            Files.writeString(actual, details);
            Gson gson = new Gson();
            if (Boolean.getBoolean("refactor.record")) Files.writeString(fixture, gson.toJson(hashes) + "\n");
            Map<?, ?> expected = gson.fromJson(Files.readString(fixture), Map.class);
            for (var entry : hashes.entrySet()) assertEquals(expected.get(entry.getKey()), entry.getValue(), entry.getKey());
            assertEquals(expected.size(), hashes.size());
            System.out.println("Placement parity: " + cases + " cases, " + actions + " actions, " + hashes.size() + " groups");
        } finally {
            BreakUtils.INSTANCE.cancelAll();
            BlockPosCooldownManager.INSTANCE.clearAllCooldowns();
            fi.dy.masa.malilib.config.ConfigUtils.readConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
            mc.level = null;
        }
    }

    private static String describe(Action action, Block block) throws Exception {
        if (action == null) return "null";
        Map<String, String> sides = new TreeMap<>();
        action.getSides().forEach((side, offset) -> sides.put(side.name(), offset.toString()));
        var items = action.getRequiredItems(block);
        var support = Action.class.getDeclaredField("requiresSupport");
        support.setAccessible(true);
        return action.getClass().getSimpleName() + " items=" + (items == null ? "null"
                : Arrays.stream(items).map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList())
                + " sides=" + sides + " look=" + action.getPlayerLook()
                + " shift=" + action.getShift() + " wait=" + action.getNeedWaitModifyLook()
                + " support=" + support.getBoolean(action);
    }

    private static int breakCount() throws Exception {
        var queue = BreakUtils.class.getDeclaredField("breakQueue");
        queue.setAccessible(true);
        return ((Collection<?>) queue.get(BreakUtils.INSTANCE)).size();
    }
}
