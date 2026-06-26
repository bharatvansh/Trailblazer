package com.trailblazer.fabric.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.trailblazer.api.PathData;
import com.trailblazer.fabric.ClientPathManager;
import com.trailblazer.fabric.ClientPathManager.PathOrigin;
import com.trailblazer.fabric.RenderSettingsManager;
import com.trailblazer.fabric.networking.payload.c2s.UpdatePathMetadataPayload;
import com.trailblazer.fabric.rendering.RenderMode;
import com.trailblazer.fabric.sharing.PathShareSender;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public final class TrailblazerCommand {

    private static ClientPathManager pathManager;
    private static RenderSettingsManager renderSettingsManager;
    private static boolean registered = false;

    private TrailblazerCommand() {}

    public static void register(ClientPathManager manager, RenderSettingsManager settingsManager) {
        if (registered) {
            return;
        }
        com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("TrailblazerCommand: registering client commands");
        pathManager = manager;
        renderSettingsManager = settingsManager;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("TrailblazerCommand: callback invoked, building command tree");

            var recordNode = literal("record")
                .executes(ctx -> toggleRecording(ctx.getSource()))
                .then(literal("start").executes(ctx -> startRecording(ctx.getSource())))
                .then(literal("stop").executes(ctx -> stopRecording(ctx.getSource())))
                .then(literal("cancel").executes(ctx -> cancelRecording(ctx.getSource())))
                .then(literal("status").executes(ctx -> showRecordingStatus(ctx.getSource())));

            com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> trailblazerNode = literal("trailblazer")
                .executes(ctx -> sendHelp(ctx.getSource()))
                // Ordered for client-only preference: record -> list -> view -> hide -> info -> rename -> delete -> color -> share -> rendermode -> help
                .then(recordNode)
                .then(literal("list").executes(ctx -> listPaths(ctx.getSource())))
                .then(literal("view")
                    .then(argument("name", StringArgumentType.greedyString())
                        .suggests(TrailblazerCommand::suggestPathNames)
                        .executes(ctx -> viewPath(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("hide")
                    .executes(ctx -> hideAll(ctx.getSource()))
                    .then(argument("name", StringArgumentType.greedyString())
                        .suggests(TrailblazerCommand::suggestPathNames)
                        .executes(ctx -> hideOne(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("info")
                    .then(argument("name", StringArgumentType.greedyString())
                        .suggests(TrailblazerCommand::suggestPathNames)
                        .executes(ctx -> showInfo(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("rename")
                    .then(argument("oldName", StringArgumentType.string())
                        .suggests(TrailblazerCommand::suggestRenameNames)
                        .then(argument("newName", StringArgumentType.string())
                            .executes(ctx -> renamePath(ctx.getSource(), StringArgumentType.getString(ctx, "oldName"), StringArgumentType.getString(ctx, "newName"))))))
                .then(literal("delete")
                    .then(argument("name", StringArgumentType.greedyString())
                        .suggests(TrailblazerCommand::suggestPathNames)
                        .executes(ctx -> deletePath(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("color")
                    .then(argument("name", StringArgumentType.string())
                        .suggests(TrailblazerCommand::suggestPathNames)
                        .then(argument("color", StringArgumentType.string())
                            .suggests(TrailblazerCommand::suggestColorNames)
                            .executes(ctx -> setColor(ctx.getSource(), StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "color"))))))
                .then(literal("share")
                    .then(argument("name", StringArgumentType.string())
                        .suggests(TrailblazerCommand::suggestPathNames)
                        .then(argument("players", StringArgumentType.string())
                            .suggests(TrailblazerCommand::suggestPlayerNames)
                            .executes(ctx -> sharePath(ctx.getSource(), StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "players"))))))
                .then(literal("rendermode")
                    .then(argument("mode", StringArgumentType.string())
                        .suggests((c,b)->suggestRenderModes(b))
                        .executes(ctx -> setRenderMode(ctx.getSource(), StringArgumentType.getString(ctx, "mode")))))
                .then(literal("help").executes(ctx -> sendHelp(ctx.getSource())));

            var builtNode = dispatcher.register(trailblazerNode);
            dispatcher.register(literal("tbl").redirect(builtNode));
            registered = true;
            com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("TrailblazerCommand: registration complete");
        });
    }

    private static int sendHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("--- Trailblazer Help ---").withStyle(ChatFormatting.GOLD));
        source.sendFeedback(Component.literal("/trailblazer record [start|stop|cancel|status]").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Recording commands (use 'record' alone to toggle)" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer list").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - List your paths").withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer view <name>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Show a path" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer hide [name]").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Hide path(s)" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer info <name>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Get path coordinates" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer rename <old> <new>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Rename a path" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer delete <name>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Delete a path" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer color <name> <color>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Change path color" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer share <name> <players>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Share path with players" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("/trailblazer rendermode <trail|markers|arrows>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" - Change render mode" ).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("Tip: Press M for UI, R to toggle recording, G to cycle render mode").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static final Map<String, RenderMode> MODE_ALIASES = Map.ofEntries(
        Map.entry("solid", RenderMode.SOLID_LINE),
        Map.entry("solid_line", RenderMode.SOLID_LINE),
        Map.entry("trail", RenderMode.DASHED_LINE),
        Map.entry("dashed", RenderMode.DASHED_LINE),
        Map.entry("dash", RenderMode.DASHED_LINE),
        Map.entry("dashed_line", RenderMode.DASHED_LINE),
        Map.entry("markers", RenderMode.SPACED_MARKERS),
        Map.entry("marker", RenderMode.SPACED_MARKERS),
        Map.entry("spaced_markers", RenderMode.SPACED_MARKERS),
        Map.entry("arrows", RenderMode.DIRECTIONAL_ARROWS),
        Map.entry("arrow", RenderMode.DIRECTIONAL_ARROWS),
        Map.entry("directional_arrows", RenderMode.DIRECTIONAL_ARROWS)
    );

    private static CompletableFuture<Suggestions> suggestRenderModes(SuggestionsBuilder builder) {
        builder.suggest("solid");
        builder.suggest("trail");
        builder.suggest("markers");
        builder.suggest("arrows");
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestColorNames(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        com.trailblazer.api.PathColors.getColorNames().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPlayerNames(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        var handler = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (handler != null) {
            handler.getOnlinePlayers().stream()
                .map(p -> p.getProfile().name())
                .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private static int viewPath(FabricClientCommandSource source, String name) {
        UUID found = findPathIdByName(name);
        if (found == null) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }
        pathManager.setPathVisible(found);
        source.sendFeedback(Component.literal("Showing path: " + name).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int setColor(FabricClientCommandSource source, String name, String colorArg) {
        UUID found = findPathIdByName(name);
        if (found == null) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }

        java.util.Optional<Integer> parsed = com.trailblazer.api.PathColors.parse(colorArg);
        if (parsed.isEmpty()) {
            source.sendError(Component.literal("Invalid color. Use a name or #RRGGBB."));
            return 0;
        }
        int color = parsed.get();

        // Update local copy
        PathData path = null;
        for (PathData p : pathManager.getMyPaths()) if (p.getPathId().equals(found)) path = p;
        if (path == null) {
            for (PathData p : pathManager.getSharedPaths()) if (p.getPathId().equals(found)) path = p;
        }
        if (path == null) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }

        path.setColorArgb(color);
        pathManager.onPathUpdated(path);

        // If this is a server-owned path, send metadata update to server so it persists
        com.trailblazer.fabric.ClientPathManager.PathOrigin origin = pathManager.getPathOrigin(found);
        if (origin == com.trailblazer.fabric.ClientPathManager.PathOrigin.SERVER_OWNED && ClientPlayNetworking.canSend(UpdatePathMetadataPayload.TYPE)) {
            try {
                ClientPlayNetworking.send(new UpdatePathMetadataPayload(found, path.getPathName(), color));
            } catch (Exception ignored) {}
        }

        source.sendFeedback(Component.literal("Color set to " + com.trailblazer.api.PathColors.nameOrHex(color)).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int sharePath(FabricClientCommandSource source, String name, String playersCsv) {
        UUID found = findPathIdByName(name);
        if (found == null) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }

        PathData path = null;
        for (PathData p : pathManager.getMyPaths()) if (p.getPathId().equals(found)) path = p;
        if (path == null) {
            for (PathData p : pathManager.getSharedPaths()) if (p.getPathId().equals(found)) path = p;
        }
        if (path == null) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }

        // resolve player names (comma separated)
        String[] parts = playersCsv.split(",");
        List<UUID> recipients = new ArrayList<>();
        var handler = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (handler == null) {
            source.sendError(Component.literal("No network handler available."));
            return 0;
        }
        List<String> matchedNames = new ArrayList<>();
        for (String p : parts) {
            String nameTrim = p.trim();
            if (nameTrim.isEmpty()) continue;
            for (var entry : handler.getOnlinePlayers()) {
                if (entry.getProfile().name().equalsIgnoreCase(nameTrim)) {
                    recipients.add(entry.getProfile().id());
                    matchedNames.add(entry.getProfile().name());
                    break;
                }
            }
        }

        if (recipients.isEmpty()) {
            source.sendError(Component.literal("No valid online players found to share with."));
            return 0;
        }

        try {
            PathShareSender.sharePath(path, recipients);
            source.sendFeedback(Component.literal("Share request sent for '" + name + "' to: " + String.join(", ", matchedNames)).withStyle(ChatFormatting.GREEN));
        } catch (Exception ex) {
            source.sendError(Component.literal("Failed to send share request: " + ex.getMessage()));
            return 0;
        }

        return 1;
    }

    private static int hideOne(FabricClientCommandSource source, String name) {
        UUID found = findPathIdByName(name);
        if (found == null) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }
        pathManager.setPathHidden(found);
        source.sendFeedback(Component.literal("Hid path: " + name).withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int hideAll(FabricClientCommandSource source) {
        pathManager.hideAllPaths();
        source.sendFeedback(Component.literal("All paths hidden.").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static UUID findPathIdByName(String name) {
        for (PathData p : pathManager.getMyPaths()) {
            if (p.getPathName().equalsIgnoreCase(name)) return p.getPathId();
        }
        for (PathData p : pathManager.getSharedPaths()) {
            if (p.getPathName().equalsIgnoreCase(name)) return p.getPathId();
        }
        return null;
    }

    private static int setRenderMode(FabricClientCommandSource source, String modeInput) {
        if (renderSettingsManager == null) {
            source.sendError(Component.literal("Render settings are not available."));
            return 0;
        }
        RenderMode mode = MODE_ALIASES.get(modeInput.toLowerCase());
        if (mode == null) {
            source.sendError(Component.literal("Unknown render mode: " + modeInput));
            return 0;
        }
        renderSettingsManager.setRenderMode(mode);
        MutableComponent feedback = Component.literal("Render mode set to ").withStyle(ChatFormatting.GREEN)
            .append(mode.getDisplayText().copy());
        source.sendFeedback(feedback);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestRenameNames(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        pathManager.getMyPaths().stream()
            .filter(p -> {
                var origin = pathManager.getPathOrigin(p.getPathId());
                return origin == PathOrigin.LOCAL || origin == PathOrigin.SERVER_OWNED;
            })
            .map(PathData::getPathName)
            .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPathNames(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        Stream<String> myPaths = pathManager.getMyPaths().stream().map(PathData::getPathName);
        Stream<String> sharedPaths = pathManager.getSharedPaths().stream().map(PathData::getPathName);
        Stream.concat(myPaths, sharedPaths)
            .distinct()
            .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static int toggleRecording(FabricClientCommandSource source) {
        boolean isRecording = pathManager.isRecording();
        boolean useServer = pathManager.shouldUseServerRecording();
        
        com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("/trailblazer record (toggle) command executed: isRecording={}, useServerRecording={}", isRecording, useServer);

        if (isRecording) {
            if (useServer) {
                pathManager.sendStopRecordingRequest(true);
                source.sendFeedback(Component.literal("Stopping server-side recording...").withStyle(ChatFormatting.GREEN));
            } else {
                pathManager.stopRecordingLocal();
                source.sendFeedback(Component.literal("Stopped local recording.").withStyle(ChatFormatting.GREEN));
            }
        } else {
            if (useServer) {
                com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("Toggle command: Using SERVER recording");
                pathManager.sendStartRecordingRequest(null);
                source.sendFeedback(Component.literal("Started server-side recording.").withStyle(ChatFormatting.GREEN));
            } else {
                com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("Toggle command: Using LOCAL recording");
                pathManager.startRecordingLocal();
                source.sendFeedback(Component.literal("Started local recording.").withStyle(ChatFormatting.GREEN));
            }
        }
        return 1;
    }

    private static int startRecording(FabricClientCommandSource source) {
        if (pathManager.isRecording()) {
            source.sendError(Component.literal("Already recording. Use /trailblazer record stop or cancel."));
            return 0;
        }
        
        com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("/trailblazer record start command executed");
        boolean useServer = pathManager.shouldUseServerRecording();
        
        if (useServer) {
            com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("Command: Using SERVER recording");
            pathManager.sendStartRecordingRequest(null);
            source.sendFeedback(Component.literal("Started server-side recording.").withStyle(ChatFormatting.GREEN));
        } else {
            com.trailblazer.fabric.TrailblazerFabricClient.LOGGER.info("Command: Using LOCAL recording");
            pathManager.startRecordingLocal();
            source.sendFeedback(Component.literal("Started local recording.").withStyle(ChatFormatting.GREEN));
        }
        return 1;
    }

    private static int stopRecording(FabricClientCommandSource source) {
        if (!pathManager.isRecording()) {
            source.sendError(Component.literal("No active recording."));
            return 0;
        }
        
        if (pathManager.shouldUseServerRecording()) {
            pathManager.sendStopRecordingRequest(true);
            source.sendFeedback(Component.literal("Stopping server-side recording...").withStyle(ChatFormatting.GREEN));
        } else {
            pathManager.stopRecordingLocal();
            source.sendFeedback(Component.literal("Stopped local recording.").withStyle(ChatFormatting.GREEN));
        }
        return 1;
    }

    private static int cancelRecording(FabricClientCommandSource source) {
        if (!pathManager.isRecording()) {
            source.sendError(Component.literal("No active recording."));
            return 0;
        }
        
        if (pathManager.shouldUseServerRecording()) {
            pathManager.sendStopRecordingRequest(false);
            source.sendFeedback(Component.literal("Cancelling server-side recording (discarded path).").withStyle(ChatFormatting.YELLOW));
        } else {
            pathManager.cancelRecordingLocal();
            source.sendFeedback(Component.literal("Cancelled local recording (discarded path).").withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    private static int showRecordingStatus(FabricClientCommandSource source) {
        boolean isRecording = pathManager.isRecording();
        String label;
        if (isRecording) {
            // Try to get recording path (works for both local and server recordings)
            var path = pathManager.getRecordingPath();
            if (path == null) {
                // Fallback: check if we have live path updates (server recording)
                var livePath = pathManager.getLivePath();
                int points = livePath != null ? livePath.getPoints().size() : 0;
                label = "Recording (server) (" + points + " points)";
            } else {
                int points = path.getPoints().size();
                String pathName = path.getPathName();
                boolean isServerRecording = pathManager.shouldUseServerRecording();
                label = "Recording '" + pathName + "' (" + points + " points)" + (isServerRecording ? " [Server]" : " [Local]");
            }
        } else {
            label = "Not recording.";
        }
        source.sendFeedback(Component.literal(label).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int listPaths(FabricClientCommandSource source) {
        List<PathData> localPaths = new ArrayList<>();
        List<PathData> serverShares = new ArrayList<>();
        Set<UUID> serverShareIds = new HashSet<>();

        for (PathData path : pathManager.getMyPaths()) {
            PathOrigin origin = pathManager.getPathOrigin(path.getPathId());
            switch (origin) {
                case LOCAL, SERVER_OWNED -> localPaths.add(path);
                case SERVER_SHARED -> {
                    if (serverShareIds.add(path.getPathId())) {
                        serverShares.add(path);
                    }
                }
            }
        }

        for (PathData path : pathManager.getSharedPaths()) {
            PathOrigin origin = pathManager.getPathOrigin(path.getPathId());
            if (origin == PathOrigin.SERVER_SHARED && serverShareIds.add(path.getPathId())) {
                serverShares.add(path);
            }
        }

        source.sendFeedback(Component.literal("--- Your Paths ---").withStyle(ChatFormatting.GOLD));
        if (localPaths.isEmpty()) {
            source.sendFeedback(Component.literal("No locally-owned paths.").withStyle(ChatFormatting.GRAY));
        } else {
            localPaths.forEach(path -> source.sendFeedback(formatListEntry(path)));
        }

        source.sendFeedback(Component.literal("--- Shared With You ---").withStyle(ChatFormatting.GOLD));
        if (serverShares.isEmpty()) {
            source.sendFeedback(Component.literal("No shared paths loaded.").withStyle(ChatFormatting.GRAY));
        } else {
            serverShares.forEach(path -> source.sendFeedback(formatListEntry(path)));
        }
        return 1;
    }

    private static Component formatListEntry(PathData path) {
        PathOrigin origin = pathManager.getPathOrigin(path.getPathId());
        String originLabel = switch (origin) {
            case LOCAL -> " (Local)";
            case SERVER_OWNED -> " (Server copy)";
            case SERVER_SHARED -> " (Server share)";
        };
        return Component.literal(path.getPathName()).withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(originLabel).withStyle(ChatFormatting.GRAY));
    }

    private static int showInfo(FabricClientCommandSource source, String name) {
        Optional<PathData> pathOpt = pathManager.getMyPaths().stream()
            .filter(p -> p.getPathName().equalsIgnoreCase(name))
            .findFirst()
            .or(() -> pathManager.getSharedPaths().stream()
                .filter(p -> p.getPathName().equalsIgnoreCase(name))
                .findFirst());

        if (pathOpt.isEmpty()) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }

        PathData path = pathOpt.get();
        java.util.List<com.trailblazer.api.Vector3d> points = path.getPoints();
        if (points.isEmpty()) {
            source.sendFeedback(Component.literal("Path '" + name + "' has no points.").withStyle(ChatFormatting.YELLOW));
            return 1;
        }
        com.trailblazer.api.Vector3d start = points.get(0);
        com.trailblazer.api.Vector3d end = points.get(points.size() - 1);

        source.sendFeedback(Component.literal("--- Info for " + name + " ---").withStyle(ChatFormatting.GOLD));
        source.sendFeedback(Component.literal("Start: ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.format("%.1f, %.1f, %.1f", start.getX(), start.getY(), start.getZ())).withStyle(ChatFormatting.WHITE)));
        source.sendFeedback(Component.literal("End:   ").withStyle(ChatFormatting.GRAY).append(Component.literal(String.format("%.1f, %.1f, %.1f", end.getX(), end.getY(), end.getZ())).withStyle(ChatFormatting.WHITE)));
        return 1;
    }

    private static int deletePath(FabricClientCommandSource source, String name) {
        Optional<PathData> pathOpt = pathManager.getMyPaths().stream()
            .filter(p -> p.getPathName().equalsIgnoreCase(name))
            .findFirst();

        if (pathOpt.isEmpty()) {
            source.sendError(Component.literal("Path not found: " + name));
            return 0;
        }

        PathData path = pathOpt.get();
        UUID pathId = path.getPathId();
        PathOrigin origin = pathManager.getPathOrigin(pathId);

        switch (origin) {
            case LOCAL -> {
                pathManager.deletePath(pathId);
                source.sendFeedback(Component.literal("Deleted path locally: " + name).withStyle(ChatFormatting.GREEN));
            }
            case SERVER_OWNED, SERVER_SHARED -> {
                // If server supports delete payload, request server deletion for SERVER_OWNED paths.
                if (origin == PathOrigin.SERVER_OWNED && ClientPlayNetworking.canSend(com.trailblazer.fabric.networking.payload.c2s.DeletePathPayload.TYPE)) {
                    try {
                        ClientPlayNetworking.send(new com.trailblazer.fabric.networking.payload.c2s.DeletePathPayload(pathId));
                        source.sendFeedback(Component.literal("Requested server deletion for: " + name).withStyle(ChatFormatting.YELLOW));
                    } catch (Exception ignored) {
                        // Fallback: remove locally from client list if send fails
                        pathManager.removeServerPath(pathId);
                        source.sendFeedback(Component.literal("Removed server-synced path from your list: " + name).withStyle(ChatFormatting.YELLOW));
                    }
                } else {
                    // For server-shared (or when server delete not supported) just remove client-side entry
                    pathManager.removeServerPath(pathId);
                    source.sendFeedback(Component.literal("Removed server-synced path from your list: " + name).withStyle(ChatFormatting.YELLOW));
                }
            }
        }
        return 1;
    }

    private static int renamePath(FabricClientCommandSource source, String oldName, String newName) {
        Optional<PathData> pathOpt = pathManager.getMyPaths().stream()
            .filter(p -> p.getPathName().equalsIgnoreCase(oldName))
            .findFirst();

        if (pathOpt.isEmpty()) {
            source.sendError(Component.literal("Path not found: " + oldName));
            return 0;
        }

        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            source.sendError(Component.literal("New name cannot be empty."));
            return 0;
        }

        PathData path = pathOpt.get();
        UUID pathId = path.getPathId();
        PathOrigin origin = pathManager.getPathOrigin(pathId);

        if (origin == PathOrigin.SERVER_SHARED) {
            source.sendError(Component.literal("Server-shared paths cannot be renamed locally."));
            return 0;
        }

        if (origin == PathOrigin.SERVER_OWNED) {
            // Request server-side rename so server persists authoritative name
            if (ClientPlayNetworking.canSend(UpdatePathMetadataPayload.TYPE)) {
                try {
                    // send current color back along with requested name
                    ClientPlayNetworking.send(new UpdatePathMetadataPayload(pathId, trimmed, path.getColorArgb()));
                    source.sendFeedback(Component.literal("Requested server rename for '" + oldName + "' -> '" + trimmed + "'.").withStyle(ChatFormatting.GREEN));
                    return 1;
                } catch (Exception ex) {
                    source.sendError(Component.literal("Failed to send rename request: " + ex.getMessage()));
                    return 0;
                }
            } else {
                source.sendError(Component.literal("Server does not support remote rename."));
                return 0;
            }
        }

        boolean nameTaken = pathManager.getMyPaths().stream()
            .anyMatch(p -> !p.getPathId().equals(pathId) && p.getPathName().equalsIgnoreCase(trimmed));
        if (nameTaken) {
            source.sendError(Component.literal("A path with that name already exists."));
            return 0;
        }

        path.setPathName(trimmed);
        pathManager.onPathUpdated(path);
        source.sendFeedback(Component.literal("Renamed '" + oldName + "' to '" + trimmed + "'.").withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
