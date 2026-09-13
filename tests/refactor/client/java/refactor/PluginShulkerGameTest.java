package refactor;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only enabled against the explicitly prepared loopback Paper/AxShulkers fixture. */
public final class PluginShulkerGameTest implements FabricClientGameTest {
    private static final String PLAYER = "PrinterRefactor";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int stones(ItemStack box) {
        return fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(box, -1).stream()
                .filter(stack -> stack.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
    }

    private static void request(ClientGameTestContext context, Item item) {
        context.runOnClient(mc -> check(QuickShulkerUtils.requestShulkerItem(mc.player, new Item[]{item}),
                "PLUGIN request accepted"));
        context.waitTicks(40);
        context.runOnClient(mc -> {
            check(!QuickShulkerUtils.isOpenHandler(), "plugin content response processed");
            check(mc.player.containerMenu == mc.player.inventoryMenu, "plugin container closed");
            check(mc.player.containerMenu.getCarried().isEmpty(), "plugin transfer leaves empty cursor");
        });
    }

    @Override public void runTest(ClientGameTestContext context) {
        Path directory = Path.of(System.getProperty("refactor.pluginServerDir"));
        try (Rcon server = new Rcon(Files.readString(directory.resolve("rcon.password")))) {
            context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc,
                    ServerAddress.parseString("127.0.0.1:25580"),
                    new ServerData("Refactor plugin fixture", "127.0.0.1:25580", ServerData.Type.OTHER), false, null));
            context.waitFor(mc -> mc.player != null && mc.level != null);
            context.waitTicks(20);
            server.command("gamemode survival " + PLAYER);
            server.command("fill -3 64 -3 3 64 3 minecraft:stone");
            server.command("tp " + PLAYER + " 0 65 0");
            server.command("clear " + PLAYER);
            server.replace("inventory.0", box("torch"));
            server.replace("inventory.1", box("stick"));
            context.waitTicks(20);
            context.runOnClient(mc -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
                Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.PLUGIN);
                Configs.Print.SHULKER_COOLDOWN.setIntegerValue(0);
                Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(true);
            });
            request(context, Items.STONE);
            check(server.command("data get entity " + PLAYER + " Inventory[{Slot:0b}].count").endsWith("20"),
                    "Paper confirms 20 stone transferred to hotbar");
            context.runOnClient(mc -> check(stones(mc.player.getInventory().getItem(9)) == 0,
                    "source shulker contents updated by plugin"));
            server.move("inventory.0", "enderchest.0");
            server.move("inventory.1", "enderchest.1");
            for (int slot = 0; slot < 9; slot++) server.replace("hotbar." + slot, "minecraft:cobblestone 64");
            for (int slot = 0; slot < 27; slot++) server.replace("inventory." + slot, "minecraft:cobblestone 64");
            server.move("enderchest.0", "inventory.2");
            server.move("enderchest.1", "inventory.0");
            server.replace("hotbar.0", "minecraft:stone 20");
            context.waitTicks(20);
            request(context, Items.DIRT);
            context.runOnClient(mc -> {
                check(mc.player.getInventory().getItem(0).isEmpty(), "exact plugin return frees material slot");
                check(stones(mc.player.getInventory().getItem(11)) == 20, "exact plugin return follows moved box");
                check(stones(mc.player.getInventory().getItem(9)) == 20, "plugin leaves decoy box unchanged");
            });
            check(server.boxStoneCount(11) == 20, "Paper confirms exact return into moved box");
            request(context, Items.STONE);
            server.move("inventory.0", "enderchest.0");
            server.move("inventory.2", "inventory.0");
            server.move("enderchest.0", "inventory.2");
            context.waitTicks(20);
            context.runOnClient(mc -> Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(false));
            request(context, Items.DIRT);
            context.runOnClient(mc -> {
                check(mc.player.getInventory().getItem(0).isEmpty(), "non-exact plugin return frees material slot");
                check(stones(mc.player.getInventory().getItem(9)) == 40, "non-exact plugin return uses first box");
                check(stones(mc.player.getInventory().getItem(11)) == 0, "non-exact plugin return leaves source empty");
            });
            check(server.boxStoneCount(9) == 40, "Paper confirms non-exact return count");
            System.out.println("REFACTOR PLUGIN PARITY PASSED: Paper/AxShulkers take, moved exact return and non-exact return");
        } catch (IOException exception) {
            throw new AssertionError("local plugin fixture connection failed", exception);
        } finally {
            context.runOnClient(mc -> mc.disconnect(new TitleScreen()));
        }
    }

    private static String box(String marker) {
        return "minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:\"minecraft:stone\",count:20}},"
                + "{slot:1,item:{id:\"minecraft:" + marker + "\",count:1}}]]";
    }

    private static final class Rcon implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private int id;

        Rcon(String password) throws IOException {
            socket = new Socket("127.0.0.1", 25581);
            socket.setSoTimeout(5000);
            input = new DataInputStream(socket.getInputStream());
            exchange(3, password);
        }

        private String exchange(int type, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            ByteBuffer packet = ByteBuffer.allocate(payload.length + 14).order(ByteOrder.LITTLE_ENDIAN);
            int requestId = ++id;
            packet.putInt(payload.length + 10).putInt(requestId).putInt(type).put(payload).put((byte) 0).put((byte) 0);
            socket.getOutputStream().write(packet.array());
            socket.getOutputStream().flush();
            int length = Integer.reverseBytes(input.readInt());
            check(length >= 10 && length <= 65536, "valid local RCON packet size");
            byte[] response = input.readNBytes(length);
            check(response.length == length, "complete local RCON packet");
            int responseId = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getInt();
            check(responseId == requestId, "local RCON request accepted");
            return new String(response, 8, length - 10, StandardCharsets.UTF_8).strip();
        }

        String command(String command) throws IOException { return exchange(2, command); }
        void replace(String slot, String item) throws IOException {
            String response = command("item replace entity " + PLAYER + " " + slot + " with " + item);
            check(response.contains("Replaced"), "fixture slot replacement: " + response);
        }
        void move(String from, String to) throws IOException {
            String response = command("item replace entity " + PLAYER + " " + to + " from entity " + PLAYER + " " + from);
            check(response.contains("Replaced"), "fixture slot move: " + response);
            replace(from, "minecraft:air");
        }
        int boxStoneCount(int slot) throws IOException {
            // Query scalar leaves: Minecraft's formatted NBT output truncates deep containers.
            int total = 0;
            for (int innerSlot = 0; innerSlot < 27; innerSlot++) {
                String path = "data get entity " + PLAYER + " Inventory[{Slot:" + slot
                        + "b}].components.\"minecraft:container\"[{slot:" + innerSlot + "}].item.";
                if (command(path + "id").contains("\"minecraft:stone\"")) {
                    String count = command(path + "count");
                    total += Integer.parseInt(count.substring(count.lastIndexOf(' ') + 1));
                }
            }
            return total;
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
