import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class PrinterBoxParityTest extends RefactorTestEnvironment {
    @Test
    void all48OrdersMatchIndependentCoordinateSort() {
        for (IterationOrderType order : IterationOrderType.values()) {
            for (int reverse = 0; reverse < 8; reverse++) {
                PrinterBox box = new PrinterBox(-2, -1, -3, 1, 1, 0);
                box.iterationMode = order;
                box.xIncrement = (reverse & 1) == 0;
                box.yIncrement = (reverse & 2) == 0;
                box.zIncrement = (reverse & 4) == 0;
                List<BlockPos> expected = new ArrayList<>();
                for (int x = -2; x <= 1; x++)
                    for (int y = -1; y <= 1; y++)
                        for (int z = -3; z <= 0; z++) expected.add(new BlockPos(x, y, z));
                Comparator<BlockPos> compare = (a, b) -> 0;
                String axes = order.name();
                for (int i = axes.length() - 1; i >= 0; i--) {
                    char axis = axes.charAt(i);
                    boolean ascending = switch (axis) {
                        case 'X' -> box.xIncrement;
                        case 'Y' -> box.yIncrement;
                        default -> box.zIncrement;
                    };
                    Comparator<BlockPos> coordinate = Comparator.comparingInt(pos -> switch (axis) {
                        case 'X' -> pos.getX();
                        case 'Y' -> pos.getY();
                        default -> pos.getZ();
                    });
                    compare = compare.thenComparing(ascending ? coordinate : coordinate.reversed());
                }
                expected.sort(compare);
                List<BlockPos> actual = new ArrayList<>();
                box.forEach(actual::add);
                assertEquals(expected, actual, order + "/" + reverse);
                var iterator = box.iterator();
                while (iterator.hasNext()) iterator.next();
                assertThrows(NoSuchElementException.class, iterator::next);
            }
        }
    }

    @Test
    void invertedAndSingleBlockBoxesKeepBoundarySemantics() {
        PrinterBox empty = new PrinterBox(1, 0, 0, 0, 0, 0);
        assertTrue(empty.isEmpty());
        assertFalse(empty.iterator().hasNext());
        PrinterBox single = new PrinterBox(-1, -2, -3, -1, -2, -3);
        assertTrue(single.contains(-1, -2, -3));
        assertFalse(single.contains(0, -2, -3));
        var iterator = single.iterator();
        assertEquals(new BlockPos(-1, -2, -3), iterator.next());
        assertFalse(iterator.hasNext());
    }
}
