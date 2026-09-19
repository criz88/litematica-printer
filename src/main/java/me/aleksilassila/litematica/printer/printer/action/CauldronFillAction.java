package me.aleksilassila.litematica.printer.printer.action;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.NotNull;

/** One bucket interaction, followed by a server confirmation before printing resumes. */
public final class CauldronFillAction extends ClickAction {
    private final Item bucket;

    public CauldronFillAction(Item bucket) {
        this.bucket = bucket;
        setItem(bucket);
    }

    @Override
    public Action queueAction(@NotNull BlockPos pos, @NotNull Direction side, boolean shift,
                              @NotNull LocalPlayer player) {
        super.queueAction(pos, side, false, player);
        ActionManager.INSTANCE.setCauldronBucket(bucket);
        return this;
    }
}
