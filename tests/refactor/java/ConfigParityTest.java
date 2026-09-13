import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.ConfigUtils;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.*;

class ConfigParityTest extends RefactorTestEnvironment {
    @Test
    void orderedOptionsHotkeysAndSerializedDefaultsMatchBaseline() throws Exception {
        StringBuilder snapshot = new StringBuilder();
        for (IConfigBase option : Configs.OPTIONS) {
            snapshot.append("option ").append(option.getName()).append('\n');
        }
        for (IConfigBase option : Configs.All) {
            snapshot.append("all ").append(option.getName()).append('\n');
        }
        for (var hotkey : Configs.HOTKEYS) {
            snapshot.append("hotkey ").append(((IConfigBase) hotkey).getName()).append('\n');
        }
        JsonObject saved = new JsonObject();
        ConfigUtils.writeConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
        snapshot.append(saved).append('\n');
        Path fixture = Path.of(System.getProperty("refactor.fixtures"), "config-baseline.txt");
        if (Boolean.getBoolean("refactor.record")) {
            Files.createDirectories(fixture.getParent());
            Files.writeString(fixture, snapshot);
        }
        assertEquals(Files.readString(fixture), snapshot.toString());
        assertIterableEquals(new LinkedHashSet<>(Configs.All), Configs.OPTIONS);
        for (IConfigBase option : Configs.OPTIONS) {
            assertTrue(Configs.All.stream().anyMatch(candidate -> candidate == option));
        }
        Configs.Core.WORK_RANGE.setDoubleValue(3.25);
        JsonObject customized = new JsonObject();
        ConfigUtils.writeConfigBase(customized, Reference.MOD_ID, Configs.OPTIONS);
        Configs.Core.WORK_RANGE.setDoubleValue(1);
        ConfigUtils.readConfigBase(customized, Reference.MOD_ID, Configs.OPTIONS);
        assertEquals(3.25, Configs.Core.WORK_RANGE.getDoubleValue());
        ConfigUtils.readConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
    }
}
