package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.printer.FluidFiller;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.core.BlockPos;
import java.util.concurrent.atomic.AtomicReference;

public class FluidRemoval extends Module {
    public final static String NAME = "fluid";
    private final FluidFiller filler = new FluidFiller();

    public FluidRemoval() {
        super(NAME, Configs.Fluid.ENABLED, Configs.Fluid.FLUID_SELECTION_TYPE, true);
    }

    @Override
    protected int getTickInterval() { return Configs.Placement.PLACE_INTERVAL.getIntegerValue(); }

    @Override
    protected int getMaxExecutions() { return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue(); }

    @Override
    protected void preprocess() { filler.refresh(); }

    @Override
    protected boolean canIterate() { return filler.ready(); }

    @Override
    public boolean canProcessPos(BlockPos pos) { return filler.matches(level.getBlockState(pos).getFluidState()); }

    @Override
    public boolean isCorrectBlock(BlockPos pos) { return !canProcessPos(pos); }

    @Override
    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
        var fluid = level.getBlockState(pos).getFluidState();
        if (!filler.matches(fluid) || (!Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue() && !fluid.isSource())) return;
        if (!filler.selectMaterial(player)) return;
        addHighlight(pos, HighlightType.PLACE);
        if (filler.place(pos, player)) skipIteration.set(true);
        else setCooldown(pos, ConfigUtils.getPlaceCooldown());
    }
}
