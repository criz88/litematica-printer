package me.aleksilassila.litematica.printer.utils;

import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import me.aleksilassila.litematica.printer.interfaces.compat.TakeItOutCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

//#if MC >= 12105
import net.minecraft.network.HashedStack;
//#endif

import java.util.*;
import java.util.function.IntFunction;

import static me.aleksilassila.litematica.printer.utils.ShulkerContents.*;

public class QuickShulkerUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final boolean QUICK_SHULKER_LOADED = FabricLoader.getInstance().isModLoaded("quickshulker");

    @Getter @Setter
    private static boolean isOpenHandler;
    @Setter
    @Getter
    private static int shulkerCooldown;
    @Getter @Setter
    private static int shulkerBoxSlot = -1;
    @Getter
    private static final Set<Item> lastNeedItemList = new HashSet<>();
    private static final LinkedList<ReturnRequest> itemsToReturn = new LinkedList<>();
    private static final List<TrackedShulker> trackedShulkers = new ArrayList<>();
    private static ReturnRequest activeReturnRequest;
    private static TrackedShulker activeShulker;
    private static final int OPERATION_TIMEOUT_TICKS = 100;
    private static int operationTicks;
    private static PendingTransfer pendingTransfer;
    private static LocalPlayer trackedPlayer;

    private QuickShulkerUtils() {}

    public static void tick() {
        if (trackedPlayer != mc.player) {
            trackedPlayer = mc.player;
            resetOperation();
            itemsToReturn.clear();
            trackedShulkers.clear();
        }
        if (shulkerCooldown > 0) shulkerCooldown--;
        if (!isOpenHandler) return;

        LocalPlayer player = mc.player;
        if (pendingTransfer != null && player != null) {
            if (player.containerMenu != pendingTransfer.container) {
                failOperation(player);
                return;
            }
            if (pendingTransfer.advance(player.getInventory())) {
                if (activeShulker != null) {
                    activeShulker.updateContents(getContainerContents(player.containerMenu,
                            player.containerMenu.slots.size() - 36));
                }
                if (activeReturnRequest != null) {
                    itemsToReturn.removeFirstOccurrence(activeReturnRequest);
                    if (activeReturnRequest.isEmptyBucket() && Configs.Print.FILL_CAULDRONS.getBooleanValue()) {
                        activeReturnRequest = null;
                        pendingTransfer = null;
                        operationTicks = 0;
                        switchFromShulker(); // Return and restock during the same opening.
                        return;
                    }
                } else if (activeShulker != null) {
                    itemsToReturn.addLast(new ReturnRequest(pendingTransfer.item, activeShulker, -1));
                }
                finishShulkerOperation(player);
                return;
            }
        }
        if (++operationTicks >= OPERATION_TIMEOUT_TICKS) failOperation(player);
    }

    private static void failOperation(LocalPlayer player) {
        // 只有已发出的转移结果未确认时才停机。
        if (pendingTransfer != null) Configs.Core.WORK_SWITCH.setBooleanValue(false);
        MessageUtils.setOverlayMessage((pendingTransfer != null
                ? I18n.SHULKER_SYNC_TIMEOUT : I18n.SHULKER_TRANSFER_NOT_STARTED).getName());
        finishShulkerOperation(player);
    }

    public static void addLastNeedItem(Item item) {
        lastNeedItemList.add(item);
    }

    public static void clearLastNeedItems() {
        lastNeedItemList.clear();
    }

    // ========== 统一取物入口 ==========

    /**
     * 根据 ShulkerSource 配置分发潜影盒取物请求。
     * @return true 已发起请求（调用方应等待），false 无法处理
     */
    public static boolean requestShulkerItem(LocalPlayer player, Item[] items) {
        if (!Configs.Print.USE_QUICK_SHULKER.getBooleanValue()) return false;

        ShulkerSource source = (ShulkerSource) Configs.Print.SHULKER_SOURCE.getOptionListValue();

        //#if MC >= 260102
        if (source == ShulkerSource.TAKE_IT_OUT) {
            return TakeItOutCompat.tryExtract(player, items);
        }
        //#endif

        if (source == ShulkerSource.MOD && !QUICK_SHULKER_LOADED) {
            return false;
        }

        if (isOpenHandler || shulkerCooldown > 0 || player.containerMenu != player.inventoryMenu) return false;

        Inventory inventory = player.getInventory();

        boolean full = isInventoryFull(inventory);
        if (Configs.Print.FILL_CAULDRONS.getBooleanValue() && (full || Arrays.stream(items)
                .anyMatch(item -> item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET))
                && requestReturn(player, source, items, true)) return true;
        if (full) return requestReturn(player, source, items, false);

        for (Item item : items) {
            int shulkerSlot = findShulkerWithItem(player, item);
            if (shulkerSlot != -1) {
                ItemStack shulkerStack = inventory.getItem(shulkerSlot);
                activeReturnRequest = null;
                activeShulker = findTrackedShulker(shulkerStack);
                if (activeShulker == null) {
                    activeShulker = new TrackedShulker(shulkerStack.getItem(), getShulkerContents(shulkerStack));
                    trackedShulkers.add(activeShulker);
                }
                activeShulker.setLastKnownSlot(shulkerSlot);
                clearLastNeedItems();
                addLastNeedItem(item);
                return openSelectedShulker(inventory, shulkerSlot, source);
            }
        }
        return false;
    }

    /** Called only after both the filled cauldron and the consumed bucket are confirmed. */
    public static void recordUsedBucket(Item fullBucket, int emptyBucketsBefore) {
        ReturnRequest origin = null;
        int pendingBuckets = 0;
        for (ReturnRequest request : itemsToReturn) {
            if (request.isEmptyBucket()) pendingBuckets++;
            else if (origin == null && request.item() == fullBucket) origin = request;
        }
        if (origin == null) return; // Personal inventory / TakeItOut / remote stock has no tracked source box.
        itemsToReturn.removeFirstOccurrence(origin);
        itemsToReturn.addLast(new ReturnRequest(Items.BUCKET, origin.shulker(),
                Math.max(0, emptyBucketsBefore - pendingBuckets)));
    }

    private static boolean requestReturn(LocalPlayer player, ShulkerSource source, Item[] items, boolean buckets) {
        Inventory inventory = player.getInventory();
        for (Iterator<ReturnRequest> iterator = itemsToReturn.iterator(); iterator.hasNext();) {
            ReturnRequest request = iterator.next();
            if (request.isEmptyBucket() != buckets) continue;
            int slot = buckets ? findTrackedShulker(inventory, request.shulker(), true)
                    : Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                    ? findReturnShulker(inventory, request) : findAnyShulker(player);
            if (buckets && (!request.hasBuckets(inventory) || slot < 0)) {
                iterator.remove();
                if (slot < 0) MessageUtils.setOverlayMessage(I18n.BUCKET_RETURN_UNAVAILABLE.getName());
                continue;
            }
            if (slot < 0) return false;
            activeReturnRequest = request;
            activeShulker = buckets || Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                    ? request.shulker() : null;
            lastNeedItemList.clear();
            if (buckets) lastNeedItemList.addAll(Arrays.asList(items));
            return openSelectedShulker(inventory, slot, source);
        }
        return false;
    }

    private static boolean openSelectedShulker(Inventory inventory, int shulkerSlot, ShulkerSource source) {
        ItemStack shulkerStack = inventory.getItem(shulkerSlot);
        setShulkerBoxSlot(shulkerSlot);
        ModUtils.closeScreen++;
        setOpenHandler(true);
        operationTicks = 0;
        setShulkerCooldown(Configs.Print.SHULKER_COOLDOWN.getIntegerValue());

        // 按来源打开潜影盒：PLUGIN 走右键模拟，MOD 走 QuickShulker API
        if (source == ShulkerSource.PLUGIN) {
            openShulkerByRightClick(shulkerSlot);
        } else {
            QuickShulkerCompat.openShulker(shulkerStack, shulkerSlot);
        }
        return true;
    }

    // ========== 容器槽位点击 ==========

    public static void clickSlot(AbstractContainerMenu container, int slotIndex, int button, ClickType type) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) return;

        NonNullList<Slot> slots = container.slots;
        int totalSlots = slots.size();
        List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
        for (Slot slotItem : slots) {
            copies.add(slotItem.getItem().copy());
        }

        //#if MC >= 12105
        Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();
        //#else
        //$$ Int2ObjectMap<ItemStack> snapshot = new Int2ObjectOpenHashMap<>();
        //#endif

        for (int j = 0; j < totalSlots; j++) {
            ItemStack original = copies.get(j);
            ItemStack current = slots.get(j).getItem();
            if (!ItemStack.isSameItem(original, current)) {
                //#if MC >= 12105
                snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                //#else
                //$$ snapshot.put(j, current.copy());
                //#endif
            }
        }

        //#if MC >= 12105
        HashedStack carried = HashedStack.create(container.getCarried(), connection.decoratedHashOpsGenenerator());
        connection.send(new ServerboundContainerClickPacket(
                container.containerId,
                container.getStateId(),
                Shorts.checkedCast(slotIndex),
                SignedBytes.checkedCast(button),
                type,
                snapshot,
                carried
        ));
        //#else
        //$$ connection.send(new ServerboundContainerClickPacket(
        //$$         container.containerId,
        //$$         container.getStateId(),
        //$$         slotIndex,
        //$$         button,
        //$$         type,
        //$$         container.getCarried().copy(),
        //$$         snapshot
        //$$ ));
        //#endif

        container.clicked(slotIndex, button, type, mc.player);
    }

    public static void pickupSlot(AbstractContainerMenu container, int slotIndex) {
        clickSlot(container, slotIndex, 0, ClickType.PICKUP);
    }

    /** button 即目标快捷栏槽位 0-8 */
    public static void swapWithHotbar(AbstractContainerMenu container, int slotIndex, int hotbarSlot) {
        clickSlot(container, slotIndex, hotbarSlot, ClickType.SWAP);
    }

    // ========== 插件服右键开箱 ==========

    public static void openShulkerByRightClick(int inventorySlot) {
        if (mc.player == null || mc.gameMode == null) return;
        mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId,
                inventorySlot,
                1, // 右键
                ClickType.PICKUP,
                mc.player);
    }

    // ========== 潜影盒取物逻辑 ==========
    /**
     * 收到 ContainerSetContent 包时调用。
     * 背包满时只执行归还；背包未满时执行正常取物。
     */
    public static void switchFromShulker() {
        LocalPlayer player = mc.player;
        if (!isOpenHandler || pendingTransfer != null || player == null
                || player.containerMenu == player.inventoryMenu) return;

        AbstractContainerMenu container = player.containerMenu;
        Inventory inventory = player.getInventory();
        int ownSlots = container.slots.size() - 36;
        if (!container.getCarried().isEmpty()) {
            failOperation(player);
            return;
        }

        boolean returning = activeReturnRequest != null;
        if (returning && activeReturnRequest.isEmptyBucket() && !Configs.Print.FILL_CAULDRONS.getBooleanValue()) {
            finishShulkerOperation(player);
            return;
        }
        if (returning && !activeReturnRequest.hasBuckets(inventory)) {
            itemsToReturn.removeFirstOccurrence(activeReturnRequest);
            finishShulkerOperation(player);
            return;
        }
        int slots = returning ? Math.min(inventory.getContainerSize(), 36) : ownSlots;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = returning ? inventory.getItem(i) : container.slots.get(i).getItem();
            if (returning && activeReturnRequest.isEmptyBucket() && !isPlainBucket(stack)) continue;
            if (returning ? stack.is(activeReturnRequest.item())
                    : !stack.isEmpty() && lastNeedItemList.contains(stack.getItem())) {
                int sourceSlot = i;
                if (returning) sourceSlot = i < 9 ? ownSlots + 27 + i : ownSlots + i - 9;
                beginTransfer(container, sourceSlot, inventory);
                return;
            }
        }
        if (returning) itemsToReturn.removeFirstOccurrence(activeReturnRequest);
        finishShulkerOperation(player);
    }

    private static void beginTransfer(AbstractContainerMenu container, int sourceSlot, Inventory inventory) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null || !container.slots.get(sourceSlot).mayPickup(mc.player)) {
            failOperation(mc.player);
            return;
        }
        int destination = findTransferSpace(container, sourceSlot);
        if (destination < 0) {
            // 未发包，无需等待同步；丢弃无法回塞的请求，避免反复打开同一个满盒。
            itemsToReturn.removeFirstOccurrence(activeReturnRequest);
            MessageUtils.setOverlayMessage(I18n.SHULKER_NO_SPACE.getName());
            finishShulkerOperation(mc.player);
            return;
        }
        boolean single = activeReturnRequest != null && activeReturnRequest.isEmptyBucket();
        pendingTransfer = new PendingTransfer(container, sourceSlot, inventory, single ? destination : -1);
        operationTicks = 0;
        sendTransferClick(container, sourceSlot, 0, pendingTransfer.split() ? ClickType.PICKUP : ClickType.QUICK_MOVE);
    }

    private static void sendTransferClick(AbstractContainerMenu container, int slot, int button, ClickType type) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        //#if MC >= 12105
        connection.send(new ServerboundContainerClickPacket(
                container.containerId, container.getStateId(), Shorts.checkedCast(slot),
                SignedBytes.checkedCast(button), type, new Int2ObjectOpenHashMap<>(),
                HashedStack.create(container.getCarried(), connection.decoratedHashOpsGenenerator())));
        //#else
        //$$ connection.send(new ServerboundContainerClickPacket(
        //$$         container.containerId, container.getStateId(), slot, button, type,
        //$$         container.getCarried().copy(), new Int2ObjectOpenHashMap<>()));
        //#endif
    }

    private static boolean isPlainBucket(ItemStack stack) {
        //#if MC >= 12005
        return ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.BUCKET));
        //#else
        //$$ return ItemStack.isSameItemSameTags(stack, new ItemStack(Items.BUCKET));
        //#endif
    }

    private static int findTransferSpace(AbstractContainerMenu container, int sourceSlot) {
        ItemStack stack = container.slots.get(sourceSlot).getItem();
        if (stack.isEmpty()) return -1;
        int ownSlots = container.slots.size() - 36;
        boolean returning = sourceSlot >= ownSlots;
        for (int i = returning ? 0 : ownSlots; i < (returning ? ownSlots : container.slots.size()); i++) {
            Slot slot = container.slots.get(i);
            ItemStack target = slot.getItem();
            if (!slot.mayPlace(stack) || target.getCount() >= slot.getMaxStackSize(stack)) continue;
            if (target.isEmpty() || stack.isStackable() &&
                    //#if MC >= 12005
                    ItemStack.isSameItemSameComponents(stack, target)
                    //#else
                    //$$ ItemStack.isSameItemSameTags(stack, target)
                    //#endif
            ) return i;
        }
        return -1;
    }

    private static int countItems(int slots, IntFunction<ItemStack> stackAt, Item item) {
        int count = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = stackAt.apply(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    /** Shared acknowledgement checks for stack transfers and one-bucket returns. */
    private static final class PendingTransfer {
        final AbstractContainerMenu container;
        final int sourceSlot, sourceCount, inventoryCount, containerCount, destination, destinationCount;
        final Item item;
        final boolean returning;
        int phase;

        PendingTransfer(AbstractContainerMenu container, int sourceSlot, Inventory inventory, int destination) {
            this.container = container;
            this.sourceSlot = sourceSlot;
            this.destination = destination;
            ItemStack stack = container.slots.get(sourceSlot).getItem();
            item = stack.getItem();
            sourceCount = stack.getCount();
            inventoryCount = countItems(36, inventory::getItem, item);
            containerCount = countItems(container.slots.size() - 36, i -> container.slots.get(i).getItem(), item);
            destinationCount = destination < 0 ? 0 : container.slots.get(destination).getItem().getCount();
            returning = sourceSlot >= container.slots.size() - 36;
        }

        boolean split() { return destination >= 0 && sourceCount > 1; }

        boolean advance(Inventory inventory) {
            ItemStack source = container.slots.get(sourceSlot).getItem(), cursor = container.getCarried();
            if (split() && source.isEmpty() && cursor.is(item) && cursor.getCount() == sourceCount - phase
                    && container.slots.get(destination).getItem().getCount() == destinationCount + phase && phase < 2
                    && (phase == 0 || container.slots.get(destination).getItem().is(item))) {
                // Pick up the stack, place one, then restore the rest; await each server reply.
                sendTransferClick(container, phase == 0 ? destination : sourceSlot, phase == 0 ? 1 : 0, ClickType.PICKUP);
                phase++;
            }
            if (!source.isEmpty() && !source.is(item)) return false;
            int moved = sourceCount - (source.is(item) ? source.getCount() : 0);
            int inventoryDelta = countItems(36, inventory::getItem, item) - inventoryCount;
            int containerDelta = countItems(container.slots.size() - 36, i -> container.slots.get(i).getItem(), item) - containerCount;
            return (destination < 0 ? moved > 0 : moved == 1) && inventoryDelta == (returning ? -moved : moved)
                    && containerDelta == -inventoryDelta && cursor.isEmpty();
        }
    }

    private static void finishShulkerOperation(LocalPlayer player) {
        boolean wasReturn = activeReturnRequest != null;
        if (player != null && player.containerMenu != player.inventoryMenu
                && (pendingTransfer == null || player.containerMenu == pendingTransfer.container)) {
            player.closeContainer();
        }
        resetOperation();
        if (itemsToReturn.isEmpty()) trackedShulkers.clear();
        if (wasReturn) shulkerCooldown = 0;
    }

    private static void resetOperation() {
        shulkerBoxSlot = -1;
        isOpenHandler = false;
        activeReturnRequest = null;
        activeShulker = null;
        pendingTransfer = null;
        operationTicks = 0;
        lastNeedItemList.clear();
        ModUtils.closeScreen = 0;
    }

    /** 在玩家背包（跳过快捷栏）中找到包含目标物品的潜影盒，返回背包槽位索引，未找到返回 -1 */
    public static int findShulkerWithItem(LocalPlayer player, Item target) {
        for (int i = 9; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (id.contains("shulker_box") && stack.getCount() == 1) {
                NonNullList<ItemStack> contents = fi.dy.masa.malilib.util.InventoryUtils
                        .getStoredItems(stack, -1);
                if (contents.stream().anyMatch(s -> s.getItem().equals(target))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findTrackedShulker(Inventory inventory, TrackedShulker tracked, boolean requireUnique) {
        int found = -1;
        int slots = requireUnique ? Math.min(36, inventory.getContainerSize()) : inventory.getContainerSize();
        for (int i = 9; i < slots; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(tracked.boxItem()) && (!requireUnique || stack.getCount() == 1)
                    && sameContents(getShulkerContents(stack), tracked.contents())) {
                if (!requireUnique || i == tracked.lastKnownSlot()) return i;
                found = found == -1 ? i : -2; // Ambiguous moved source: keep its empty bucket.
            }
        }
        return found;
    }

    /**
     * 三级查找回塞目标潜影盒：
     * 1. 精确内容匹配（最准）
     * 2. 检查记录的 lastKnownSlot 是否还是同类型潜影盒（防NBT漂移）
     * 3. 查找背包里装有同种物品的其他潜影盒（兜底）
     * 关闭精确回塞时退化为"只要有空位就塞"
     */
    private static int findReturnShulker(Inventory inventory, ReturnRequest request) {
        TrackedShulker tracked = request.shulker();

        // L1: 精确匹配
        int slot = findTrackedShulker(inventory, tracked, false);
        if (slot != -1) return slot;

        // L2: 检查记录的槽位
        int lastSlot = tracked.lastKnownSlot();
        if (lastSlot >= 9 && lastSlot < inventory.getContainerSize()) {
            ItemStack stack = inventory.getItem(lastSlot);
            if (stack.getItem().equals(tracked.boxItem())) {
                return lastSlot;
            }
        }

        // L3: 查找装有同种物品且有空位的潜影盒
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (!id.contains("shulker_box") || stack.getCount() != 1) continue;
            List<ItemStack> contents = getShulkerContents(stack);
            if (contents.size() >= 27) continue;
            if (contents.stream().anyMatch(s -> s.getItem().equals(request.item()))) {
                return i;
            }
        }

        return -1;
    }

    private static int findAnyShulker(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (id.contains("shulker_box") && stack.getCount() == 1) {
                return i;
            }
        }
        return -1;
    }

    private static TrackedShulker findTrackedShulker(ItemStack stack) {
        List<ItemStack> contents = getShulkerContents(stack);
        for (TrackedShulker trackedShulker : trackedShulkers) {
            if (stack.getItem().equals(trackedShulker.boxItem())
                    && sameContents(contents, trackedShulker.contents())) {
                return trackedShulker;
            }
        }
        return null;
    }

    private static boolean isInventoryFull(Inventory inventory) {
        for (int i = 0; i < Math.min(inventory.getContainerSize(), 36); i++) {
            if (inventory.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private record ReturnRequest(Item item, TrackedShulker shulker, int retainedBuckets) {
        private boolean isEmptyBucket() { return retainedBuckets >= 0; }
        private boolean hasBuckets(Inventory inventory) {
            return !isEmptyBucket() || countItems(36, inventory::getItem, Items.BUCKET) > retainedBuckets;
        }
    }


}