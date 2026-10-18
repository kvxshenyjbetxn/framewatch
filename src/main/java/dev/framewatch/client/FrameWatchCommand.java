package dev.framewatch.client;

import java.util.Collections;
import java.util.List;
import dev.framewatch.FrameWatch;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

public final class FrameWatchCommand extends CommandBase {
    private final ClientMonitor monitor;
    public FrameWatchCommand(ClientMonitor monitor) { this.monitor = monitor; }
    @Override public String getCommandName() { return "framewatch"; }
    @Override public List<String> getCommandAliases() { return Collections.singletonList("fw"); }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public String getCommandUsage(ICommandSender sender) { return "/fw [settings|move|preset <minimal|graph|full>|scale <0.5-2.5>|opacity <0-1>|toggle|details|reset|export|pause|resume|budget <fps>|position <x> <y>|status]"; }

    @Override public void processCommand(ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "toggle" : args[0];
        if ("settings".equals(action)) {
            monitor.openSettings();
        } else if ("move".equals(action)) {
            monitor.openPositionEditor();
        } else if ("preset".equals(action) && args.length == 2) {
            if (!"minimal".equals(args[1]) && !"graph".equals(args[1]) && !"full".equals(args[1])) {
                monitor.message(HudTranslations.text("framewatch.message.presets"));
                return;
            }
            FrameWatch.config.applyPreset(args[1]);
            saveSettings();
            monitor.message(HudTranslations.text("framewatch.message.preset", args[1]));
        } else if ("scale".equals(action) && args.length == 2) {
            FrameWatch.config.hudScale = parseDoubleBounded(sender, args[1], 0.5, 2.5);
            saveSettings();
        } else if ("opacity".equals(action) && args.length == 2) {
            FrameWatch.config.panelOpacity = parseDoubleBounded(sender, args[1], 0, 1);
            saveSettings();
        } else if ("toggle".equals(action)) {
            FrameWatch.config.hudEnabled = !FrameWatch.config.hudEnabled;
            saveSettings();
            monitor.message(HudTranslations.text(FrameWatch.config.hudEnabled
                                ? "framewatch.message.hudShown" : "framewatch.message.hudHidden"));
        } else if ("details".equals(action)) {
            FrameWatch.config.showProfiler = !FrameWatch.config.showProfiler;
            FrameWatch.config.detailed = FrameWatch.config.showProfiler;
            saveSettings();
        } else if ("reset".equals(action)) {
            monitor.reset(); monitor.message(HudTranslations.text("framewatch.message.historyCleared"));
        } else if ("export".equals(action)) {
            monitor.requestExport();
        } else if ("pause".equals(action) || "resume".equals(action)) {
            monitor.setPaused("pause".equals(action));
                        monitor.message(HudTranslations.text(monitor.isPaused()
                                ? "framewatch.message.paused" : "framewatch.message.resumed"));
        } else if ("budget".equals(action) && args.length == 2) {
            int fps = parseIntBounded(sender, args[1], 0, 1000);
            monitor.setBudget(fps);
                        monitor.message(HudTranslations.text("framewatch.message.budget", fps == 0
                                ? HudTranslations.text("framewatch.auto") : fps + " FPS"));
        } else if ("position".equals(action) && args.length == 3) {
            int x = parseIntBounded(sender, args[1], 0, 4096);
            int y = parseIntBounded(sender, args[2], 0, 4096);
            FrameWatch.config.x = x; FrameWatch.config.y = y;
            saveSettings();
        } else if ("status".equals(action)) {
            monitor.message(monitor.status());
        } else monitor.message(getCommandUsage(sender));
    }

    private void saveSettings() {
        FrameWatch.config.changed();
        SettingsPersistence.save();
    }

    @Override public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 2 && "preset".equals(args[0])) return getListOfStringsMatchingLastWord(args, "minimal", "graph", "full");
        return args.length == 1 ? getListOfStringsMatchingLastWord(args,
                "settings", "move", "preset", "scale", "opacity", "toggle", "details", "reset", "export", "pause", "resume", "budget", "position", "status", "help") : null;
    }
}
