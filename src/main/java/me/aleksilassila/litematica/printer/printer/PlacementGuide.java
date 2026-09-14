package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.printer.action.IceForWaterAction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.utils.*;
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.*;
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
        if (Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && BlockUtils.needsWater(ctx.requiredState)) {
            return null;
        }
        // 先准备水，再检查水生植物能否存活；水源不受用户的可替换方块列表限制。
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue() && BlockUtils.needsWater(ctx.requiredState)) {
            switch (IceForWaterFlow.decideBuildAction(true,
                    BlockUtils.isWaterSource(ctx.currentState), ctx.currentState.is(Blocks.ICE),
                    state == BlockMatchingType.MISSING_BLOCK || ctx.currentState.is(Blocks.WATER),
                    false,
                    mc.gameMode != null && !mc.gameMode.getPlayerMode().isCreative())) {
                case PLACE_ICE, QUEUE_ICE_BREAK -> {
                    IceForWaterSafety.Result safety = IceForWaterSafety.check(ctx.level, ctx.blockPos);
                    if (!safety.safe()) {
                        MessageUtils.setOverlayMessage(safety.message(ctx.level));
                        return null;
                    }
                    // 查询可处理位置不能直接入队破坏；由执行阶段确认目标和安全条件。
                    return new IceForWaterAction();
                }
                case PLACE_BLOCK -> state = BlockMatchingType.MISSING_BLOCK;
                case SKIP -> {
                    MessageUtils.setOverlayMessage(mc.gameMode != null && mc.gameMode.getPlayerMode().isCreative()
                            ? I18n.ICE_CREATIVE_MODE.getName() : I18n.ICE_WATER_UNSAFE.getName());
                    return null;
                }
                case NORMAL -> { }
            }
        }
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
                    @Nullable Action action = buildAction(ctx, hook, state);
                    if (action == null && skip.get()) {   // hook 不处理该方块, 继续尝试下一个
                        continue;
                    }
                    return action;
                }
            }
        }
        return buildAction(ctx, ClassHook.DEFAULT, state);    // 兜底处理
    }

    private @Nullable Action buildAction(SchematicBlockContext ctx, ClassHook requiredType, BlockMatchingType state) {
        return switch (state) {
            case MISSING_BLOCK -> MissingBlockPlacement.getAction(ctx, requiredType);
            case ERROR_BLOCK -> buildActionErrorBlock(ctx, requiredType);
            case ERROR_BLOCK_STATE -> BlockStateCorrection.getAction(mc, ctx, requiredType);
            default -> null;
        };
    }

    /*** 方块错误：方块类型完全不同，且不满足缺失/状态错误的条件 ***/
    private @Nullable Action buildActionErrorBlock(SchematicBlockContext ctx, ClassHook requiredType) {
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


}