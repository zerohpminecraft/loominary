package net.zerohpminecraft;

import net.fabricmc.api.ClientModInitializer;
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
        LoominaryCommand.register();
        LoominaryKeybindings.register();
        AnvilAutoFillHandler.register();
        MapBannerDecoder.register();
        BannerAutoClickHandler.register();
    }
}