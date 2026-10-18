package dev.framewatch;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = FrameWatch.ID, name = "FrameWatch", version = FrameWatch.VERSION,
        acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*",
        guiFactory = "dev.framewatch.client.HudGuiFactory")
public final class FrameWatch {
    public static final String ID = "framewatch";
    public static final String VERSION = "0.3.1";
    public static Logger log;
    public static FrameConfig config;
    @SidedProxy(clientSide = "dev.framewatch.client.ClientProxy", serverSide = "dev.framewatch.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        log = event.getModLog();
        config = FrameConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) { proxy.init(); }
}
