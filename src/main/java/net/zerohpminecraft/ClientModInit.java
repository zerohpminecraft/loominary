package net.zerohpminecraft;

import net.fabricmc.api.ClientModInitializer;
import net.zerohpminecraft.MapEncryption;
import net.zerohpminecraft.command.LoominaryCommand;

public class ClientModInit implements ClientModInitializer {

    public static final String MOD_ID = "loominary";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("§a[Loominary] Client-side mod initialized successfully!");

        LOGGER.info("§a[Loominary] MapFrameScanner registered (tick-based detection active)");

        CarpetChannel.init();
        PayloadState.load();
        MapEncryption.loadPasswords();
        MapEncryption.tryLoadKeyFile();
        LoominaryCommand.register();
        LoominaryKeybindings.register();
        AnvilAutoFillHandler.register();
        MapBannerDecoder.register();
        BannerAutoClickHandler.register();
        CarpetBalanceHandler.register();
        CarpetFillHandler.register();
        AutoWalkHandler.register();
        WaypointMover.register();
        AutoPrintHandler.register();

        // Docs screenshot harness — dev-only. The class is excluded from the release
        // jar (build.gradle), and lazy class loading means this reference is safe:
        // the branch never executes without -Dloominary.docs=true.
        if (Boolean.getBoolean("loominary.docs")) {
            net.zerohpminecraft.docs.DocsDriver.init();
        }
    }
}