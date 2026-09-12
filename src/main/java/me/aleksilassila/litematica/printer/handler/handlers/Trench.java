package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.data.DataManager;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.TrenchModeType;
import me.aleksilassila.litematica.printer.handler.GuiBlockInfo;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.mixin.extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.*;
import static me.aleksilassila.litematica.printer.config.Configs.Trench.*;

/** Owns the trench workflow. Other modules never execute its constituent steps. */
public class Trench extends Module {
    public static final String NAME = "trench";
    private static final Direction[] INLETS = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int RETRY_TICKS = 20;
    private final FluidFiller filler = new FluidFiller();
    private enum Role { OUTSIDE, SIDE, CENTER }
    private enum Kind { BREAK_FLUID, FILL, BREAK_CENTER }
    private final Budget placeBudget = new Budget(), breakBudget = new Budget();
    private final Map<BlockPos, Pending> pending = new HashMap<>();
    private List<PrinterBox> regions = List.of();
    private List<?> policy = List.of();
    private TrenchModeType mode = TrenchModeType.FOUR_SIDES;
    private PrinterBox selectedBounds;
    private ClientLevel contextLevel;
    private boolean wasEnabled, wasWorking;
    private BlockPos activeBreak;
    private boolean yieldTick;
    private GuiBlockInfo info;
    private String status = "idle";
    private long statusUntil;

    public Trench() { super(NAME, ENABLED, SELECTION_TYPE, true); }

    /** Called before either the shared break continuation or placement queue is executed. */
    public boolean prepareTick() {
        updateVariables();
        boolean enabled = ENABLED.getBooleanValue();
        boolean working = ConfigUtils.isPrinterEnable();
        var next = enabled && level != null ? LitematicaUtils.getCurrentSelectionBoxes() : regions;
        boolean newWorld = contextLevel != level;
        boolean changed = !next.equals(regions);
        var nextMode = (TrenchModeType) MODE.getOptionListValue();
        List<?> nextPolicy = List.of(nextMode, SELECTION_TYPE.getOptionListValue(),
                INCLUDE_FLOWING.getBooleanValue(),
                List.copyOf(FLUID_REPLACE_BLOCK_LIST.getStrings()), List.copyOf(FLUID_LIST.getStrings()),
                MINING_LIMITER.getOptionListValue(), MINING_LIMIT.getOptionListValue(),
                List.copyOf(MINING_WHITELIST.getStrings()), List.copyOf(MINING_BLACKLIST.getStrings()));
        PrinterBox nextBounds = level == null || player == null ? null : selectionBounds();
        boolean settingsChanged = !nextPolicy.equals(policy) || !Objects.equals(nextBounds, selectedBounds);
        boolean cancel = enabled != wasEnabled || ((enabled || wasEnabled) &&
                (changed || settingsChanged || newWorld || working != wasWorking));
        if (newWorld) pending.clear();
        regions = next;
        mode = nextMode;
        policy = nextPolicy;
        selectedBounds = nextBounds;
        if (changed) pending.keySet().removeIf(pos -> role(pos) == Role.OUTSIDE);
        if (changed || settingsChanged) {
            // Geometry changes can turn an old center into an end wall or vice versa.
            pending.forEach((pos, task) -> task.repair = task.kind != Kind.BREAK_CENTER && role(pos) == Role.SIDE);
            resetScanState();
            iteratorManager.markNeedsRebuild();
        }
        if (cancel) {
            activeBreak = null;
            info = null;
            getPendingHighlights().clear();
            status = "idle";
            statusUntil = 0;
        }
        contextLevel = level;
        wasEnabled = enabled;
        wasWorking = working;
        if (enabled && (changed || cancel) && (regions.isEmpty() || regions.stream().anyMatch(r -> !valid(r)))) {
            MessageUtils.setOverlayMessage(MessageUtils.translatable("litematica-printer.trench.invalidSelection"));
        }
        if (enabled) filler.refresh(FLUID_REPLACE_BLOCK_LIST.getStrings(), FLUID_LIST.getStrings());
        return cancel;
    }

    private static boolean valid(PrinterBox r) {
        return !r.isEmpty() && ((r.maxX - r.minX == 2 && r.maxZ - r.minZ > 2)
                || (r.maxZ - r.minZ == 2 && r.maxX - r.minX > 2));
    }

    private Role role(BlockPos pos) {
        Role result = Role.OUTSIDE;
        for (PrinterBox r : regions) {
            if (!valid(r) || !r.contains(pos)) continue;
            boolean alongZ = r.maxX - r.minX == 2;
            boolean center = alongZ ? pos.getX() == r.minX + 1 : pos.getZ() == r.minZ + 1;
            boolean end = alongZ ? pos.getZ() == r.minZ || pos.getZ() == r.maxZ
                    : pos.getX() == r.minX || pos.getX() == r.maxX;
            if (center && !(mode == TrenchModeType.FOUR_SIDES && end)) return Role.CENTER;
            result = Role.SIDE;
        }
        return result;
    }

    /** Clip work, never the original geometry used to identify the center and end walls. */
    private PrinterBox selectionBounds() {
        int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
        Direction.Axis axis = Direction.Axis.Y;
        SelectionType type = (SelectionType) SELECTION_TYPE.getOptionListValue();
        if (type == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) max = (int) Math.floor(player.getY());
        else if (type == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) min = (int) Math.ceil(player.getY());
        else if (type == SelectionType.LITEMATICA_RENDER_LAYER) {
            var layer = DataManager.getRenderLayerRange();
            axis = layer.getAxis();
            switch (layer.getLayerMode()) {
                case SINGLE_LAYER -> { min = layer.getLayerSingle(); max = min; }
                case LAYER_RANGE -> { min = layer.getLayerMin(); max = layer.getLayerMax(); }
                case ALL_BELOW -> max = layer.getLayerBelow();
                case ALL_ABOVE -> min = layer.getLayerAbove();
                default -> { }
            }
        }
        return new PrinterBox(axis == Direction.Axis.X ? min : Integer.MIN_VALUE,
                axis == Direction.Axis.Y ? min : Integer.MIN_VALUE, axis == Direction.Axis.Z ? min : Integer.MIN_VALUE,
                axis == Direction.Axis.X ? max : Integer.MAX_VALUE, axis == Direction.Axis.Y ? max : Integer.MAX_VALUE,
                axis == Direction.Axis.Z ? max : Integer.MAX_VALUE);
    }

    private boolean targetFluid(FluidState state) {
        return filler.matches(state) && (INCLUDE_FLOWING.getBooleanValue() || state.isSource());
    }

    private boolean miningAllowed(BlockState state) {
        return Mine.mineRestriction(state, MINING_LIMITER.getOptionListValue(),
                MINING_LIMIT.getOptionListValue(), MINING_WHITELIST.getStrings(),
                MINING_BLACKLIST.getStrings());
    }

    /** Only server block update callbacks acknowledge pending actions, never local prediction. */
    public void onBlockUpdate(ClientLevel world, BlockPos pos, BlockState state) {
        Pending task = pending.get(pos);
        if (world == contextLevel && task != null) task.confirmed = state;
    }

    /** A reloaded chunk also supplies authoritative state for work paused out of view. */
    public void onChunkUpdate(ClientLevel world, int chunkX, int chunkZ) {
        if (world != contextLevel) return;
        pending.forEach((pos, task) -> {
            if ((pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ) {
                task.confirmed = world.getBlockState(pos);
            }
        });
    }

    private void reconcile(BlockPos pos) {
        Pending task = pending.get(pos);
        if (task == null || !level.hasChunkAt(pos)) return;
        BlockState state = level.getBlockState(pos);
        // Server updates can arrive before vanilla retires a local block prediction.
        if (!state.equals(task.confirmed)) return;
        boolean fluid = filler.matches(state.getFluidState()), replaceable = BlockUtils.isReplaceable(state);
        long age = ModuleManager.getCurrentHandlerTime() - task.sentAt;
        // Centers may finish in air; side repairs require a settled, server-confirmed seal.
        boolean complete = role(pos) == Role.CENTER && state.isAir() && !fluid;
        if (task.kind == Kind.BREAK_CENTER) {
            // A dry rejected attempt can retry without blocking neighboring excavations.
            complete |= state.isAir() || (state.getFluidState().isEmpty() && !pos.equals(activeBreak) && age >= RETRY_TICKS);
        } else {
            complete |= !fluid && !replaceable && age >= Math.max(2, ConfigUtils.getPlaceCooldown())
                    && (!(state.getBlock() instanceof FallingBlock) || (level.hasChunkAt(pos.below())
                    && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)));
        }
        if (complete) pending.remove(pos);
        else if (task.kind == Kind.BREAK_FLUID && replaceable) task.repair = role(pos) == Role.SIDE;
    }

    @Override
    public void tick() {
        if (!ENABLED.getBooleanValue() || !ConfigUtils.isPrinterEnable()
                || level == null || player == null || connection == null || gameMode == null) return;
        if (iteratorManager.tryBuildBox(player, SELECTION_TYPE.getOptionListValue())) {
            box.set(iteratorManager.getBox());
            iteratorManager.reset();
        }
        yieldTick = false;
        long cutoff = ModuleManager.getCurrentHandlerTime();
        if (cutoff > statusUntil) status = "idle";
        long highlightCutoff = System.currentTimeMillis() - Configs.Highlight.HIGHLIGHT_FADE_DURATION.getIntegerValue() * 100L;
        getPendingHighlights().removeIf(h -> h.time() < highlightCutoff);
        if (activeBreak != null) {
            process(activeBreak, false);
            if (busy()) return;
        }
        int milliseconds = Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue();
        long deadline = milliseconds > 0 ? System.nanoTime() + milliseconds * 1_000_000L : Long.MAX_VALUE;
        while (System.nanoTime() < deadline) {
            BlockPos pos = iteratorManager.nextCandidate();
            if (pos == null) return;
            if (!canProcessPos(pos) || !iteratorManager.isWithinRange(pos)) continue;
            process(pos, false);
            if (busy()) return;
        }
    }

    private void report(String value) {
        status = value;
        statusUntil = ModuleManager.getCurrentHandlerTime() + 20;
    }

    public String getStatus() { return status; }
    private boolean busy() { return activeBreak != null || yieldTick || ActionManager.INSTANCE.needWaitModifyLook; }

    @Override
    public GuiBlockInfo getGuiInfo() { return info; }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return selectedBounds != null && selectedBounds.contains(pos) && role(pos) != Role.OUTSIDE;
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        if (level == null || !level.hasChunkAt(pos) || pending.containsKey(pos)) return false;
        Role role = role(pos);
        return role == Role.CENTER ? level.getBlockState(pos).isAir()
                : role == Role.SIDE && !targetFluid(level.getBlockState(pos).getFluidState());
    }

    private boolean dependenciesReady(BlockPos pos) {
        boolean ready = true;
        for (Direction direction : INLETS) {
            BlockPos neighbor = pos.relative(direction);
            // Only selected positions participate in drainage dependencies. Outside
            // fluid is handled if it subsequently flows into the selection.
            if (!canProcessPos(neighbor)) continue;
            if (neighbor.getY() < level.getMinY() || neighbor.getY() > level.getMaxY()) continue;
            if (!level.hasChunkAt(neighbor)) {
                ready = false;
                continue;
            }
            reconcile(neighbor);
            if (targetFluid(level.getBlockState(neighbor).getFluidState()) || pending.containsKey(neighbor)) {
                ready = false;
                stopActive(pos);
                if (iteratorManager.isWithinRange(neighbor)) process(neighbor, true);
                if (busy()) return false;
            }
        }
        return ready;
    }

    private void stopActive(BlockPos pos) {
        if (pos.equals(activeBreak)) {
            BreakUtils.INSTANCE.cancelAt(pos);
            activeBreak = null;
        }
    }

    private void process(BlockPos pos, boolean drainOnly) {
        Role role = role(pos);
        if (!canProcessPos(pos) || !level.hasChunkAt(pos) || !iteratorManager.isWithinRange(pos)) {
            stopActive(pos);
            report("blocked");
            return;
        }
        reconcile(pos);
        BlockState state = level.getBlockState(pos);
        boolean fluid = targetFluid(state.getFluidState()), air = state.isAir(), replaceable = BlockUtils.isReplaceable(state);
        Pending task = pending.get(pos);
        // World updates can finish or replace a progressive target between our ticks.
        // Release only the mining interaction; confirmation and repair remain in pending.
        if (task == null || task.kind == Kind.FILL || air || replaceable
                || (role == Role.SIDE && !fluid)) stopActive(pos);
        long now = ModuleManager.getCurrentHandlerTime();
        boolean repair = role == Role.SIDE && task != null && task.repair;
        boolean waiting = task != null && task.waiting(state, fluid, replaceable, pos.equals(activeBreak), now);
        if (waiting || isOnCooldown(pos)) {
            report("waiting");
            return;
        }
        if (drainOnly && !fluid && !repair) return;
        if (role == Role.CENTER && !air && !fluid && !repair && !dependenciesReady(pos)) {
            stopActive(pos);
            report("blocked");
            return;
        }
        boolean canBreak = BreakUtils.canBreakBlock(pos) && miningAllowed(state);
        // Avoid selecting filling material on dry excavation steps or while no budget is available.
        boolean needsMaterial = fluid || repair;
        boolean placing = needsMaterial && replaceable;
        Budget budget = placing ? placeBudget : breakBudget;
        int interval = placing ? Configs.Placement.PLACE_INTERVAL.getIntegerValue() : Configs.Break.BREAK_INTERVAL.getIntegerValue();
        int limit = placing ? Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue() : Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        if (!budget.available(now, interval, limit)) return;
        boolean material = !needsMaterial || filler.selectMaterial(player);
        boolean breaking = !placing && (fluid || (role == Role.CENTER && !air));
        boolean executed = material && (placing || (breaking && canBreak));
        if (executed) {
            // The shared filler clicks the upper neighbor unless air placement is enabled.
            if (placing && !Configs.Print.PLACE_IN_AIR.getBooleanValue()
                    && (!level.hasChunkAt(pos.above()) || !BlockUtils.canBeClicked(level, pos.above())
                    || BlockUtils.isReplaceable(level.getBlockState(pos.above())))) {
                report("blocked");
                return;
            }
            if (placing || !pos.equals(activeBreak)) {
                pending.put(pos.immutable(), new Pending(placing ? Kind.FILL : fluid ? Kind.BREAK_FLUID : Kind.BREAK_CENTER,
                        now, needsMaterial && role == Role.SIDE));
            }
            budget.consume(now);
            if (placing) {
                filler.place(pos, player);
                setCooldown(pos, ConfigUtils.getPlaceCooldown());
            } else {
                BlockBreakResult result = BreakUtils.INSTANCE.continueDestroyBlock(pos);
                if (result == BlockBreakResult.COMPLETED_WAIT) yieldTick = true;
                if (result == BlockBreakResult.IN_PROGRESS) activeBreak = pos.immutable();
                else {
                    BreakUtils.INSTANCE.cancelAt(pos);
                    activeBreak = null;
                    if (result != BlockBreakResult.FAILED) setCooldown(pos, ConfigUtils.getBreakCooldown());
                    else if (!needsMaterial) pending.remove(pos);
                }
            }
            addHighlight(pos, placing ? HighlightType.PLACE : HighlightType.BREAK);
            report("waiting");
        } else {
            stopActive(pos);
            if (needsMaterial || (role == Role.CENTER && !air)) report(material ? "blocked" : "material");
        }
        info = new GuiBlockInfo(pos, state, LitematicaUtils.getBlockState(pos), PlayerUtils.canInteracted(pos), executed, true);
    }

    private static final class Pending {
        final Kind kind;
        final long sentAt;
        boolean repair;
        BlockState confirmed;
        Pending(Kind kind, long sentAt, boolean repair) { this.kind = kind; this.sentAt = sentAt; this.repair = repair; }
        boolean waiting(BlockState state, boolean fluid, boolean replaceable, boolean active, long now) {
            boolean acknowledged = state.equals(confirmed);
            if (kind == Kind.BREAK_FLUID && acknowledged && replaceable) return false;
            return !active && (now - sentAt < RETRY_TICKS || (kind == Kind.FILL && !fluid && !replaceable)
                    || (kind == Kind.BREAK_CENTER && state.isAir() && !acknowledged));
        }
    }

    private static final class Budget {
        long tick = Long.MIN_VALUE, lastExecution = Long.MIN_VALUE;
        boolean intervalReady;
        int used;
        boolean available(long now, int interval, int limit) {
            if (now != tick) {
                tick = now;
                used = 0;
                intervalReady = lastExecution == Long.MIN_VALUE || now - lastExecution >= Math.max(0, interval);
            }
            return intervalReady && (limit <= 0 || used < limit);
        }
        void consume(long now) { used++; lastExecution = now; }
    }
}
