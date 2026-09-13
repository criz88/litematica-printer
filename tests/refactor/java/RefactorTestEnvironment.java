import me.aleksilassila.litematica.printer.config.builder.BaseConfigBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

abstract class RefactorTestEnvironment {
    static Minecraft mc;
    private static MockedStatic<Minecraft> minecraft;
    private static boolean bootstrapped;

    @BeforeAll
    static void startClient() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            net.bytebuddy.agent.ByteBuddyAgent.install();
            new net.bytebuddy.ByteBuddy()
                    .redefine(BaseConfigBuilder.class)
                    .method(net.bytebuddy.matcher.ElementMatchers.named("buildI18n")
                            .or(net.bytebuddy.matcher.ElementMatchers.named("buildVisible")))
                    .intercept(net.bytebuddy.implementation.StubMethod.INSTANCE)
                    .make().load(RefactorTestEnvironment.class.getClassLoader(),
                            net.bytebuddy.dynamic.loading.ClassReloadingStrategy.fromInstalledAgent());
            mc = mock(Minecraft.class);
            bootstrapped = true;
        }
        minecraft = mockStatic(Minecraft.class);
        minecraft.when(Minecraft::getInstance).thenReturn(mc);
        mc.player = null;
        mc.level = null;
        mc.gameMode = null;
    }

    @AfterAll
    static void stopClient() {
        minecraft.close();
    }
}
