package dev.framewatch.client;

import dev.framewatch.FrameWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

public final class SettingsPersistence {
    private SettingsPersistence() {}

    public static boolean save() {
        try {
            FrameWatch.config.save();
            return true;
        } catch (IllegalStateException e) {
            if (FrameWatch.log != null) FrameWatch.log.error("Cannot save FrameWatch settings; live edits remain active", e);
            Minecraft client = Minecraft.getMinecraft();
            if (client != null && client.thePlayer != null) {
                client.thePlayer.addChatMessage(new ChatComponentText(HudTranslations.text("framewatch.saveFailed")));
            }
            return false;
        }
    }
}
