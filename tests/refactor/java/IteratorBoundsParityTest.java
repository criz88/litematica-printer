import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.IteratorManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IteratorBoundsParityTest extends RefactorTestEnvironment {
    LayerRange layer;
    MockedStatic<DataManager> data;
    double originalRange;

    @BeforeEach void setup() {
        originalRange = Configs.Core.WORK_RANGE.getDoubleValue();
        Configs.Core.WORK_RANGE.setDoubleValue(3.25);
        mc.player = mock(LocalPlayer.class);
        mc.level = mock(ClientLevel.class);
        when(mc.level.getMinY()).thenReturn(-64);
        when(mc.level.getMaxY()).thenReturn(320);
        when(mc.player.getX()).thenReturn(0.25);
        when(mc.player.getY()).thenReturn(0.125);
        when(mc.player.getEyeY()).thenReturn(1.75);
        when(mc.player.getZ()).thenReturn(-0.25);
        when(mc.player.getEyePosition()).thenReturn(new Vec3(0.25, 1.75, -0.25));
        layer = mock(LayerRange.class);
        when(layer.getLayerMin()).thenReturn(-1);
        when(layer.getLayerMax()).thenReturn(2);
        when(layer.getLayerSingle()).thenReturn(1);
        when(layer.getLayerAbove()).thenReturn(0);
        when(layer.getLayerBelow()).thenReturn(1);
        when(layer.getLayerMode()).thenReturn(LayerMode.ALL);
        when(layer.getAxis()).thenReturn(Direction.Axis.Y);
        data = mockStatic(DataManager.class);
        data.when(DataManager::getRenderLayerRange).thenReturn(layer);
    }

    @AfterEach void cleanup() {
        data.close();
        Configs.Core.WORK_RANGE.setDoubleValue(originalRange);
        mc.level = null;
    }

    @Test void layerClippingMatchesCoordinatePredicateOnEveryAxis() {
        for (Direction.Axis axis : Direction.Axis.values()) {
            when(layer.getAxis()).thenReturn(axis);
            for (LayerMode mode : LayerMode.values()) {
                when(layer.getLayerMode()).thenReturn(mode);
                IteratorManager iterator = new IteratorManager();
                assertTrue(iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_RENDER_LAYER));
                Set<BlockPos> expected = new HashSet<>();
                for (int x = -3; x <= 4; x++)
                    for (int y = -2; y <= 5; y++)
                        for (int z = -4; z <= 3; z++) {
                            int coordinate = axis.choose(x, y, z);
                            boolean included = switch (mode) {
                                case SINGLE_LAYER -> coordinate == 1;
                                case LAYER_RANGE -> coordinate >= -1 && coordinate <= 2;
                                case ALL_BELOW -> coordinate <= 1;
                                case ALL_ABOVE -> coordinate >= 0;
                                default -> true;
                            };
                            if (included) expected.add(new BlockPos(x, y, z));
                        }
                Set<BlockPos> actual = new HashSet<>();
                iterator.getBox().forEach(actual::add);
                assertEquals(expected, actual, axis + "/" + mode);
            }
        }
    }

    @Test void playerAboveBelowSelectionsIgnoreVisibleLayerAndClampWorldHeight() {
        when(layer.getLayerMode()).thenReturn(LayerMode.SINGLE_LAYER);
        when(layer.getLayerSingle()).thenReturn(200);
        when(mc.level.getMinY()).thenReturn(-1);
        when(mc.level.getMaxY()).thenReturn(4);
        IteratorManager iterator = new IteratorManager();
        iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER);
        assertEquals(-1, iterator.getBox().minY);
        assertEquals(0, iterator.getBox().maxY);
        iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER);
        assertEquals(1, iterator.getBox().minY);
        assertEquals(4, iterator.getBox().maxY);
    }

    @Test void rebuildThresholdLayerChangesAndExplicitInvalidationRemainStable() {
        IteratorManager iterator = new IteratorManager();
        assertTrue(iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_RENDER_LAYER));
        assertFalse(iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_RENDER_LAYER));
        var first = iterator.getBox();
        when(mc.player.getX()).thenReturn(0.35);
        assertFalse(iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_RENDER_LAYER));
        assertSame(first, iterator.getBox());
        when(mc.player.getX()).thenReturn(2.0);
        assertTrue(iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_RENDER_LAYER));
        when(layer.getLayerAbove()).thenReturn(2);
        assertTrue(iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_RENDER_LAYER));
        iterator.markNeedsRebuild();
        assertTrue(iterator.tryBuildBox(mc.player, SelectionType.LITEMATICA_RENDER_LAYER));
        assertFalse(iterator.isNeedsRebuild());
    }
}
