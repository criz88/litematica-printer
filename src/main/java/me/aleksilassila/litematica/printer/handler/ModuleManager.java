package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.printer.CauldronFill;
import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import me.aleksilassila.litematica.printer.utils.RemoteContainerUtils;
import me.aleksilassila.litematica.printer.interfaces.compat.TakeItOutCompat;
import net.minecraft.client.Minecraft;

public class ModuleManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GUI GUI = new GUI();
    public static final Print PRINT = new Print();
    public static final Fill FILL = new Fill();
    public static final Mine MINE = new Mine();
    public static final FluidRemoval FLUID_REMOVAL = new FluidRemoval();
    public static final Bedrock BEDROCK = new Bedrock();
    public static final Trench TRENCH = new Trench();

    @Getter
    @Setter
    private static int packetTick;
    @Getter
    private static long currentHandlerTime;

    public static final ImmutableList<Module> VALUES = ImmutableList.of(
            GUI, PRINT, FILL, FLUID_REMOVAL, MINE, TRENCH, BEDROCK
    );

    private static boolean lastPrinterEnabled = false;

    /** Must precede BreakUtils.onTick and ActionManager.sendQueue. */
    public static void prepareTick() {
        if (TRENCH.prepareTick()) {
            ActionManager.INSTANCE.clearQueue();
            BreakUtils.INSTANCE.cancelAll();
            PRINT.setWatingForWaterPos(null);
            if (Configs.Trench.ENABLED.getBooleanValue()
                    && me.aleksilassila.litematica.printer.interfaces.compat.BedrockCompat.isAvailable()
                    && me.aleksilassila.litematica.printer.interfaces.compat.BedrockCompat.isWorking()) {
                me.aleksilassila.litematica.printer.interfaces.compat.BedrockCompat.setWorking(false);
            }
        }
    }

    public static boolean isModuleActive(Module module) {
        return module instanceof GUI || (module.getEnableConfig() != null
                && module.getEnableConfig().getBooleanValue()
                && (!Configs.Trench.ENABLED.getBooleanValue() || module == TRENCH));
    }

    public static void tick() {
        CauldronFill.tick();
        if (CauldronFill.isWaiting()) return;
        QuickShulkerUtils.tick();
        if (QuickShulkerUtils.isOpenHandler()) return;

        // If TakeItOut is waiting for a server-side shulker extraction, skip
        // all processing so the printer does not interfere.
        if (TakeItOutCompat.isAwaitingItem()) return;

        if (ModUtils.isRemoteInventoryNextLoaded()) {
            RemoteContainerUtils.tick();
        }
        boolean printerEnabled = ConfigUtils.isPrinterEnable();
        if (printerEnabled && !lastPrinterEnabled) {
            MissingMaterialTracker.getInstance().reset();
            for (Module module : VALUES) {
                module.resetScanState();
            }
        }
        lastPrinterEnabled = printerEnabled;

        MissingMaterialTracker.getInstance().startCycle();

        if (!printerEnabled) {
            ActionManager.INSTANCE.clearQueue();
            return;
        }
        if (ActionManager.INSTANCE.sendQueue(mc.player).needWaitModifyLook
                || CauldronFill.isWaiting()) {
            return;
        }

        if (Configs.Core.LAG_CHECK.getBooleanValue()) {
            if (packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
                return;
            }
            packetTick++;
        }

        for (Module module : VALUES) {
            if (!isModuleActive(module)) continue;
            if (!(module instanceof GUI)) {
                if (module != TRENCH && BreakUtils.INSTANCE.isNeedHandle()) {
                    return;
                }
                if (ActionManager.INSTANCE.needWaitModifyLook) {
                    return;
                }
            }
            module.tick();
            if (QuickShulkerUtils.isOpenHandler()
                    || CauldronFill.isWaiting()) return;
        }
    }

    public static void updateTickHandlerTime() {
        currentHandlerTime++;
    }
}