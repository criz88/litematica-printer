package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/** Shared material resolution and placement, without scheduling or destructive policy. */
public final class FluidFiller {
    private List<String> blockNames = List.of();
    private List<String> fluidNames = List.of();
    private final List<Item> items = new ArrayList<>();
    private final List<Fluid> fluids = new ArrayList<>();

    public void refresh() {
        refresh(Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.getStrings(), Configs.Fluid.FLUID_LIST.getStrings());
    }

    public void refresh(List<String> configuredBlocks, List<String> configuredFluids) {
        if (!configuredBlocks.equals(blockNames)) {
            blockNames = List.copyOf(configuredBlocks);
            items.clear();
            for (String name : blockNames) {
                BuiltInRegistries.ITEM.stream().filter(item -> PinYinSearchUtils.matchName(name, new ItemStack(item)))
                        .forEach(items::add);
            }
        }
        if (!configuredFluids.equals(fluidNames)) {
            fluidNames = List.copyOf(configuredFluids);
            fluids.clear();
            for (String name : fluidNames) {
                BuiltInRegistries.FLUID.stream().filter(fluid -> PinYinSearchUtils.matchName(name,
                        fluid.defaultFluidState().createLegacyBlock())).forEach(fluids::add);
            }
        }
    }

    public boolean ready() { return !items.isEmpty() && !fluids.isEmpty(); }

    public boolean matches(FluidState state) { return !state.isEmpty() && fluids.contains(state.getType()); }

    public boolean selectMaterial(LocalPlayer player) {
        if (items.isEmpty()) return false;
        if (InventoryUtils.switchToItems(player, items.toArray(new Item[0]))) return true;
        Item item = items.get(0);
        MissingMaterialTracker.getInstance().recordMissing(item,
                //#if MC >= 260100
                //$$ item.getName(item.getDefaultInstance())
                //#elseif MC > 12101
                item.getName()
                //#else
                //$$ item.getDescription()
                //#endif
        );
        return false;
    }

    /** Caller selects the material and validates placement first. */
    public boolean place(BlockPos pos, LocalPlayer player) {
        Action action = new Action().queueAction(pos, Direction.UP, false, player);
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        return ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook;
    }
}
