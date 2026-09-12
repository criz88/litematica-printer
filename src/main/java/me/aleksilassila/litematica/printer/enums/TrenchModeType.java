package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

public enum TrenchModeType implements ConfigOptionListEntry<TrenchModeType> {
    FOUR_SIDES("trenchMode.fourSides"),
    TWO_SIDES("trenchMode.twoSides");

    private final I18n i18n;

    TrenchModeType(String key) { i18n = I18n.of(key); }

    @Override
    public I18n getI18n() { return i18n; }
}
