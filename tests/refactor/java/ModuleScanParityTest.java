import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.handler.IteratorManager;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleScanParityTest extends RefactorTestEnvironment {
    static final BlockPos A = BlockPos.ZERO;
    static final BlockPos B = new BlockPos(1, 0, 0);
    Probe module;
    IteratorManager iterator;
    MockedStatic<LitematicaUtils> schematic;
    MockedStatic<PlayerUtils> playerUtils;
    boolean enabled, classify;

    @BeforeEach void setup() throws Exception {
        enabled = Configs.Core.WORK_SWITCH.getBooleanValue();
        classify = Configs.Core.CLASSIFY_BY_BLOCK.getBooleanValue();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
        Configs.Core.CLASSIFY_BY_BLOCK.setBooleanValue(false);
        mc.player = mock(LocalPlayer.class);
        mc.level = mock(ClientLevel.class);
        mc.gameMode = mock(MultiPlayerGameMode.class);
        when(mc.gameMode.getPlayerMode()).thenReturn(GameType.SURVIVAL);
        when(mc.getConnection()).thenReturn(mock(ClientPacketListener.class));
        module = new Probe();
        iterator = mock(IteratorManager.class);
        var field = Module.class.getDeclaredField("iteratorManager");
        field.setAccessible(true);
        field.set(module, iterator);
        when(iterator.isWithinRange(any())).thenReturn(true);
        when(iterator.nextCandidate()).thenReturn(A, B, null);
        schematic = mockStatic(LitematicaUtils.class);
        playerUtils = mockStatic(PlayerUtils.class);
        playerUtils.when(() -> PlayerUtils.canInteracted(any(BlockPos.class))).thenReturn(true);
        ActionManager.INSTANCE.clearQueue();
    }

    @AfterEach void cleanup() {
        schematic.close();
        playerUtils.close();
        ActionManager.INSTANCE.clearQueue();
        Configs.Core.WORK_SWITCH.setBooleanValue(enabled);
        Configs.Core.CLASSIFY_BY_BLOCK.setBooleanValue(classify);
        mc.level = null;
    }

    @Test void executionLimitStopsBeforeGuiUpdateAndResumesNextCandidate() {
        module.maximum = 1;
        module.tick();
        assertEquals(List.of(A), module.executed);
        assertNull(module.getGuiInfo());
        module.tick();
        assertEquals(List.of(A, B), module.executed);
        assertEquals(List.of("refresh", "preprocess", "canExecute", "canIterate",
                "refresh", "preprocess", "canExecute", "canIterate"), module.phases);
    }

    @Test void waitingPositionRetriesBeforeCandidatesAndCanPauseAgain() {
        module.waitAt(B);
        module.pause = true;
        module.tick();
        assertEquals(List.of(B), module.executed);
        assertEquals(ScanState.WAITING, module.getScanState());
        verify(iterator, never()).nextCandidate();
        module.pause = false;
        module.maximum = 1;
        module.tick();
        assertEquals(List.of(B, B), module.executed);
        assertEquals(ScanState.RUNNING, module.getScanState());
    }

    @Test void waitingPositionOutsideRangeIsDiscardedBeforeContinuing() {
        module.waitAt(B);
        when(iterator.isWithinRange(B)).thenReturn(false);
        module.tick();
        assertEquals(List.of(A), module.executed);
        assertEquals(ScanState.RUNNING, module.getScanState());
    }

    @Test void classificationLocksFirstItemUntilEndOfCycle() {
        Configs.Core.CLASSIFY_BY_BLOCK.setBooleanValue(true);
        module.tick();
        assertEquals(List.of(A), module.executed);
        when(iterator.nextCandidate()).thenReturn(B, null);
        module.tick();
        assertEquals(List.of(A, B), module.executed);
    }

    @Test void timeoutIsCheckedBetweenOutOfRangeRawCandidates() throws Exception {
        var field = Module.class.getDeclaredField("timeLimitExceeded");
        field.setAccessible(true);
        AtomicBoolean timeout = (AtomicBoolean) field.get(module);
        when(iterator.nextCandidate()).thenAnswer(call -> { timeout.set(true); return A; });
        when(iterator.isWithinRange(A)).thenReturn(false);
        module.tick();
        verify(iterator, times(1)).nextCandidate();
        assertTrue(module.executed.isEmpty());
        assertFalse(timeout.get(), "finally must clear the timeout flag");
    }

    @Test void lookWaitAndDisabledConfigPreventScanning() {
        ActionManager.INSTANCE.needWaitModifyLook = true;
        module.tick();
        verify(iterator, never()).nextCandidate();
        module.phases.clear();
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        module.tick();
        assertTrue(module.phases.isEmpty());
    }

    static class Probe extends Module {
        final List<BlockPos> executed = new ArrayList<>();
        final List<String> phases = new ArrayList<>();
        int maximum;
        boolean pause;

        Probe() { super("refactor-test", null, null, true); }
        void waitAt(BlockPos pos) { enterWaiting(pos); }
        @Override protected void updateVariables() {
            super.updateVariables();
            if (phases != null) phases.add("refresh");
        }
        @Override protected void preprocess() { phases.add("preprocess"); }
        @Override protected boolean canExecute() { phases.add("canExecute"); return true; }
        @Override protected boolean canIterate() { phases.add("canIterate"); return true; }
        @Override protected int getMaxExecutions() { return maximum; }
        @Override protected int getIterationTimeLimit() { return 0; }
        @Override protected boolean needsAreaCheck() { return false; }
        @Override public boolean isOnCooldown(BlockPos pos) { return false; }
        @Override public boolean canProcessPos(BlockPos pos) { return true; }
        @Override public boolean isCorrectBlock(BlockPos pos) { return false; }
        @Override protected Item[] getRequiredItems(BlockPos pos) {
            return new Item[]{pos.equals(A) ? Items.STONE : Items.DIRT};
        }
        @Override protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skip) {
            executed.add(pos);
            if (pause) { enterWaiting(pos); skip.set(true); }
        }
    }
}
