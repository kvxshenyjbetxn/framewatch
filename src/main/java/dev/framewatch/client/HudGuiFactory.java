package dev.framewatch.client;

import java.util.Collections;
import java.util.Set;
import cpw.mods.fml.client.IModGuiFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

public final class HudGuiFactory implements IModGuiFactory {
    @Override public void initialize(Minecraft minecraftInstance) {}
    @Override public Class<? extends GuiScreen> mainConfigGuiClass() { return HudSettingsScreen.class; }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return Collections.emptySet(); }
    @Override public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) { return null; }
}
