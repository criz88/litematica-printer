package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.Direction;

/** Mutable bounds used only while rebuilding a scan box. */
final class ScanBounds {
    private int minX, minY, minZ;
    private int maxX, maxY, maxZ;

    ScanBounds(double x, double eyeY, double z, double range) {
        minX = (int) Math.floor(x - range);
        maxX = (int) Math.ceil(x + range);
        minY = (int) Math.floor(eyeY - range);
        maxY = (int) Math.ceil(eyeY + range);
        minZ = (int) Math.floor(z - range);
        maxZ = (int) Math.ceil(z + range);
    }

    void clip(Direction.Axis axis, int min, int max) {
        switch (axis) {
            case X -> { minX = Math.max(minX, min); maxX = Math.min(maxX, max); }
            case Y -> { minY = Math.max(minY, min); maxY = Math.min(maxY, max); }
            case Z -> { minZ = Math.max(minZ, min); maxZ = Math.min(maxZ, max); }
        }
    }

    PrinterBox toBox() {
        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
