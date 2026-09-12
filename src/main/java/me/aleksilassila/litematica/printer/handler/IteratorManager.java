package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;

/**
 * 迭代管理器 — 从 Module 中分离出的迭代相关逻辑。
 * 负责：PrinterBox 生命周期、迭代器缓存、形状过滤、范围裁剪。
 */
public class IteratorManager {
    private PrinterBox box;
    private Iterator<BlockPos> cachedIterator;
    private RadiusShapeType shapeType;
    private Vec3 eyePos;
    private double effectiveRange;

    private BlockPos lastEyePos;
    private int lastExpandRange = -1;
    private int lastWorldMinY = Integer.MIN_VALUE;
    private int lastWorldMaxY = Integer.MIN_VALUE;
    private int lastPlayerLayer = Integer.MIN_VALUE;
    private int lastLayerMin = Integer.MIN_VALUE;
    private int lastLayerMax = Integer.MIN_VALUE;
    private int lastLayerSingle = Integer.MIN_VALUE;
    private int lastLayerAbove = Integer.MIN_VALUE;
    private int lastLayerBelow = Integer.MIN_VALUE;
    @Nullable
    private Direction.Axis lastLayerAxis = null;
    @Nullable
    private LayerMode lastLayerMode = null;
    @Nullable
    private SelectionType lastSelectionType = null;
    @Nullable
    private PrinterBox lastBox;

    private boolean needsRebuild;
    private boolean dirtyIterator;

    public IteratorManager() {
        this.needsRebuild = true;
        this.dirtyIterator = true;
    }

    /**
     * 根据玩家位置和配置重建 PrinterBox，返回是否需要重置扫描状态。
     */
    public boolean tryBuildBox(LocalPlayer player, @Nullable Object selectionTypeObj) {
        BlockPos eyeBP = new BlockPos(new Vec3i(
                (int) Math.round(player.getX()),
                (int) Math.round(player.getEyeY()),
                (int) Math.round(player.getZ())));

        double effectiveRange = ConfigUtils.getEffectiveRange();
        int currentRange = (int) Math.ceil(effectiveRange);

        // 扫描区域可以复用，但可达性必须使用本次更新的位置和配置。
        this.eyePos = player.getEyePosition();
        this.effectiveRange = effectiveRange;
        this.shapeType = Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType s ? s : null;

        LayerRange layerRange = DataManager.getRenderLayerRange();
        LayerMode layerMode = layerRange.getLayerMode();
        Direction.Axis layerAxis = layerRange.getAxis();
        int layerMin = layerRange.getLayerMin();
        int layerMax = layerRange.getLayerMax();
        int layerSingle = layerRange.getLayerSingle();
        int layerAbove = layerRange.getLayerAbove();
        int layerBelow = layerRange.getLayerBelow();

        SelectionType selectionType = selectionTypeObj instanceof SelectionType s ? s : null;

        int worldMinY = PrinterBox.client.level != null ? PrinterBox.client.level.getMinY() : Integer.MIN_VALUE;
        int worldMaxY = PrinterBox.client.level != null ? PrinterBox.client.level.getMaxY() : Integer.MAX_VALUE;
        int playerLayer = selectionType == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER ? (int) Math.floor(player.getY())
                : selectionType == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER ? (int) Math.ceil(player.getY()) : 0;

        boolean needRebuild = needsRebuild || this.box == null
                || worldMinY != lastWorldMinY || worldMaxY != lastWorldMaxY
                || playerLayer != lastPlayerLayer
                || !this.box.equals(lastBox)
                || lastEyePos == null
                || !lastEyePos.closerThan(eyeBP, effectiveRange * 0.4)
                || lastExpandRange != currentRange
                || layerMin != lastLayerMin
                || layerMax != lastLayerMax
                || layerSingle != lastLayerSingle
                || layerAbove != lastLayerAbove
                || layerBelow != lastLayerBelow
                || layerAxis != lastLayerAxis
                || layerMode != lastLayerMode
                || selectionType != lastSelectionType;

        if (needRebuild) {
            lastEyePos = eyeBP;
            lastExpandRange = currentRange;
            lastWorldMinY = worldMinY;
            lastWorldMaxY = worldMaxY;
            lastPlayerLayer = playerLayer;
            lastLayerMin = layerMin;
            lastLayerMax = layerMax;
            lastLayerSingle = layerSingle;
            lastLayerAbove = layerAbove;
            lastLayerBelow = layerBelow;
            lastLayerAxis = layerAxis;
            lastLayerMode = layerMode;
            lastSelectionType = selectionType;

            int minX = (int) Math.floor(player.getX() - effectiveRange);
            int maxX = (int) Math.ceil(player.getX() + effectiveRange);
            int minY = (int) Math.floor(player.getEyeY() - effectiveRange);
            int maxY = (int) Math.ceil(player.getEyeY() + effectiveRange);
            int minZ = (int) Math.floor(player.getZ() - effectiveRange);
            int maxZ = (int) Math.ceil(player.getZ() + effectiveRange);

            // 只有可见层模式受渲染层限制；全部投影及玩家上下方保留各自范围。
            if (selectionType == SelectionType.LITEMATICA_RENDER_LAYER && layerMode != LayerMode.ALL) {
                switch (layerMode) {
                    case SINGLE_LAYER -> {
                        switch (layerAxis) {
                            case Y -> { minY = Math.max(minY, layerSingle); maxY = Math.min(maxY, layerSingle); }
                            case X -> { minX = Math.max(minX, layerSingle); maxX = Math.min(maxX, layerSingle); }
                            case Z -> { minZ = Math.max(minZ, layerSingle); maxZ = Math.min(maxZ, layerSingle); }
                        }
                    }
                    case LAYER_RANGE -> {
                        switch (layerAxis) {
                            case Y -> { minY = Math.max(minY, layerMin); maxY = Math.min(maxY, layerMax); }
                            case X -> { minX = Math.max(minX, layerMin); maxX = Math.min(maxX, layerMax); }
                            case Z -> { minZ = Math.max(minZ, layerMin); maxZ = Math.min(maxZ, layerMax); }
                        }
                    }
                    case ALL_BELOW -> {
                        switch (layerAxis) {
                            case Y -> maxY = Math.min(maxY, layerBelow);
                            case X -> maxX = Math.min(maxX, layerBelow);
                            case Z -> maxZ = Math.min(maxZ, layerBelow);
                        }
                    }
                    case ALL_ABOVE -> {
                        switch (layerAxis) {
                            case Y -> minY = Math.max(minY, layerAbove);
                            case X -> minX = Math.max(minX, layerAbove);
                            case Z -> minZ = Math.max(minZ, layerAbove);
                        }
                    }
                }
            }

            if (selectionType != null) {
                if (selectionType == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) {
                    maxY = Math.min(maxY, (int) Math.floor(player.getY()));
                } else if (selectionType == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
                    minY = Math.max(minY, (int) Math.ceil(player.getY()));
                }
            }

            box = new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
            lastBox = box;

            box.iterationMode = (IterationOrderType) Configs.Core.ITERATION_ORDER.getOptionListValue();
            box.xIncrement = !Configs.Core.X_REVERSE.getBooleanValue();
            box.yIncrement = !Configs.Core.Y_REVERSE.getBooleanValue();
            box.zIncrement = !Configs.Core.Z_REVERSE.getBooleanValue();

            cachedIterator = null;
            dirtyIterator = true;

            this.needsRebuild = false;
            return true;
        }

        this.needsRebuild = false;
        return false;
    }

    public void markNeedsRebuild() {
        this.needsRebuild = true;
    }

    public boolean isNeedsRebuild() {
        return needsRebuild;
    }

    public boolean isDirtyIterator() {
        return dirtyIterator;
    }

    /**
     * 单步获取原始候选位置；调用方须在有时间预算的循环中用 isWithinRange 过滤。
     * 返回 null 表示迭代结束。
     */
    @Nullable
    public BlockPos nextCandidate() {
        if (box == null) return null;

        if (cachedIterator == null) {
            cachedIterator = box.iterator();
            dirtyIterator = false;
        }

        if (cachedIterator.hasNext()) {
            return cachedIterator.next();
        }

        cachedIterator = null;
        return null;
    }

    /** 同时用于扫描候选和暂停后恢复的位置，避免越过当前工作范围。 */
    public boolean isWithinRange(BlockPos pos) {
        if (box == null || !box.contains(pos)) return false;
        return shapeType != null
                ? PlayerUtils.canInteracted(pos, eyePos, effectiveRange, shapeType)
                : PlayerUtils.canInteracted(pos);
    }

    public boolean hasNext() {
        if (box == null) return false;
        if (cachedIterator == null) {
            cachedIterator = box.iterator();
            dirtyIterator = false;
        }
        return cachedIterator.hasNext();
    }

    public void reset() {
        cachedIterator = null;
        dirtyIterator = true;
    }

    @Nullable
    public PrinterBox getBox() {
        return box;
    }

    public boolean hasBox() {
        return box != null;
    }

    /**
     * 使用脏区域迭代器替换当前迭代器（用于 PARTIAL 模式）。
     */
    public void setDirtyRegionIterator(Iterator<BlockPos> dirtyIter) {
        this.cachedIterator = dirtyIter;
        this.dirtyIterator = false;
    }
}
