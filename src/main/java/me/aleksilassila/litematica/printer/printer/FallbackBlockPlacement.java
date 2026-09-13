package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;


/** Generic block placement fallback, including coral replacement. */
final class FallbackBlockPlacement {
    private FallbackBlockPlacement() {}

    static @Nullable Action getAction(SchematicBlockContext ctx) {
        Block block = ctx.requiredState.getBlock();
        Identifier blockId1 = BlockUtils.getKey(block);
        if (blockId1.toString().contains("coral")) {
            Identifier blockId2 = of(blockId1.toString().replace("dead_", ""));
            boolean isBlock = blockId1.toString().contains("block");
            List<Item> items = new ArrayList<>();
            items.add(block.asItem());
            if (Configs.Print.REPLACE_CORAL.getBooleanValue()) {
                if (!blockId1.equals(blockId2)) {
                    items.add(BlockUtils.getBlock(blockId2).asItem());
                }
            }
            Action action = new Action().setItems(items.toArray(new Item[0]));
            if (!isBlock) {
                boolean isWallFan = block instanceof BaseCoralWallFanBlock;
                Direction facing = isWallFan ? ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() : Direction.DOWN;
                action.setSides(facing).setRequiresSupport();
            }
            return action;
        }
        Action action = new Action();
        if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
            Direction side = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            AttachFace face = ctx.requiredState.getValue(BlockStateProperties.ATTACH_FACE);
            // 简化方向判断逻辑 三元运算符 Direction.UP那报错？ 应该可以正常运行但是还是换了switch格式
            //Direction sidePitch = face == AttachFace.CEILING ? Direction.UP : face == AttachFace.FLOOR ? Direction.DOWN : side;
            Direction sidePitch = switch (face) {
                case CEILING -> Direction.UP;
                case FLOOR   -> Direction.DOWN;
                default      -> side;
            };
            if (face != AttachFace.WALL) {
                side = side.getOpposite();
            }
            return new Action().setSides(side).setLookDirection(side.getOpposite(), sidePitch).setNeedWaitModifyLook();
        }
        if (block instanceof HorizontalDirectionalBlock || block instanceof StonecutterBlock
                // @formatter:off
                //#if MC >= 11904
                || block instanceof
                    //#if MC >= 12105
                    FlowerBedBlock
                    //#else
                    //$$ PinkPetalsBlock
                    //#endif
                //#endif
                // @formatter:on
        ) {
            Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (block instanceof FenceGateBlock) // 栅栏门
                facing = facing.getOpposite();
            action.setLookDirection(facing.getOpposite());
        }
        if (block instanceof BaseEntityBlock) {
            Direction facing;
            if (ctx.requiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                if (
                //#if MC >= 11904
                block instanceof DecoratedPotBlock ||
                //#endif
                block instanceof CampfireBlock) facing = facing.getOpposite();
                action.setSides(facing).setLookDirection(facing.getOpposite());
            }
            if (ctx.requiredState.hasProperty(BlockStateProperties.FACING)) {
                facing = ctx.requiredState.getValue(BlockStateProperties.FACING);
                if (ctx.requiredState.getBlock() instanceof ShulkerBoxBlock) {
                    facing = facing.getOpposite();
                    action.setShift();
                }
                if (ctx.requiredState.getBlock() instanceof BarrelBlock)
                    action.setNeedWaitModifyLook();
                action.setSides(facing).setLookDirection(facing.getOpposite());
                if (block instanceof DispenserBlock)
                    action.setNeedWaitModifyLook();
            }
        }
        //方块型珊瑚的替换
        if (Configs.Print.REPLACE_CORAL.getBooleanValue() && block.getDescriptionId().endsWith("_coral_block")) {
            //例子：block.minecraft.dead_tube_coral
            String type = block.getDescriptionId().replace("block.minecraft.dead_", "").replace("_coral_block", "");
            switch (type) {
                case "tube" -> action.setItem(Items.TUBE_CORAL_BLOCK);
                case "brain" -> action.setItem(Items.BRAIN_CORAL_BLOCK);
                case "bubble" -> action.setItem(Items.BUBBLE_CORAL_BLOCK);
                case "fire" -> action.setItem(Items.FIRE_CORAL_BLOCK);
                case "horn" -> action.setItem(Items.HORN_CORAL_BLOCK);
            }
            action.setRequiresSupport();
        }
        return action;
    }

    private static Identifier of(String string) {
        //#if MC > 12006
        return Identifier.parse(string);
        //#else
        //$$ return new ResourceLocation(string);
        //#endif
    }
}
