import dev.blinkwhite.remoteinventory.client.ContainerItemCache;
import dev.blinkwhite.remoteinventory.client.ContainerReturnTracker;
import dev.blinkwhite.remoteinventory.enums.ResultType;
import dev.blinkwhite.remoteinventory.network.payload.RemoteExchangeResultPayload.SlotSnapshot;
import dev.blinkwhite.remoteinventory.network.payload.ScanContainerResultPayload.SlotEntry;
import me.aleksilassila.litematica.printer.utils.RemoteContainerUtils;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real dependency caches with controlled callback inputs; no live network is simulated. */
class RemoteExchangeParityTest extends RefactorTestEnvironment {
    private static final BlockPos TAKE = new BlockPos(1, 2, 3);
    private static final BlockPos RETURN = new BlockPos(4, 5, 6);
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private final ContainerItemCache cache = ContainerItemCache.INSTANCE;
    private final ContainerReturnTracker tracker = ContainerReturnTracker.INSTANCE;

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchStates() throws Exception {
        return (Map<String, Object>) field(RemoteContainerUtils.class, "fetchStates").get(null);
    }

    @BeforeEach @AfterEach void clear() throws Exception {
        cache.clear();
        tracker.clear();
        fetchStates().clear();
        field(RemoteContainerUtils.class, "pendingExchange").set(null, null);
    }

    private Object prepare() throws Exception {
        cache.updateContainer("", TAKE, List.of(new SlotEntry(0, STONE, 10)));
        cache.updateContainer("", RETURN, List.of(new SlotEntry(0, DIRT, 1)));
        tracker.track("", RETURN, DIRT);
        Class<?> pending = Class.forName(RemoteContainerUtils.class.getName() + "$PendingExchange");
        var constructor = pending.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        field(RemoteContainerUtils.class, "pendingExchange").set(null,
                constructor.newInstance(TAKE, STONE, 0, RETURN, DIRT, 5));
        Class<?> stateClass = Class.forName(RemoteContainerUtils.class.getName() + "$ItemFetchState");
        var stateConstructor = stateClass.getDeclaredConstructors()[0];
        stateConstructor.setAccessible(true);
        // The original implementation accepted itemId; the refactor removed that unused field.
        Object state = stateConstructor.getParameterCount() == 0
                ? stateConstructor.newInstance() : stateConstructor.newInstance(STONE);
        field(stateClass, "requestPending").setBoolean(state, true);
        fetchStates().put(STONE, state);
        return state;
    }

    private void respond(ResultType result, int taken, int returned, List<SlotSnapshot> delta) throws Exception {
        var callback = RemoteContainerUtils.class.getDeclaredMethod("onExchangeResult",
                BlockPos.class, ResultType.class, int.class, int.class, List.class);
        callback.setAccessible(true);
        callback.invoke(null, TAKE, result, taken, returned, delta);
        assertFalse(RemoteContainerUtils.hasPendingExchange());
        for (Object state : fetchStates().values())
            assertFalse(field(state.getClass(), "requestPending").getBoolean(state));
    }

    private int count(BlockPos pos, String item) {
        return cache.exportContainerData().entrySet().stream()
                .filter(entry -> entry.getKey().pos().equals(pos))
                .flatMap(entry -> entry.getValue().stream())
                .filter(slot -> slot.itemId().equals(item)).mapToInt(SlotEntry::count).sum();
    }

    @Test void partialExchangeRetainsRetryStateAndReturnEntry() throws Exception {
        Object state = prepare();
        respond(ResultType.PARTIAL, 3, 2, List.of());
        assertSame(state, fetchStates().get(STONE));
        assertEquals(7, count(TAKE, STONE));
        assertEquals(3, count(RETURN, DIRT));
        assertEquals(List.of(DIRT, STONE), tracker.peekAll().stream().map(e -> e.itemId()).toList());
    }

    @Test void successfulExchangeClearsFetchAndCompletedReturn() throws Exception {
        prepare();
        respond(ResultType.SUCCESS, 4, 5, List.of());
        assertFalse(fetchStates().containsKey(STONE));
        assertEquals(6, count(TAKE, STONE));
        assertEquals(6, count(RETURN, DIRT));
        assertEquals(List.of(STONE), tracker.peekAll().stream().map(e -> e.itemId()).toList());
    }

    @Test void failedTakeInvalidatesOnlyTakeCache() throws Exception {
        Object state = prepare();
        respond(ResultType.SLOT_EMPTY, 0, 0, List.of());
        assertSame(state, fetchStates().get(STONE));
        assertFalse(cache.isCached("", TAKE));
        assertTrue(cache.isCached("", RETURN));
        assertEquals(List.of(DIRT), tracker.peekAll().stream().map(e -> e.itemId()).toList());
    }

    @Test void lateResponseOnlyReleasesRequestFlags() throws Exception {
        prepare();
        field(RemoteContainerUtils.class, "pendingExchange").set(null, null);
        respond(ResultType.SUCCESS, 4, 5, List.of(new SlotSnapshot(0, STONE, 4)));
        assertEquals(10, count(TAKE, STONE));
        assertEquals(1, count(RETURN, DIRT));
        assertEquals(List.of(DIRT), tracker.peekAll().stream().map(e -> e.itemId()).toList());
    }
}
