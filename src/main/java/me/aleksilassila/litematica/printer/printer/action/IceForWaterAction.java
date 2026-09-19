package me.aleksilassila.litematica.printer.printer.action;

import net.minecraft.world.item.Items;

/** Identifies water preparation, so ordinary state adjustments never place or break ice. */
public final class IceForWaterAction extends Action {
    public IceForWaterAction() {
        setItem(Items.ICE);
    }
}
