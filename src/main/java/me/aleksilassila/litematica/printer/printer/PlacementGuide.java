package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.utils.*;
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("IfCanBeSwitch")
public class PlacementGuide {
    @SuppressWarnings("all")
    protected static final Map<Block, Block> STRIPPED_LOGS = AxeItemAccessor.getStrippedBlocks();
    protected static List<String> compostWhitelistCache = new ArrayList<>();      // 缓存堆肥桶白名单的字符串列表（用于判断是否修改）
    protected static Item[] whitelistItemsCache = new Item[0];    // 缓存过滤后的可堆肥物品列表（避免重复计算）
    protected final @NotNull Minecraft mc;
    protected final AtomicReference<Boolean> skip = new AtomicReference<>(false);


    public PlacementGuide(@NotNull Minecraft client) {
        this.mc = client;
    }

    public @Nullable Action getAction(SchematicBlockContext ctx) {
        BlockMatchingType state = BlockMatchingType.get(ctx);
        if (state == BlockMatchingType.CORRECT) return null;
        // canSurvive 只阻拦放置（MISSING），不阻拦破坏（ERROR_BLOCK 走 BREAK_WRONG_BLOCK）
        if (state == BlockMatchingType.MISSING_BLOCK && !ctx.requiredState.canSurvive(ctx.level, ctx.blockPos)) return null;
        // 双格方块（玫瑰丛等）：MISSING 时上半部分由下半部分自动生成，不独立放置
        if (state == BlockMatchingType.MISSING_BLOCK
                && ctx.requiredState.getBlock() instanceof DoublePlantBlock
                && ctx.requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        for (ClassHook hook : ClassHook.values()) {
            for (Class<?> clazz : hook.classes) {
                if (clazz != null && clazz.isInstance(ctx.requiredState.getBlock())) {
                    skip.set(false);
                    @Nullable Action action = buildAction(ctx, hook, state, skip);
                    if (action == null && skip.get()) {   // hook 不处理该方块, 继续尝试下一个
                        continue;
                    }
                    return action;
                }
            }
        }
        return buildAction(ctx, ClassHook.DEFAULT, state, skip);    // 兜底处理
    }

    @SuppressWarnings("EnhancedSwitchMigration")
    private @Nullable Action buildAction(SchematicBlockContext ctx, ClassHook requiredType, BlockMatchingType state, AtomicReference<Boolean> skip) {
        // 跳过含水方块
        if (Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && BlockUtils.needsWater(ctx.requiredState)) {
            return null;
        }
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && BlockUtils.needsWater(ctx.requiredState)) {
            boolean canGenerateWater = mc.gameMode != null && !mc.gameMode.getPlayerMode().isCreative();
            switch (IceForWaterFlow.decideBuildAction(
                    true,
                    BlockUtils.isWaterSource(ctx.currentState) || BlockUtils.isWaterlogged(ctx.currentState),
                    ctx.currentState.getBlock() instanceof IceBlock,
                    state == BlockMatchingType.MISSING_BLOCK,
                    iceDownCheck(ctx),
                    canGenerateWater)) {
                case PLACE_ICE -> {
                    return new Action().setItem(Items.ICE);
                }
                case PLACE_BLOCK -> {
                    return MissingBlockPlacement.getAction(ctx, requiredType);
                }
                case QUEUE_ICE_BREAK -> {
                    if (!BreakUtils.INSTANCE.inQueue(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx.blockPos);
                    return new Action().setItem(Items.ICE);
                }
                case SKIP -> {
                    // 创造模式：提示后跳过
                    if (mc.gameMode != null && mc.gameMode.getPlayerMode().isCreative()
                            && BlockUtils.needsWater(ctx.requiredState)) {
                        MessageUtils.setOverlayMessage(I18n.ICE_CREATIVE_MODE.getName());
                    }
                    return null;
                }
            }
        }
        Action action;
        switch (state) {
            case MISSING_BLOCK:
                action = MissingBlockPlacement.getAction(ctx, requiredType);
                break;
            case ERROR_BLOCK:
                action = buildActionErrorBlock(ctx, requiredType, skip);
                break;
            case ERROR_BLOCK_STATE:
                action = buildActionErrorBlockState(ctx, requiredType, skip);
                break;
            default:
                action = null;
                break;
        }
        return action;
    }

    private boolean iceDownCheck(SchematicBlockContext ctx) {
        Block downBlockState = ctx.level.getBlockState(ctx.blockPos.below()).getBlock();
        return downBlockState == Blocks.COBWEB
                || downBlockState == Blocks.BAMBOO_SAPLING
                || downBlockState instanceof LiquidBlock;
    }

    /*** 状态错误：方块类型相同，但方块状态（如朝向、亮度等）不一致 ***/
    private @Nullable Action buildActionErrorBlockState(SchematicBlockContext ctx, ClassHook requiredType, AtomicReference<Boolean> skip) {
        boolean printBreakWrongStateBlock = Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue();

        switch (requiredType) {
            case SLAB -> {
                if (ctx.requiredState.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                    Direction requiredHalf = ctx.currentState.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.DOWN : Direction.UP;
                    return new Action().setSides(requiredHalf);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case SNOW -> {
                int layers = ctx.currentState.getValue(SnowLayerBlock.LAYERS);
                if (layers < ctx.requiredState.getValue(SnowLayerBlock.LAYERS)) {
                    Map<Direction, Vec3> sides = new HashMap<>() {{
                        put(Direction.UP, new Vec3(0, (layers / 8d) - 1, 0));
                    }};
                    return new ClickAction().setItem(Items.SNOW).setSides(sides);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case DOOR, TRAPDOOR -> {
                //判断门是不是铁制的，如果是就直接返回
                if (ctx.requiredState.is(Blocks.IRON_DOOR) || ctx.requiredState.is(Blocks.IRON_TRAPDOOR)) {
                    break;
                }
                if (ctx.requiredState.getValue(BlockStateProperties.OPEN) != ctx.currentState.getValue(BlockStateProperties.OPEN)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    boolean facingDiff = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                            != ctx.currentState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    boolean alignDiff = false;
                    if (ctx.requiredState.hasProperty(TrapDoorBlock.HALF)) {
                        alignDiff = ctx.requiredState.getValue(TrapDoorBlock.HALF)
                                != ctx.currentState.getValue(TrapDoorBlock.HALF);
                    }
                    if (ctx.requiredState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                        alignDiff |= ctx.requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                != ctx.currentState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
                    }
                    if (ctx.requiredState.hasProperty(DoorBlock.HINGE)) {
                        alignDiff |= ctx.requiredState.getValue(DoorBlock.HINGE)
                                != ctx.currentState.getValue(DoorBlock.HINGE);
                    }
                    if (facingDiff || alignDiff) {
                        BreakUtils.INSTANCE.add(ctx);
                    }
                }
            }
            case FENCE_GATE -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                if (facing.getOpposite() == ctx.currentState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        || ctx.requiredState.getValue(BlockStateProperties.OPEN) != ctx.currentState.getValue(BlockStateProperties.OPEN)
                ) {
                    return new ClickAction().setSides(facing.getOpposite()).setLookDirection(facing);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case LEVER -> {
                if (ctx.requiredState.getValue(LeverBlock.POWERED) != ctx.currentState.getValue(LeverBlock.POWERED)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case CANDLES -> {
                if (ctx.currentState.getValue(BlockStateProperties.CANDLES) < ctx.requiredState.getValue(BlockStateProperties.CANDLES)) {
                    return new ClickAction().setItem(ctx.requiredState.getBlock().asItem());
                }
                if (!ctx.currentState.getValue(CandleBlock.LIT) && ctx.requiredState.getValue(CandleBlock.LIT)) {
                    return new ClickAction().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
                }
                if (ctx.currentState.getValue(CandleBlock.LIT) && !ctx.requiredState.getValue(CandleBlock.LIT)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case PICKLES -> {
                if (ctx.currentState.getValue(SeaPickleBlock.PICKLES) < ctx.requiredState.getValue(SeaPickleBlock.PICKLES)) {
                    return new ClickAction().setItem(Items.SEA_PICKLE);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case REPEATER -> {
                if (!ctx.requiredState.getValue(RepeaterBlock.DELAY).equals(ctx.currentState.getValue(RepeaterBlock.DELAY))) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock &&
                        ctx.requiredState.getValue(RepeaterBlock.POWERED) == ctx.currentState.getValue(RepeaterBlock.POWERED) &&
                        ctx.requiredState.getValue(RepeaterBlock.LOCKED) == ctx.currentState.getValue(RepeaterBlock.LOCKED)
                ) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case COMPARATOR -> {
                if (ctx.requiredState.getValue(ComparatorBlock.MODE) != ctx.currentState.getValue(ComparatorBlock.MODE)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    Direction requiredFacing = ctx.requiredState.getValue(ComparatorBlock.FACING);
                    Direction currentFacing = ctx.currentState.getValue(ComparatorBlock.FACING);
                    if (requiredFacing == currentFacing) {
                        SchematicBlockContext facingFirstBlockCtx = ctx.offset(requiredFacing);
                        // 检验输出信号
                        if (ctx.level.getSignal(ctx.blockPos, requiredFacing) != ctx.schematic.getSignal(ctx.blockPos, requiredFacing)) {
                            // 检验输入端是否为"能输出比较器信号方块"
                            if (facingFirstBlockCtx.requiredState.hasAnalogOutputSignal()) {
                                return null;
                            }
                            // 检验输入端非透明方块
                            if (facingFirstBlockCtx.requiredState.isRedstoneConductor(facingFirstBlockCtx.level, facingFirstBlockCtx.blockPos)) {
                                SchematicBlockContext facingSecondBlockCtx = facingFirstBlockCtx.offset(requiredFacing);
                                // 仿照原版检验物品展示框
                                BlockPos blockPos = facingSecondBlockCtx.blockPos;
                                List<ItemFrame> itemFrameList = facingSecondBlockCtx.schematic.getEntitiesOfClass(
                                        ItemFrame.class,
                                        new AABB(blockPos),
                                        (itemFrame) -> itemFrame.getDirection() == requiredFacing
                                );
                                // 隔非透明方块检验容器
                                if (facingSecondBlockCtx.requiredState.hasAnalogOutputSignal()) {
                                    return null;
                                }
                                // 隔非透明方块检验物品展示框
                                if (!itemFrameList.isEmpty()) {
                                    return null;
                                }
                            }
                        }
                    }
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case CROPS -> {
                if (!Configs.Print.BONEMEAL_CROPS.getBooleanValue()) {
                    return null;
                }
                Block currentBlock = ctx.currentState.getBlock();
                Block requiredBlock = ctx.requiredState.getBlock();
                if (currentBlock == requiredBlock && InventoryUtils.playerHasAccessToItem(mc.player, Items.BONE_MEAL)) {
                    int maxAge = requiredBlock instanceof BeetrootBlock ? 3 : 7;
                    int requiredAge = ctx.requiredState.getValue(requiredBlock instanceof BeetrootBlock ? BeetrootBlock.AGE : StemBlock.AGE);
                    int currentAge = ctx.currentState.getValue(requiredBlock instanceof BeetrootBlock ? BeetrootBlock.AGE : StemBlock.AGE);
                    if (requiredAge == maxAge && currentAge < maxAge) {
                        return new ClickAction().setItem(Items.BONE_MEAL);
                    }
                }
            }
            case NOTE_BLOCK -> {
                if (Configs.Print.NOTE_BLOCK_TUNING.getBooleanValue() && !Objects.equals(ctx.requiredState.getValue(NoteBlock.NOTE), ctx.currentState.getValue(NoteBlock.NOTE))) {
                    return new ClickAction();
                }
            }
            case CAMPFIRE -> {
                if (!ctx.requiredState.getValue(CampfireBlock.LIT) && ctx.currentState.getValue(CampfireBlock.LIT)) {
                    return new ClickAction().setItems(Reference.SHOVEL_ITEMS).setSides(Direction.UP);
                }
                if (ctx.requiredState.getValue(CampfireBlock.LIT) && !ctx.currentState.getValue(CampfireBlock.LIT)) {
                    return new ClickAction().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
                }
                if (printBreakWrongStateBlock && ctx.requiredState.getValue(CampfireBlock.FACING) != ctx.currentState.getValue(CampfireBlock.FACING)) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case END_PORTAL_FRAME -> {
                if (ctx.requiredState.getValue(EndPortalFrameBlock.HAS_EYE) && !ctx.currentState.getValue(EndPortalFrameBlock.HAS_EYE)) {
                    return new ClickAction().setItem(Items.ENDER_EYE);
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            //#if MC >= 11904
            case FLOWERBED -> {
                if (ctx.currentState.getValue(BlockStateProperties.FLOWER_AMOUNT) <= ctx.requiredState.getValue(BlockStateProperties.FLOWER_AMOUNT)) {
                    return new ClickAction().setItem(ctx.requiredState.getBlock().asItem());
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            //#endif
            case RED_STONE_WIRE -> {
                // 在Java版中，对于没有连接到任何红石元件的十字形的红石线，可以按使用键使其变为点状，从而不与任何方向连接，再按一次可以恢复。
                boolean allNoneRequired = ctx.requiredState.getValue(RedStoneWireBlock.NORTH) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.SOUTH) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.EAST) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.WEST) == RedstoneSide.NONE;

                boolean allSideCurrent = ctx.currentState.getValue(RedStoneWireBlock.NORTH) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.SOUTH) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.EAST) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.WEST) == RedstoneSide.SIDE;

                if (allNoneRequired && allSideCurrent) {
                    return new ClickAction().setItem(Items.AIR);
                }
            }
            case VINES, GLOW_LICHEN -> {
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setLookDirection(direction);
                    }
                }
                if (printBreakWrongStateBlock) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case CAULDRON -> {
                if (ctx.currentState.getValue(LayeredCauldronBlock.LEVEL) > ctx.requiredState.getValue(LayeredCauldronBlock.LEVEL)) {
                    if (InventoryUtils.playerHasAccessToItem(mc.player, Items.GLASS_BOTTLE)) {
                        return new ClickAction().setItem(Items.GLASS_BOTTLE);
                    } else {
                        MessageUtils.setOverlayMessage(I18n.BREWINGSTAND_LOWER.getName(getNameFromItem(Items.GLASS_BOTTLE)));
                    }
                }
                if (ctx.currentState.getValue(LayeredCauldronBlock.LEVEL) < ctx.requiredState.getValue(LayeredCauldronBlock.LEVEL))
                    if (InventoryUtils.playerHasAccessToItem(mc.player, Items.POTION)) {
                        return new ClickAction().setItem(Items.POTION);
                    } else {
                        MessageUtils.setOverlayMessage(I18n.BREWINGSTAND_RAISE.getName(getNameFromItem(Items.GLASS_BOTTLE)));
                    }
            }
            case DAYLIGHT_DETECTOR -> {
                if (ctx.currentState.getValue(DaylightDetectorBlock.INVERTED) != ctx.requiredState.getValue(DaylightDetectorBlock.INVERTED)) {
                    return new ClickAction();
                }
            }
            case FIRE -> {
                if (!ctx.requiredState.getValue(FireBlock.AGE).equals(ctx.currentState.getValue(FireBlock.AGE))) {
                    return null;
                }
                if (ctx.requiredState.getBlock() instanceof SoulFireBlock) return null;
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                    }
                }
                return new Action().setSides(Direction.DOWN).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
            }
            case COMPOSTER -> {
                if (!Configs.Print.FILL_COMPOSTER.getBooleanValue()) {
                    return null;
                }
                if (ctx.currentState.getValue(ComposterBlock.LEVEL) >= ctx.requiredState.getValue(ComposterBlock.LEVEL)) {
                    return null;
                }
                List<String> whitelist = Configs.Print.FILL_COMPOSTER_WHITELIST.getStrings();
                if (!whitelist.equals(compostWhitelistCache)) {
                    compostWhitelistCache = new ArrayList<>(whitelist);
                    List<Item> whitelistItems = new ArrayList<>();
                    for (Item item : Reference.COMPOSTABLE_ITEMS) {
                        for (String rule : whitelist) {
                            if (PinYinSearchUtils.matchName(rule, new ItemStack(item))) {
                                whitelistItems.add(item);
                                break;
                            }
                        }
                    }
                    whitelistItemsCache = whitelistItems.toArray(Item[]::new);
                }
                Item[] finalItems = whitelistItemsCache.length > 0 ? whitelistItemsCache : Reference.COMPOSTABLE_ITEMS;
                if (finalItems.length > 0) {
                    return new ClickAction().setItems(finalItems);
                }
            }
            case STAIR -> {
                if (printBreakWrongStateBlock &&
                        (ctx.requiredState.getValue(StairBlock.FACING) != ctx.currentState.getValue(StairBlock.FACING) ||
                                ctx.requiredState.getValue(StairBlock.HALF) != ctx.currentState.getValue(StairBlock.HALF))) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case DEFAULT -> {
                Class<?>[] ignored = new Class<?>[]
                        {FenceBlock.class,
                                WallBlock.class,
                                IronBarsBlock.class,
                                PressurePlateBlock.class,
                                StainedGlassPaneBlock.class
                        };
                if (printBreakWrongStateBlock && !Arrays.asList(ignored).contains(ctx.requiredState.getBlock().getClass())) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
        }
        return null;
    }

    /*** 方块错误：方块类型完全不同，且不满足缺失/状态错误的条件 ***/
    private @Nullable Action buildActionErrorBlock(SchematicBlockContext ctx, ClassHook requiredType, AtomicReference<Boolean> skip) {
        switch (requiredType) {
            case FARMLAND -> {
                Block[] soilBlocks = new Block[]{Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT_PATH, Blocks.COARSE_DIRT};
                for (Block soilBlock : soilBlocks) {
                    if (ctx.currentState.getBlock().equals(soilBlock)) {
                        return new ClickAction().setItems(Reference.HOE_ITEMS);
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case DIRT_PATH -> {
                Block[] soilBlocks = new Block[]{Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MYCELIUM, Blocks.PODZOL};
                for (Block soilBlock : soilBlocks) {
                    if (ctx.currentState.getBlock().equals(soilBlock)) {
                        return new ClickAction().setItems(Reference.SHOVEL_ITEMS);
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case FLOWER_POT -> {
                if (ctx.requiredState.getBlock() instanceof FlowerPotBlock potBlock) {
                    Block content = potBlock.getPotted();
                    if (content != Blocks.AIR) {
                        return new ClickAction().setItem(content.asItem());
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case CAULDRON -> {
                if (Arrays.asList(requiredType.classes).contains(ctx.currentState.getBlock().getClass())) {
                    return null;
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            case STRIP_LOG -> {
                Block stripped = STRIPPED_LOGS.get(ctx.currentState.getBlock());
                if (stripped != null && stripped == ctx.requiredState.getBlock()) {
                    return new ClickAction().setItems(Reference.AXE_ITEMS);
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
            }
            case SIGN -> {
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && BreakUtils.canBreakBlock(ctx.blockPos)) {
                    boolean isLegitimateSign = ctx.currentState.getBlock() instanceof StandingSignBlock
                            || ctx.currentState.getBlock() instanceof WallSignBlock
                            //#if MC >= 12002
                            || ctx.currentState.getBlock() instanceof WallHangingSignBlock
                            || ctx.currentState.getBlock() instanceof CeilingHangingSignBlock
                            //#endif
                            ;
                    if (!isLegitimateSign) {
                        BreakUtils.INSTANCE.add(ctx);
                    }
                }
            }
            case CROPS -> {
                String requiredBlockKey = BlockUtils.getKeyString(ctx.requiredState.getBlock());
                String currentBlockKey = BlockUtils.getKeyString(ctx.currentState.getBlock());
                if (requiredBlockKey.contains("pumpkin_stem") && !currentBlockKey.contains("pumpkin_stem")) {
                    BreakUtils.INSTANCE.add(ctx);
                } else if (requiredBlockKey.contains("melon_stem") && !currentBlockKey.contains("melon_stem")) {
                    BreakUtils.INSTANCE.add(ctx);
                }
            }
            default -> {
                if (Configs.Print.REPLACE_CORAL.getBooleanValue() && ctx.requiredState.getBlock().getDescriptionId().contains("coral")) {
                    break;
                }
                if ((Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && !ctx.requiredState.isAir())
                        || (Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue() && ctx.requiredState.isAir())) {
                    if (BreakUtils.canBreakBlock(ctx.blockPos)) BreakUtils.INSTANCE.add(ctx);
                }
            }
        }
        return null;
    }

    enum ClassHook {
        // 放置
        TORCH(
                //#if MC > 12002
                BaseTorchBlock.class
                //#else
                //$$ TorchBlock.class
                //#endif
        ),                                      // 火把
        SLAB(SlabBlock.class),                  // 台阶
        STAIR(StairBlock.class),                // 楼梯
        TRAPDOOR(TrapDoorBlock.class),          // 活板门
        STRIP_LOG(RotatedPillarBlock.class),    // 去皮原木
        ANVIL(AnvilBlock.class),                // 铁砧
        HOPPER(HopperBlock.class),              // 漏斗
        CAMPFIRE(CampfireBlock.class),          // 营火
        BED(BedBlock.class),                    // 床
        BELL(BellBlock.class),                  // 钟
        AMETHYST(AmethystClusterBlock.class),   // 紫水晶
        DOOR(DoorBlock.class),                  // 门
        COCOA(CocoaBlock.class),                // 可可豆
        //#if MC >= 12003
        CRAFTER(CrafterBlock.class),            // 合成器
        //#endif
        CHEST(ChestBlock.class),                // 箱子
        OBSERVER(ObserverBlock.class),          // 侦测器
        LADDER(LadderBlock.class),              // 梯子
        LANTERN(LanternBlock.class),            // 灯笼
        ROD(RodBlock.class),                    // 末地烛 避雷针
        TRIPWIRE_HOOK(TripWireHookBlock.class), // 绊线钩
        RAIL(BaseRailBlock.class),              // 铁轨
        PISTON(PistonBaseBlock.class),          // 活塞 （为了避免被破坏错误状态破坏）
        SIGN(
                StandingSignBlock.class,
                WallSignBlock.class
                //#if MC >= 12002
                , WallHangingSignBlock.class
                , CeilingHangingSignBlock.class
                //#endif
        ),
        BANNER(AbstractBannerBlock.class),      // 旗帜
        SKULL(AbstractSkullBlock.class),        // 头颅
        CROPS(AttachedStemBlock.class, StemBlock.class, CropBlock.class, BeetrootBlock.class),          // 农作物(茎)

        // 点击
        FLOWER_POT(FlowerPotBlock.class),               // 花盆
        BIG_DRIPLEAF_STEM(BigDripleafStemBlock.class),  // 大垂叶茎
        CAVE_VINES(CaveVinesBlock.class, CaveVinesPlantBlock.class),                // 洞穴藤蔓
        WEEPING_VINES(WeepingVinesBlock.class, WeepingVinesPlantBlock.class),       // 垂泪藤
        TWISTING_VINES(TwistingVinesBlock.class, TwistingVinesPlantBlock.class),    // 缠怨藤
        SNOW(SnowLayerBlock.class),                     // 雪
        CANDLES(CandleBlock.class),                     // 蜡烛
        REPEATER(RepeaterBlock.class),                  // 中继器
        COMPARATOR(ComparatorBlock.class),              // 比较器
        PICKLES(SeaPickleBlock.class),                  // 海泡菜
        NOTE_BLOCK(NoteBlock.class),                    // 音符盒
        END_PORTAL_FRAME(EndPortalFrameBlock.class),    // 末地传送门框架
        //#if MC >= 11904
        FLOWERBED(
                //#if MC >= 12105
                FlowerBedBlock.class
                //#else
                //$$ PinkPetalsBlock.class
                //#endif
        ), // 花簇（ojng你看看你这是什么抽象命名）
        //#endif
        VINES(VineBlock.class),                         // 藤蔓
        GLOW_LICHEN(GlowLichenBlock.class),             // 发光地衣
        FIRE(FireBlock.class, SoulFireBlock.class),     // 火，灵魂火
        RED_STONE_WIRE(RedStoneWireBlock.class),        // 红石粉
        FENCE_GATE(FenceGateBlock.class),               // 栅栏门
        LEVER(LeverBlock.class),                        // 拉杆
        CAULDRON(CauldronBlock.class, LavaCauldronBlock.class, LayeredCauldronBlock.class), // 炼药锅
        DAYLIGHT_DETECTOR(DaylightDetectorBlock.class), // 阳光探测器
        COMPOSTER(ComposterBlock.class),                // 堆肥桶

        // 其他
        FARMLAND(FarmBlock.class),              // 耕地
        DIRT_PATH(DirtPathBlock.class),         // 土径
        NETHER_PORTAL(NetherPortalBlock.class), // 下界传送门
        SKIP(SkullBlock.class, LiquidBlock.class, BubbleColumnBlock.class, WaterlilyBlock.class), // 跳过
        DEFAULT; // 默认

        private final Class<?>[] classes;

        ClassHook(Class<?>... classes) {
            this.classes = classes;
        }
    }

    // 辅助方法：获取物品名称（版本适配）
    private static Component getNameFromItem(Item item) {
        //#if MC >= 260100
        //$$ return item.getName(item.getDefaultInstance());
        //#elseif MC > 12101
        return item.getName();
        //#else
        //$$ return item.getDescription();
        //#endif
    }


}