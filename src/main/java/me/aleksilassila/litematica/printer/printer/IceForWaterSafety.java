package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
//#if MC >= 12111
import net.minecraft.world.attribute.EnvironmentAttributes;
//#endif
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Checks the temporary source before the final waterlogged block is placed. */
public final class IceForWaterSafety {
    private static final int MAX_WATER_CELLS = 512;
    private static final Water SOURCE = new Water(8, true);
    private static final Water EMPTY = new Water(0, false);
    private static final Result SAFE = new Result(null, BlockPos.ZERO);

    private IceForWaterSafety() {}

    public enum Failure { UNLOADED, EVAPORATES, NO_SUPPORT, LIMIT, OBSTRUCTED }

    public record Result(Failure failure, BlockPos pos) {
        public boolean safe() { return failure == null; }

        public Component message(Level level) {
            I18n message = I18n.of("ice.water_" + failure.name().toLowerCase(Locale.ROOT));
            return failure == Failure.OBSTRUCTED
                    ? message.getName(pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos).getBlock().getName())
                    : message.getName(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private record Water(int amount, boolean source) {}

    public static Result check(Level level, BlockPos origin) {
        Result generation = checkGeneration(level, origin);
        return generation.safe() ? new FlowCheck(level, origin).run() : generation;
    }

    private static Result checkGeneration(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return new Result(Failure.UNLOADED, pos);
        if (!level.hasChunkAt(pos.below())) return new Result(Failure.UNLOADED, pos.below());
        //#if MC >= 12111
        if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)) {
        //#else
        //$$ if (level.dimensionType().ultraWarm()) {
        //#endif
            return new Result(Failure.EVAPORATES, pos);
        }
        BlockState below = level.getBlockState(pos.below());
        // Match IceBlock.playerDestroy, including water as valid support.
        //#if MC >= 12000
        boolean supported = below.blocksMotion() || below.liquid();
        //#else
        //$$ boolean supported = below.getMaterial().blocksMotion() || below.getMaterial().isLiquid();
        //#endif
        return supported ? SAFE : new Result(Failure.NO_SUPPORT, pos.below());
    }

    /**
     * Monotone upper bound on water spread: preserve existing sources, decrease horizontal
     * strength, reset falling water to 8, and allow new sources with two source neighbours.
     * Follow downward flow and nearest-drop routing before checking the selected outlets.
     * No speculative final blocks or packet timing are trusted.
     */
    private static final class FlowCheck {
        private final Level level;
        private final BlockPos origin;
        private final Map<BlockPos, Water> water = new HashMap<>();
        private final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        private final Set<BlockPos> queued = new HashSet<>();
        private Result unavailable;

        private FlowCheck(Level level, BlockPos origin) {
            this.level = level;
            this.origin = origin.immutable();
        }

        private void enqueue(BlockPos pos) {
            if (water.containsKey(pos) && queued.add(pos)) pending.addLast(pos);
        }

        private BlockState state(BlockPos pos) {
            // Slope lookahead can read farther than the cells reached by water.
            if (!level.hasChunkAt(pos)) {
                if (unavailable == null) unavailable = new Result(Failure.UNLOADED, pos);
                return Blocks.BARRIER.defaultBlockState();
            }
            // Only this ice is about to be broken; other ice remains a barrier.
            return pos.equals(origin) ? Blocks.WATER.defaultBlockState() : level.getBlockState(pos);
        }

        private Water at(BlockPos pos) {
            Water simulated = water.get(pos);
            if (simulated != null) return simulated;
            var fluid = state(pos).getFluidState();
            return fluid.is(FluidTags.WATER) ? new Water(fluid.getAmount(), fluid.isSource()) : EMPTY;
        }

        private boolean open(BlockPos from, BlockPos to, Direction side) {
            return !Shapes.mergedFaceOccludes(state(from).getCollisionShape(level, from),
                    state(to).getCollisionShape(level, to), side);
        }

        private boolean becomesSource(BlockPos pos) {
            BlockPos belowPos = pos.below();
            BlockState below = state(belowPos);
            //#if MC >= 12000
            boolean supported = below.isSolid();
            //#else
            //$$ boolean supported = below.getMaterial().isSolid();
            //#endif
            if (!supported && !at(belowPos).source()) return false;
            int sources = 0;
            for (Direction side : BlockUtils.horizontalDirections) {
                BlockPos neighbour = pos.relative(side);
                if (at(neighbour).source() && open(neighbour, pos, side.getOpposite()) && ++sources >= 2) return true;
            }
            return false;
        }

        private void update(BlockPos pos, Water value) {
            water.put(pos, value);
            enqueue(pos);
            // New source neighbours or new support can enable source conversion on a later pass.
            if (value.source()) {
                for (Direction side : Direction.values()) enqueue(pos.relative(side));
                for (Direction side : BlockUtils.horizontalDirections) enqueue(pos.above().relative(side));
            }
        }

        private boolean canEnter(BlockPos pos, Direction side, Fluid fluid) {
            BlockPos next = pos.relative(side);
            return !at(next).source() && open(pos, next, side)
                    && canHoldFluid(level, next, state(next), fluid);
        }

        private boolean isHole(BlockPos pos) {
            BlockPos below = pos.below();
            return open(pos, below, Direction.DOWN) && (at(below).amount() > 0
                    || canHoldFluid(level, below, state(below), Fluids.FLOWING_WATER));
        }

        // Vanilla searches up to four steps beyond the first horizontal outlet.
        private int slopeDistance(BlockPos pos, Direction back, int distance) {
            int best = 1000;
            for (Direction side : BlockUtils.horizontalDirections) {
                if (side == back || !canEnter(pos, side, Fluids.FLOWING_WATER)) continue;
                BlockPos next = pos.relative(side);
                if (isHole(next)) return distance;
                if (distance < 4) best = Math.min(best, slopeDistance(next, side.getOpposite(), distance + 1));
            }
            return best;
        }

        private Set<Direction> outlets(BlockPos pos, Water from) {
            Set<Direction> sides = EnumSet.noneOf(Direction.class);
            BlockPos below = pos.below();
            boolean hole = isHole(pos);
            boolean falls = at(below).amount() == 0 && canEnter(pos, Direction.DOWN,
                    becomesSource(below) ? Fluids.WATER : Fluids.FLOWING_WATER);
            if (falls || hole) sides.add(Direction.DOWN);
            int neighbours = 0;
            for (Direction side : BlockUtils.horizontalDirections) if (at(pos.relative(side)).source()) neighbours++;
            // Falling streams do not fan out on every vertical step. A source above
            // existing water can still spread sideways; three neighbouring sources
            // also permit lateral spread while filling an empty cell below.
            if (falls ? neighbours < 3 : !from.source() && hole) return sides;
            if (from.amount() <= 1) return sides;
            Set<Direction> horizontal = EnumSet.noneOf(Direction.class);
            int best = 1000;
            for (Direction side : BlockUtils.horizontalDirections) {
                BlockPos next = pos.relative(side);
                if (!canEnter(pos, side, becomesSource(next) ? Fluids.WATER : Fluids.FLOWING_WATER)) continue;
                int distance = isHole(next) ? 0 : slopeDistance(next, side.getOpposite(), 1);
                if (distance < best) horizontal.clear();
                if (distance <= best) {
                    horizontal.add(side);
                    best = distance;
                }
            }
            sides.addAll(horizontal);
            return sides;
        }

        private Result run() {
            update(origin, SOURCE);
            while (!pending.isEmpty()) {
                BlockPos pos = pending.removeFirst();
                queued.remove(pos);
                Water from = water.get(pos);
                if (!from.source() && becomesSource(pos)) {
                    from = SOURCE;
                    update(pos, from);
                }
                Set<Direction> outlets = outlets(pos, from);
                if (unavailable != null) return unavailable;
                for (Direction side : outlets) {
                    int amount = side == Direction.DOWN ? 8 : from.amount() - 1;
                    if (amount <= 0) continue;
                    BlockPos next = pos.relative(side);
                    // Water exits at the world bottom; do not invent cells or a floor below it.
                    if (level.isOutsideBuildHeight(next)) continue;
                    if (!open(pos, next, side)) continue;
                    BlockState current = state(next);
                    Water existing = at(next);
                    // Incoming water cannot change an existing source. Existing sources still
                    // contribute to nearby source conversion without flood-filling the lake.
                    if (existing.source()) continue;
                    boolean source = becomesSource(next);
                    if (unavailable != null) return unavailable;
                    Fluid incoming = source ? Fluids.WATER : Fluids.FLOWING_WATER;
                    // A dry rail/stair/chest normally rejects FLOWING_WATER. It is not an
                    // outlet merely because it has a partial/empty collision shape.
                    if (!current.getFluidState().is(FluidTags.WATER)
                            && !canHoldFluid(level, next, current, incoming)) continue;
                    // Protect existing blocks regardless of what the schematic requests.
                    if (!current.isAir() && !current.getFluidState().is(FluidTags.WATER)
                            && !(current.getBlock() instanceof LiquidBlockContainer)) {
                        return new Result(Failure.OBSTRUCTED, next);
                    }
                    Water reached = source ? SOURCE : new Water(Math.max(amount, existing.amount()), false);
                    Water previous = water.get(next);
                    if (!reached.equals(previous)) {
                        update(next, reached);
                        if (water.size() > MAX_WATER_CELLS) return new Result(Failure.LIMIT, next);
                    }
                }
            }
            return SAFE;
        }
    }

    // Mirrors FlowingFluid's material/container checks. canPlaceLiquid must receive the
    // actual incoming fluid type: WATER and FLOWING_WATER are different for waterlogging.
    private static boolean canHoldFluid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        if (state.getBlock() instanceof LiquidBlockContainer container) {
            //#if MC >= 12002
            return container.canPlaceLiquid(null, level, pos, state, fluid);
            //#else
            //$$ return container.canPlaceLiquid(level, pos, state, fluid);
            //#endif
        }
        //#if MC >= 12000
        if (state.blocksMotion()) return false;
        //#else
        //$$ if (state.getMaterial().blocksMotion()) return false;
        //#endif
        return !(state.getBlock() instanceof DoorBlock) && !state.is(BlockTags.SIGNS)
                && !state.is(Blocks.LADDER) && !state.is(Blocks.SUGAR_CANE) && !state.is(Blocks.BUBBLE_COLUMN)
                && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL)
                && !state.is(Blocks.END_GATEWAY) && !state.is(Blocks.STRUCTURE_VOID);
    }
}
