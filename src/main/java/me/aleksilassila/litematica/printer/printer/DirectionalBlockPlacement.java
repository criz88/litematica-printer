package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import me.aleksilassila.litematica.printer.printer.PlacementGuide.ClassHook;

/** Direction, support face and neighbor-sensitive placement rules. */
final class DirectionalBlockPlacement {
    private DirectionalBlockPlacement() {}

    static @Nullable Action getAction(SchematicBlockContext ctx, ClassHook requiredType) {
        switch (requiredType) {
            case TORCH -> {
                Direction lookDirection = ctx.getRequiredStateProperty(WallTorchBlock.FACING).orElse(Direction.UP).getOpposite();
                return new Action().setSides(lookDirection).setLookDirection(lookDirection).setRequiresSupport();
            }
            case AMETHYST -> {
                Direction lookDirection = ctx.getRequiredStateProperty(AmethystClusterBlock.FACING).orElse(Direction.UP).getOpposite();
                return new Action().setSides(lookDirection).setRequiresSupport();
            }
            case STAIR -> {
                Direction facing = ctx.requiredState.getValue(StairBlock.FACING);
                Half half = ctx.requiredState.getValue(StairBlock.HALF);
                Map<Direction, Vec3> sides = new HashMap<>();
                if (half == Half.BOTTOM) {
                    sides.put(Direction.DOWN, new Vec3(0, 0, 0));
                    sides.put(facing, new Vec3(0, 0, 0));
                } else {
                    sides.put(Direction.UP, new Vec3(0, 0.75, 0));
                    sides.put(facing.getOpposite(), new Vec3(0, 0.75, 0));
                }
                return new Action().setSides(sides).setLookDirection(facing);
            }
            case TRAPDOOR -> {
                Half half = ctx.requiredState.getValue(TrapDoorBlock.HALF);
                Direction side = half == Half.TOP ? Direction.UP : Direction.DOWN;
                Direction facing = ctx.requiredState.getValue(TrapDoorBlock.FACING);
                return new Action()
                        .setSides(side)
                        .setLookDirection(facing.getOpposite());
            }
            case ANVIL -> {
                return new Action().setLookDirection(ctx.requiredState.getValue(AnvilBlock.FACING).getCounterClockWise());
            }
            case HOPPER -> {
                Direction facing = ctx.requiredState.getValue(HopperBlock.FACING);
                return new Action().setSides(facing);
            }
            case COCOA -> {
                return new Action().setSides(ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING));
            }
            //#if MC >= 12003
            case CRAFTER -> {
                FrontAndTop frontAndTop = ctx.requiredState.getValue(BlockStateProperties.ORIENTATION);
                Direction facing = frontAndTop.front().getOpposite();
                Direction rotation = frontAndTop.top().getOpposite();
                if (facing == Direction.UP) {
                    return new Action().setLookDirection(rotation, Direction.UP).setNeedWaitModifyLook(true);
                } else if (facing == Direction.DOWN) {
                    return new Action().setLookDirection(rotation.getOpposite(), Direction.DOWN).setNeedWaitModifyLook(true);
                } else {
                    return new Action().setLookDirection(facing, facing).setNeedWaitModifyLook(true);
                }
            }
            //#endif
            case OBSERVER -> {
                @Nullable
                Direction facing = ctx.getRequiredStateProperty(ObserverBlock.FACING).orElse(null);
                if (facing == null) {
                    return null;
                }

                SchematicBlockContext input = ctx.offset(facing);
                SchematicBlockContext output = ctx.offset(facing.getOpposite());

                if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
                    List<Property<?>> inputPropertiesToIgnore = new ArrayList<>();
                    if (input.requiredState.getBlock() instanceof WallBlock) {
                        BlockUtils.getWallFacingProperty(facing.getOpposite())
                                .ifPresent(inputPropertiesToIgnore::add);
                    }
                    if (output.requiredState.getBlock() instanceof CrossCollisionBlock) {
                        BlockUtils.getCrossCollisionBlock(facing.getOpposite())
                                .ifPresent(inputPropertiesToIgnore::add);
                    }

                    BlockMatchingType inputState = BlockMatchingType.get(input, inputPropertiesToIgnore.toArray(new Property<?>[0]));
                    BlockMatchingType outputState = BlockMatchingType.get(output);

                    if (inputState == BlockMatchingType.CORRECT && outputState == BlockMatchingType.CORRECT) {
                        if (BlockUtils.checkObserverChain(input)) {
                            return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
                        }
                        return null;
                    }

                    if (inputState == BlockMatchingType.CORRECT) {
                        SchematicBlockContext temp = input;
                        while (temp.requiredState.getBlock() instanceof FallingBlock) {
                            SchematicBlockContext offset = temp.offset(Direction.DOWN);
                            if (BlockMatchingType.get(offset) != BlockMatchingType.CORRECT) {
                                return null;
                            }
                            temp = offset;
                        }

                        if (!output.requiredState.isAir() && !BlockUtils.checkObserverChain(input)) {
                            return null;
                        }

                        for (Direction d : Direction.values()) {
                            SchematicBlockContext offset = output.offset(d);
                            if (offset.blockPos.equals(output.blockPos) || offset.blockPos.equals(input.blockPos) || offset.blockPos.equals(ctx.blockPos)) {
                                continue;
                            }
                            if (offset.requiredState.getBlock() instanceof PistonBaseBlock && !offset.currentState.isAir()) {
                                return null;
                            }
                        }

                    } else if (inputState == BlockMatchingType.ERROR_BLOCK_STATE) {
                        return null;
                    } else {
                        if (!output.requiredState.isAir()) {
                            if (output.currentState.isAir() && input.requiredState.getBlock() instanceof WallBlock) {
                                BlockPosCooldownManager.INSTANCE.setCooldown(ctx.level, "observer", ctx.blockPos, 2);
                                return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
                            }
                            return null;
                        } else {
                            // 检查是否被其他侦测器侦测
                            if (BlockUtils.checkObserverChain(input)) {
                                return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
                            }
                            if (!BlockUtils.checkObserverChain(output)) {
                                return null;
                            }
                        }
                    }
                }

                return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
            }
            case LADDER -> {
                Direction facing = ctx.requiredState.getValue(LadderBlock.FACING);
                return new Action()
                        .setSides(facing)
                        .setLookDirection(facing.getOpposite())
                        .setNeedWaitModifyLook();
            }
            case LANTERN -> {
                if (ctx.requiredState.getValue(LanternBlock.HANGING))
                    return new Action().setLookDirection(Direction.UP);
                return new Action().setLookDirection(Direction.DOWN);
            }
            case ROD -> {
                Block requiredBlock = ctx.requiredState.getBlock();
                Direction facing = ctx.requiredState.getValue(EndRodBlock.FACING);

                // 如果前面朝向自己的末地烛，而放置方式相反，那么反向放置
                if (requiredBlock instanceof EndRodBlock) {
                    BlockState forwardState = ctx.level.getBlockState(ctx.blockPos.relative(facing));
                    BlockState forwardStateSchematic = ctx.level.getBlockState(ctx.blockPos.relative(facing));
                    if (forwardState.is(requiredBlock) && forwardState.getValue(EndRodBlock.FACING) == facing.getOpposite()) {
                        return new Action().setSides(facing);
                    }
                    // 如果投影中后面有相同朝向的末地烛，则先跳过放置
                    if (forwardStateSchematic.is(requiredBlock) && forwardStateSchematic.getValue(EndRodBlock.FACING) == facing) {
                        // 但是这个投影已经被正确填装时可以打印
                        if (forwardStateSchematic == forwardState) return new Action().setSides(facing.getOpposite());
                        return null;
                    }
                }
                return new Action().setSides(facing.getOpposite());
            }
            case TRIPWIRE_HOOK -> {
                Direction facing = ctx.requiredState.getValue(TripWireHookBlock.FACING);
                return new Action().setSides(facing);
            }
            case RAIL -> {
                Action action = new Action();
                RailShape shape;
                if (ctx.requiredState.getBlock() instanceof RailBlock)
                    shape = ctx.requiredState.getValue(RailBlock.SHAPE);
                else shape = ctx.requiredState.getValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT);

                switch (shape) {
                    case EAST_WEST, ASCENDING_EAST -> action.setLookDirection(Direction.EAST);
                    case NORTH_SOUTH, ASCENDING_NORTH -> action.setLookDirection(Direction.NORTH);
                    case ASCENDING_WEST -> action.setLookDirection(Direction.WEST);
                    case ASCENDING_SOUTH -> action.setLookDirection(Direction.SOUTH);
                }
                if (ctx.requiredState.getBlock() instanceof RailBlock) {
                    if (shape == RailShape.SOUTH_EAST) {
                        return action;
                    }
                    // TODO)) 完成这非常恶心的铁轨算法
                }
                return action;
            }
            case PISTON -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.FACING);
                // 侦测器安全放置
                if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
                    // 活塞四周
                    for (Direction direction : Direction.values()) {
                        SchematicBlockContext temp = ctx.offset(direction);
                        while (temp.requiredState.getBlock() instanceof ObserverBlock) {
                            @Nullable Direction tempObserverFacing = temp.getRequiredStateProperty(ObserverBlock.FACING).orElse(null);
                            if (tempObserverFacing != null) {
                                SchematicBlockContext offset = temp.offset(tempObserverFacing);
                                if (tempObserverFacing == direction) {
                                    if (BlockMatchingType.get(offset) != BlockMatchingType.CORRECT) {
                                        return null;
                                    }
                                }
                                temp = offset;
                            }
                        }
                    }

                }
                return new Action().setLookDirection(facing.getOpposite()).setNeedWaitModifyLook();
            }
            default -> {}
        }
        return null;
    }
}
