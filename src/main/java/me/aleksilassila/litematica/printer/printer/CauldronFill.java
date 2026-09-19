package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class CauldronFill {
    private static Pending pending;
    private static int ticks;

    private CauldronFill() {}

    public static @Nullable Item bucketFor(BlockState current, BlockState required) {
        if (!current.is(Blocks.CAULDRON)) return null;
        if (required.is(Blocks.LAVA_CAULDRON)) return Items.LAVA_BUCKET;
        if (required.is(Blocks.WATER_CAULDRON) && required.getValue(LayeredCauldronBlock.LEVEL) == 3) {
            return Items.WATER_BUCKET;
        }
        return null;
    }

    public static boolean isWaiting() {
        return pending != null;
    }

    public static void begin(LocalPlayer player, BlockPos pos, Item bucket) {
        pending = new Pending(player, Minecraft.getInstance().level, pos.immutable(), bucket,
                count(player, bucket), count(player, Items.BUCKET), PlayerUtils.getAbilities(player).instabuild);
        ticks = 0;
    }

    public static void tick() {
        if (pending == null) return;
        Minecraft mc = Minecraft.getInstance();
        Pending p = pending;
        if (p.player != mc.player || p.level != mc.level) {
            pending = null;
            return;
        }
        // Bucket actions bypass client prediction, so these are server-supplied changes.
        boolean filled = bucketFor(Blocks.CAULDRON.defaultBlockState(), mc.level.getBlockState(p.pos)) == p.bucket;
        boolean consumed = count(p.player, p.bucket) == p.fullCount - 1
                && count(p.player, Items.BUCKET) == p.emptyCount + 1;
        if (filled && (p.creative || consumed)) {
            if (!p.creative) QuickShulkerUtils.recordUsedBucket(p.bucket, p.emptyCount);
            pending = null;
        } else if (++ticks >= 100) {
            pending = null;
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            ActionManager.INSTANCE.clearQueue();
            MessageUtils.setOverlayMessage(I18n.CAULDRON_SYNC_TIMEOUT.getName());
        }
    }

    private static int count(LocalPlayer player, Item item) {
        int count = 0;
        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private record Pending(LocalPlayer player, ClientLevel level, BlockPos pos, Item bucket,
                           int fullCount, int emptyCount, boolean creative) {}
}
