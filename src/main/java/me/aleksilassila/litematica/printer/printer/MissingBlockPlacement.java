package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import me.aleksilassila.litematica.printer.printer.PlacementGuide.ClassHook;
import static me.aleksilassila.litematica.printer.printer.PlacementGuide.STRIPPED_LOGS;

/** Placement into missing or replaceable positions; dispatch order remains in PlacementGuide. */
final class MissingBlockPlacement {
    private MissingBlockPlacement() {}

    /*** 缺失方块：实际位置为空，或当前方块在可替换列表中且启用了替换功能 ***/
    static @Nullable Action getAction(SchematicBlockContext ctx, ClassHook requiredType) {
        switch (requiredType) {
            case CAULDRON -> {
                return ctx.requiredState.is(Blocks.CAULDRON) || Configs.Print.FILL_CAULDRONS.getBooleanValue()
                        ? new Action().setItem(Items.CAULDRON) : null;
            }
            case TORCH -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case AMETHYST -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case SLAB -> {
                Map<Direction, Vec3> slabSides = BlockUtils.getSlabSides(ctx.level, ctx.blockPos, ctx.requiredState.getValue(SlabBlock.TYPE));
                return new Action().setSides(slabSides);
            }
            case STAIR -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case TRAPDOOR -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case STRIP_LOG -> {
                Action action = new Action().setSides(ctx.requiredState.getValue(RotatedPillarBlock.AXIS));
                Item[] items = {ctx.requiredState.getBlock().asItem()};
                if (Configs.Print.STRIP_LOGS.getBooleanValue()) {
                    for (Map.Entry<Block, Block> entry : STRIPPED_LOGS.entrySet()) {
                        if (ctx.requiredState.getBlock() == entry.getValue()) {
                            items = new Item[]{entry.getValue().asItem(), entry.getKey().asItem()};
                            break;
                        }
                    }
                }
                action.setItems(items);
                return action;
            }
            case ANVIL -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case HOPPER -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case NETHER_PORTAL -> {
                boolean canCreatePortal = PortalShape.findEmptyPortalShape(ctx.level, ctx.blockPos, Direction.Axis.X).isPresent();
                if (canCreatePortal) {
                    return new Action().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                }
            }
            case COCOA -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            //#if MC >= 12003
            case CRAFTER -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            //#endif
            case CHEST -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
                ChestType type = ctx.requiredState.getValue(BlockStateProperties.CHEST_TYPE);
                Map<Direction, Vec3> noChestSides = new HashMap<>();

                for (Direction side : Direction.values()) {
                    if (ctx.level.getBlockState(ctx.blockPos.relative(side)).getBlock() instanceof ChestBlock) {
                        continue;
                    }
                    noChestSides.put(side, Vec3.ZERO);
                }

                if (type == ChestType.SINGLE) {
                    for (Direction side : BlockStateProperties.HORIZONTAL_FACING.getPossibleValues()) {
                        if (!noChestSides.containsKey(side)) {
                            return new Action().setLookDirection(facing).setShift();
                        }
                        return new Action().setSides(noChestSides).setLookDirection(facing);
                    }
                } else {
                    Direction chestFacing = facing;
                    if (type == ChestType.LEFT) {
                        chestFacing = facing.getCounterClockWise();
                    } else if (type == ChestType.RIGHT) {
                        chestFacing = facing.getClockWise();
                    }
                    if (ctx.level.getBlockState(ctx.blockPos.relative(chestFacing)).getBlock() instanceof ChestBlock) {
                        return new Action().setSides(Map.of(chestFacing, Vec3.ZERO)).setLookDirection(facing).setShift(false);
                    } else {
                        return new Action().setSides(noChestSides).setLookDirection(facing).setShift();
                    }
                }
            }
            case BED -> {
                if (ctx.requiredState.getValue(BedBlock.PART) == BedPart.FOOT)
                    return new Action().setLookDirection(ctx.requiredState.getValue(BedBlock.FACING));
            }
            case BELL -> {
                Direction side;
                switch (ctx.requiredState.getValue(BellBlock.ATTACHMENT)) {
                    case FLOOR -> side = Direction.DOWN;
                    case CEILING -> side = Direction.UP;
                    default -> side = ctx.requiredState.getValue(BellBlock.FACING);
                }

                Direction look = ctx.requiredState.getValue(BellBlock.ATTACHMENT) != BellAttachType.SINGLE_WALL && ctx.requiredState.getValue(BellBlock.ATTACHMENT) != BellAttachType.DOUBLE_WALL ? ctx.requiredState.getValue(BellBlock.FACING) : null;

                return new Action().setSides(side).setLookDirection(look);
            }
            case DOOR -> {
                Direction facing = ctx.requiredState.getValue(DoorBlock.FACING);
                DoorHingeSide hinge = ctx.requiredState.getValue(DoorBlock.HINGE);
                BlockPos upperPos = ctx.blockPos.above();

                // 获取门铰链方向
                Direction hingeSide = facing.getCounterClockWise();

                double offset = hinge == DoorHingeSide.RIGHT ? 0.25 : -0.25;
                Vec3 hingeVec = facing.getAxis() == Direction.Axis.X ? new Vec3(0, 0, offset) : new Vec3(offset, 0, 0);

                Map<Direction, Vec3> sides = new HashMap<>();
                sides.put(hingeSide, Vec3.ZERO); // 靠墙方向需要支撑
                sides.put(Direction.DOWN, hingeVec); // 底部点击偏移
                sides.put(facing, hingeVec); // 正面点击偏移

                // 获取左右方块状态
                Direction left = facing.getCounterClockWise();
                Direction right = facing.getCounterClockWise();
                BlockState leftState = ctx.level.getBlockState(ctx.blockPos.relative(left));
                BlockState leftUpperState = ctx.level.getBlockState(upperPos.relative(left));
                BlockState rightState = ctx.level.getBlockState(ctx.blockPos.relative(right));
                BlockState rightUpperState = ctx.level.getBlockState(upperPos.relative(right));

                int occupancy = (leftState.isCollisionShapeFullBlock(ctx.level, ctx.blockPos.relative(left)) ? -1 : 0) + (leftUpperState.isCollisionShapeFullBlock(ctx.level, upperPos.relative(left)) ? -1 : 0) + (rightState.isCollisionShapeFullBlock(ctx.level, ctx.blockPos.relative(right)) ? 1 : 0) + (rightUpperState.isCollisionShapeFullBlock(ctx.level, upperPos.relative(right)) ? 1 : 0);

                boolean isLeftDoor = leftState.getBlock() instanceof DoorBlock && leftState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
                boolean isRightDoor = rightState.getBlock() instanceof DoorBlock && rightState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;

                boolean condition = (hinge == DoorHingeSide.RIGHT && ((isLeftDoor && !isRightDoor) || occupancy > 0)) || (hinge == DoorHingeSide.LEFT && ((isRightDoor && !isLeftDoor) || occupancy < 0)) || (occupancy == 0 && (isLeftDoor == isRightDoor));
                if (condition) return new Action().setSides(sides).setLookDirection(facing).setRequiresSupport();
            }
            case DIRT_PATH, FARMLAND -> {
                return new Action().setItems(Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.MYCELIUM, Items.PODZOL);
            }
            case BIG_DRIPLEAF_STEM -> {
                return new Action().setItem(Items.BIG_DRIPLEAF);
            }
            case CAVE_VINES -> {
                return new Action().setItem(Items.GLOW_BERRIES).setRequiresSupport();
            }
            case WEEPING_VINES -> {
                return new Action().setItem(Items.WEEPING_VINES).setRequiresSupport();
            }
            case TWISTING_VINES -> {
                return new Action().setItem(Items.TWISTING_VINES).setRequiresSupport();
            }
            case FLOWER_POT -> {
                return new Action().setItem(Items.FLOWER_POT);
            }
            case VINES, GLOW_LICHEN -> {
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN && ctx.requiredState.getBlock() == Blocks.VINE) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction);
                    }
                }
            }
            case FIRE -> {
                if (ctx.requiredState.getBlock() instanceof SoulFireBlock)
                    return new Action().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                    }
                }
                return new Action().setSides(Direction.DOWN).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
            }
            case OBSERVER -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case LADDER -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case LANTERN -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case ROD -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case TRIPWIRE_HOOK -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case RAIL -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case PISTON -> {
                return DirectionalBlockPlacement.getAction(ctx, requiredType);
            }
            case SIGN -> {
                Block signBlock = ctx.requiredState.getBlock();
                // 站立告示牌：处理0-15的16方向旋转值
                if (signBlock instanceof StandingSignBlock) {
                    int rotation = ctx.requiredState.getValue(StandingSignBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                }
                // 墙告示牌：保留原有4方向逻辑
                if (signBlock instanceof WallSignBlock) {
                    Direction facing = ctx.requiredState.getValue(WallSignBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
                // 天花板悬挂告示牌处理逻辑
                //#if MC >= 12002
                if (signBlock instanceof WallHangingSignBlock) {
                    //TODO: 视乎方向还是有点问题, 待处理
                    Direction facing = ctx.requiredState.getValue(WallHangingSignBlock.FACING);
                    List<Direction> sides = new ArrayList<>();
                    if (facing.getAxis() == Direction.Axis.X) {
                        sides.add(Direction.NORTH);
                        sides.add(Direction.SOUTH);
                    } else if (facing.getAxis() == Direction.Axis.Z) {
                        sides.add(Direction.EAST);
                        sides.add(Direction.WEST);
                    }
                    return new Action()
                            .setSides(sides.toArray(new Direction[0]))
                            .setLookDirection(facing.getOpposite()).setRequiresSupport();
                }
                if (signBlock instanceof CeilingHangingSignBlock) {
                    int rotation = ctx.requiredState.getValue(CeilingHangingSignBlock.ROTATION);
                    boolean attachFace = ctx.requiredState.getValue(CeilingHangingSignBlock.ATTACHED);
                    return new Action()
                            .setShift(attachFace)
                            .setSides(Direction.UP)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                }
                //#endif
                return null;
            }
            case BANNER -> {
                if (ctx.requiredState.getBlock() instanceof BannerBlock) {
                    int rotation = ctx.requiredState.getValue(BannerBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                } else if (ctx.requiredState.getBlock() instanceof WallBannerBlock) {
                    Direction facing = ctx.requiredState.getValue(WallBannerBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
            }
            case SKULL -> {
                if (ctx.requiredState.getBlock() instanceof SkullBlock) {
                    int rotation = ctx.requiredState.getValue(SkullBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(BlockUtils.getOppositeRotation(rotation))
                            .setRequiresSupport();
                } else if (ctx.requiredState.getBlock() instanceof WallSkullBlock) {
                    Direction facing = ctx.requiredState.getValue(WallSkullBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
            }
            case CROPS -> {
                String blockKey = BlockUtils.getKeyString(ctx.requiredState.getBlock());
                if (blockKey.contains("pumpkin")) {
                    return new Action()
                            .setItem(Items.PUMPKIN_SEEDS)
                            .setRequiresSupport();
                }
                if (blockKey.contains("melon")) {
                    return new Action()
                            .setItem(Items.MELON_SEEDS)
                            .setRequiresSupport();
                }
                return new Action();
            }
            case SKIP -> {
                return null;
            }
            default -> {
                return FallbackBlockPlacement.getAction(ctx);
            }
        }
        return null;
    }


}
