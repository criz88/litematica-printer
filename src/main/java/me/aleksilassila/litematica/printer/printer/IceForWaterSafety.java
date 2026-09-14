package me.aleksilassila.litematica.printer.printer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.*;

/** Checks the temporary source before the final waterlogged block is placed. */
public final class IceForWaterSafety {
    private static final int MAX_WATER_CELLS = 512;
    private static final Water SOURCE = new Water(8, true);
    private static final Water EMPTY = new Water(0, false);
    private static final Result SAFE = new Result(null, BlockPos.ZERO);

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

    public static Result check(Level level, BlockPos pos) {
        if (!loaded(level, pos)) return new Result(Failure.UNLOADED, pos);
        if (!loaded(level, pos.below())) return new Result(Failure.UNLOADED, pos.below());
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
        return supported ? new IceForWaterSafety(level, pos).run() : new Result(Failure.NO_SUPPORT, pos.below());
    }

    private static boolean loaded(Level level, BlockPos pos) {
        // ClientLevel.hasChunk always returns true; query the actual chunk cache.
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // Predict downward flow and nearest-drop routing, including new sources.
    // Future placements and packet timing are not assumed.
    private final Level level;
    private final BlockPos origin;
    private final Long2ObjectOpenHashMap<Water> water = new Long2ObjectOpenHashMap<>();
    private final Set<BlockPos> pending = new LinkedHashSet<>();
    // World geometry is fixed during this synchronous check; simulated water is not.
    private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap passages = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<int[]> slopes = new Long2ObjectOpenHashMap<>();
    private Result unavailable;

    private IceForWaterSafety(Level level, BlockPos origin) {
        this.level = level;
        this.origin = origin.immutable();
    }

    private void enqueue(BlockPos pos) {
        if (water.containsKey(pos.asLong())) pending.add(pos);
    }

    private BlockState state(BlockPos pos) {
        BlockState state = states.get(pos.asLong());
        if (state != null) return state;
        // Slope lookahead can read farther than the cells reached by water.
        if (!loaded(level, pos)) {
            if (unavailable == null) unavailable = new Result(Failure.UNLOADED, pos);
            state = Blocks.BARRIER.defaultBlockState();
        } else {
            // Only this ice is about to be broken; other ice remains a barrier.
            state = pos.equals(origin) ? Blocks.WATER.defaultBlockState() : level.getBlockState(pos);
        }
        states.put(pos.asLong(), state);
        return state;
    }

    private Water at(BlockPos pos) {
        Water simulated = water.get(pos.asLong());
        if (simulated != null) return simulated;
        var fluid = state(pos).getFluidState();
        return fluid.is(FluidTags.WATER) ? new Water(fluid.getAmount(), fluid.isSource()) : EMPTY;
    }

    private boolean open(BlockPos from, BlockPos to, Direction side) {
        // Low six bits mark checked faces; high six bits mark open faces.
        int bit = 1 << side.ordinal(), cached = passages.get(from.asLong());
        if ((cached & bit) == 0) {
            boolean open = !Shapes.mergedFaceOccludes(state(from).getCollisionShape(level, from),
                    state(to).getCollisionShape(level, to), side);
            cached |= bit | (open ? bit << 6 : 0);
            passages.put(from.asLong(), cached);
            int reverse = 1 << side.getOpposite().ordinal();
            passages.put(to.asLong(), passages.get(to.asLong()) | reverse | (open ? reverse << 6 : 0));
        }
        return (cached & (bit << 6)) != 0;
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
        water.put(pos.asLong(), value);
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
                && canHoldFluid(next, state(next), fluid);
    }

    private boolean isHole(BlockPos pos) {
        BlockPos below = pos.below();
        return open(pos, below, Direction.DOWN) && (at(below).amount() > 0
                || canHoldFluid(below, state(below), Fluids.FLOWING_WATER));
    }

    // Slots 0..5 cache flowing-water entry, 6 caches downward flow, 8..29 cache distances.
    private int[] routeData(BlockPos pos) {
        return slopes.computeIfAbsent(pos.asLong(), key -> new int[30]);
    }

    private boolean isSlopeHole(BlockPos pos) {
        int[] data = routeData(pos);
        if (data[6] == 0) data[6] = isHole(pos) ? 2 : 1;
        return data[6] == 2;
    }

    // Vanilla searches up to four steps beyond the first horizontal outlet.
    private int slopeDistance(BlockPos pos, Direction back, int distance) {
        int[] distances = routeData(pos);
        int key = distance * 6 + back.ordinal();
        if (distances[key] != 0) return distances[key] - 1;
        int best = 1000;
        for (Direction side : BlockUtils.horizontalDirections) {
            if (side == back) continue;
            int face = side.ordinal();
            if (distances[face] == 0) distances[face] = canEnter(pos, side, Fluids.FLOWING_WATER) ? 2 : 1;
            if (distances[face] == 1) continue;
            BlockPos next = pos.relative(side);
            if (isSlopeHole(next)) {
                best = distance;
                break;
            }
            if (distance < 4) best = Math.min(best, slopeDistance(next, side.getOpposite(), distance + 1));
        }
        distances[key] = best + 1; // Zero means unknown, including for dead ends (1000).
        return best;
    }

    private Set<Direction> outlets(BlockPos pos, Water from) {
        // Route searches share a fixed simulated state only within this call.
        slopes.clear();
        Set<Direction> sides = EnumSet.noneOf(Direction.class);
        BlockPos below = pos.below();
        boolean hole = isHole(pos);
        boolean falls = at(below).amount() == 0 && canEnter(pos, Direction.DOWN,
                becomesSource(below) ? Fluids.WATER : Fluids.FLOWING_WATER);
        int neighbours = 0;
        for (Direction side : BlockUtils.horizontalDirections) if (at(pos.relative(side)).source()) neighbours++;
        // Falling streams spread sideways only with three neighbouring sources.
        if (from.amount() <= 1 || (falls ? neighbours < 3 : !from.source() && hole))
            return falls || hole ? EnumSet.of(Direction.DOWN) : sides;
        int best = 1000;
        for (Direction side : BlockUtils.horizontalDirections) {
            BlockPos next = pos.relative(side);
            if (!canEnter(pos, side, becomesSource(next) ? Fluids.WATER : Fluids.FLOWING_WATER)) continue;
            int distance = isSlopeHole(next) ? 0 : slopeDistance(next, side.getOpposite(), 1);
            if (distance < best) sides.clear();
            if (distance <= best) {
                sides.add(side);
                best = distance;
            }
        }
        if (falls || hole) sides.add(Direction.DOWN);
        return sides;
    }

    private Result run() {
        update(origin, SOURCE);
        while (!pending.isEmpty()) {
            BlockPos pos = pending.iterator().next();
            pending.remove(pos);
            Water from = water.get(pos.asLong());
            if (!from.source() && becomesSource(pos)) update(pos, from = SOURCE);
            Set<Direction> outlets = outlets(pos, from);
            if (unavailable != null) return unavailable;
            for (Direction side : outlets) {
                int amount = side == Direction.DOWN ? 8 : from.amount() - 1;
                BlockPos next = pos.relative(side);
                // Water exits at the world bottom; do not invent cells or a floor below it.
                if (level.isOutsideBuildHeight(next) || !open(pos, next, side)) continue;
                BlockState current = state(next);
                Water existing = at(next);
                // Incoming water cannot change an existing source. Existing sources still
                // contribute to nearby source conversion without flood-filling the lake.
                if (existing.source()) continue;
                boolean source = becomesSource(next);
                if (unavailable != null) return unavailable;
                boolean wet = current.getFluidState().is(FluidTags.WATER);
                if (!wet && !canHoldFluid(next, current, source ? Fluids.WATER : Fluids.FLOWING_WATER)) continue;
                if (!wet && !current.isAir() && !(current.getBlock() instanceof LiquidBlockContainer))
                    return new Result(Failure.OBSTRUCTED, next);
                Water reached = source ? SOURCE : new Water(Math.max(amount, existing.amount()), false);
                if (reached.equals(water.get(next.asLong()))) continue;
                update(next, reached);
                if (water.size() > MAX_WATER_CELLS) return new Result(Failure.LIMIT, next);
            }
        }
        return SAFE;
    }

    // Containers distinguish WATER from FLOWING_WATER; collision shape alone is insufficient.
    private boolean canHoldFluid(BlockPos pos, BlockState state, Fluid fluid) {
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
