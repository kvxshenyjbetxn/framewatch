package dev.framewatch.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.framewatch.CommonProxy;
import dev.framewatch.FrameWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.input.Keyboard;

public final class ClientProxy extends CommonProxy {
    private ClientMonitor monitor;
    private final KeyBinding settings = new KeyBinding("key.framewatch.settings", Keyboard.KEY_F7, "key.categories.framewatch");
    private final KeyBinding toggle = new KeyBinding("key.framewatch.toggle", Keyboard.KEY_F8, "key.categories.framewatch");

    private final KeyBinding export = new KeyBinding("key.framewatch.export", Keyboard.KEY_F10, "key.categories.framewatch");

    @Override public void init() {
        HudTranslations.initialize();
        monitor = new ClientMonitor();
        FrameHooks.attach(monitor);
        ClientRegistry.registerKeyBinding(settings);
        ClientRegistry.registerKeyBinding(toggle);

        ClientRegistry.registerKeyBinding(export);
        FMLCommonHandler.instance().bus().register(this);
        ClientCommandHandler.instance.registerCommand(new FrameWatchCommand(monitor));
        IResourceManager resources = Minecraft.getMinecraft().getResourceManager();
        if (resources instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) resources).registerReloadListener(new IResourceManagerReloadListener() {
                private boolean registered;
                @Override public void onResourceManagerReload(IResourceManager manager) {
                    if (registered) monitor.resourcesReloaded();
                    registered = true;
                }
            });
        }
        FrameWatch.log.info("FrameWatch initialized. F7 settings, F8 HUD, F10 CSV. CPU phase panel is controlled in settings.");
    }

    @SubscribeEvent public void onKey(InputEvent.KeyInputEvent event) { consumeBindings(); }
    @SubscribeEvent public void onMouse(InputEvent.MouseInputEvent event) { consumeBindings(); }

    @SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) monitor.processGuiRequests();
    }

    private void consumeBindings() {
        if (settings.isPressed()) monitor.openSettings();
        if (toggle.isPressed()) {
            FrameWatch.config.hudEnabled = !FrameWatch.config.hudEnabled;
            saveSettings();
        }

        if (export.isPressed()) monitor.requestExport();
    }

    private void saveSettings() {
        FrameWatch.config.changed();
        SettingsPersistence.save();
    }
}
