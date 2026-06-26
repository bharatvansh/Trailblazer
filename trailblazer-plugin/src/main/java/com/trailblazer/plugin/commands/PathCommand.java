package com.trailblazer.plugin.commands;

import com.trailblazer.api.PathData;
import com.trailblazer.api.Vector3d;
import com.trailblazer.plugin.PathDataManager;
import com.trailblazer.plugin.TrailblazerPlugin;
import com.trailblazer.plugin.rendering.PlayerRenderSettingsManager;
import com.trailblazer.plugin.rendering.RenderMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PathCommand implements CommandExecutor {

    private final TrailblazerPlugin plugin;
    private final com.trailblazer.plugin.PathDataManager pathDataManager;
    private final PlayerRenderSettingsManager renderSettingsManager;

    public PathCommand(TrailblazerPlugin plugin) {
        this.plugin = plugin;
        this.pathDataManager = plugin.getPathDataManager();
        this.renderSettingsManager = plugin.getPlayerRenderSettingsManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "record":
                handleRecord(player, args);
                break;
            case "list":
                handleList(player);
                break;
            case "view":
                handleView(player, args);
                break;
            case "hide":
                handleHide(player, args);
                break;
            case "info":
                handleInfo(player, args);
                break;
            case "rename":
                handleRename(player, args);
                break;
            case "delete":
                handleDelete(player, args);
                break;
            case "color":
                handleColor(player, args);
                break;
            case "spacing":
                handleSpacing(player, args);
                break;
            case "share":
                handleShare(player, args);
                break;
            case "rendermode":
                handleRenderMode(player, args);
                break;
            default:
                sendHelpMessage(player);
                break;
        }
        return true;
    }

    private void handleRecord(Player player, String[] args) {
        var recManager = plugin.getRecordingManager();
        boolean isModded = plugin.getServerPacketHandler().isModdedPlayer(player);
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /trailblazer record <start|stop|cancel|status> [name]", NamedTextColor.RED));
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "start": {
                String name = null;
                if (args.length >= 3) {
                    // join rest as name to allow spaces? Keep simple: single token for now.
                    name = args[2];
                }
                if (recManager.isRecording(player.getUniqueId())) {
                    player.sendMessage(Component.text("Already recording. Use /trailblazer record stop first.", NamedTextColor.YELLOW));
                    return;
                }
                boolean started = recManager.startRecording(player, name);
                if (started) {
                    player.sendMessage(Component.text("Started recording " + (name != null ? name : "(auto-named)") + ".", NamedTextColor.GREEN));
                    // Notify modded clients to update UI
                    if (isModded) {
                        var activeRecording = recManager.getActive(player.getUniqueId());
                        if (activeRecording != null) {
                            plugin.getServerPacketHandler().sendRecordingStarted(player, activeRecording);
                        }
                    }
                } else {
                    player.sendMessage(Component.text("Failed to start recording.", NamedTextColor.RED));
                }
                break; }
            case "status": {
                if (recManager.isRecording(player.getUniqueId())) {
                    var active = recManager.getActive(player.getUniqueId());
                    player.sendMessage(Component.text("Recording '" + active.getName() + "' with " + active.getPoints().size() + " points.", NamedTextColor.GREEN));
                } else {
                    if (isModded) {
                        player.sendMessage(Component.text("No server recording active. Use client '/trailblazer record' to start a local recording.", NamedTextColor.GRAY));
                    } else {
                        player.sendMessage(Component.text("Not currently recording.", NamedTextColor.GRAY));
                    }
                }
                break; }
            case "stop": {
                if (!recManager.isRecording(player.getUniqueId())) {
                    player.sendMessage(Component.text("Not currently recording.", NamedTextColor.YELLOW));
                    return;
                }
                PathData saved = recManager.stopRecording(player, true);
                if (saved != null) {
                    player.sendMessage(Component.text("Saved path '" + saved.getPathName() + "' with " + saved.getPoints().size() + " points.", NamedTextColor.GREEN));
                    
                    if (!plugin.getServerPacketHandler().isModdedPlayer(player)) {
                        plugin.getPathRendererManager().startRendering(player, saved);
                    } else {
                        // Tell client to stop live preview and sync new paths
                        plugin.getServerPacketHandler().sendStopLivePath(player);
                        // Paths are already synced in ServerPacketHandler.handleStopRecording
                    }
                } else {
                    player.sendMessage(Component.text("Recording discarded (not enough points).", NamedTextColor.YELLOW));
                    if (isModded) {
                        plugin.getServerPacketHandler().sendStopLivePath(player);
                    }
                }
                break; }
            case "cancel": {
                if (!recManager.isRecording(player.getUniqueId())) {
                    player.sendMessage(Component.text("Not currently recording.", NamedTextColor.YELLOW));
                    return;
                }
                recManager.cancelRecording(player);
                if (isModded) {
                    plugin.getServerPacketHandler().sendStopLivePath(player);
                }
                player.sendMessage(Component.text("Recording cancelled.", NamedTextColor.YELLOW));
                break; }
            default:
                player.sendMessage(Component.text("Usage: /trailblazer record <start|stop|cancel|status> [name]", NamedTextColor.RED));
        }
    }

    private void handleInfo(Player player, String[] args) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("You have the client mod. Use the client '/trailblazer info' command instead.", NamedTextColor.YELLOW));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /trailblazer info <name> (use quotes for names with spaces)", NamedTextColor.RED));
            return;
        }
        var pr = CommandUtils.parseQuoted(args, 1, true);
        String pathName = pr.value;
        String dimId = currentDimensionId(player.getWorld());
        Optional<PathData> pathOpt = com.trailblazer.api.PathNameMatcher.findByName(
            pathDataManager.loadPaths(player.getWorld().getUID(), player.getUniqueId()).stream()
                .filter(p -> dimId.equals(p.getDimension()))
                .collect(java.util.stream.Collectors.toList()),
            pathName);

        if (pathOpt.isPresent()) {
            PathData path = pathOpt.get();
            List<Vector3d> points = path.getPoints();
            if (points.isEmpty()) {
                player.sendMessage(Component.text("Path '" + pathName + "' has no points.", NamedTextColor.YELLOW));
                return;
            }
            Vector3d start = points.get(0);
            Vector3d end = points.get(points.size() - 1);

            player.sendMessage(Component.text("--- Info for " + pathName + " ---", NamedTextColor.GOLD));
            player.sendMessage(Component.text("Start: ", NamedTextColor.GRAY).append(Component.text(String.format("%.1f, %.1f, %.1f", start.getX(), start.getY(), start.getZ()), NamedTextColor.WHITE)));
            player.sendMessage(Component.text("End:   ", NamedTextColor.GRAY).append(Component.text(String.format("%.1f, %.1f, %.1f", end.getX(), end.getY(), end.getZ()), NamedTextColor.WHITE)));
        } else {
            player.sendMessage(Component.text("Path '" + pathName + "' not found.", NamedTextColor.RED));
        }
    }

    private void handleColor(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /trailblazer color <name> <colorName|#RRGGBB> (quote name if it has spaces)", NamedTextColor.RED));
            return;
        }
        var pr = CommandUtils.parseQuoted(args, 1, false);
        String pathName = pr.value;
        var pr2 = CommandUtils.parseQuoted(args, pr.nextIndex, true);
        String colorArg = pr2.value;
        String dimId2 = currentDimensionId(player.getWorld());
        List<PathData> paths = pathDataManager.loadPaths(player.getWorld().getUID(), player.getUniqueId()).stream()
            .filter(p -> dimId2.equals(p.getDimension()))
            .collect(java.util.stream.Collectors.toList());
    Optional<PathData> pathOpt = com.trailblazer.api.PathNameMatcher.findByName(paths, pathName);
        if (pathOpt.isEmpty()) {
            player.sendMessage(Component.text("Path '" + pathName + "' not found.", NamedTextColor.RED));
            return;
        }
        PathData path = pathOpt.get();
        java.util.Optional<Integer> parsed = com.trailblazer.api.PathColors.parse(colorArg);
        if (parsed.isEmpty()) {
            player.sendMessage(Component.text("Invalid color. Use a name or #RRGGBB.", NamedTextColor.RED));
            return;
        }
        path.setColorArgb(parsed.get());
        // Persist by rewriting file (reuse rename logic pattern)
        pathDataManager.savePath(player.getWorld().getUID(), path);
        player.sendMessage(Component.text("Color for path '" + path.getPathName() + "' set to " + com.trailblazer.api.PathColors.nameOrHex(path.getColorArgb()), NamedTextColor.GREEN));
        
        plugin.getPathRendererManager().startRendering(player, path);
    }

    private void handleView(Player player, String[] args) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("Client mod handles viewing paths. Use the in-game client UI or visibility toggles.", NamedTextColor.YELLOW));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /trailblazer view <name> (quote names with spaces)", NamedTextColor.RED));
            return;
        }
        var pr = CommandUtils.parseQuoted(args, 1, true);
        String pathName = pr.value;
        String dimId3 = currentDimensionId(player.getWorld());
        List<PathData> paths = pathDataManager.loadPaths(player.getWorld().getUID(), player.getUniqueId()).stream()
            .filter(p -> dimId3.equals(p.getDimension()))
            .collect(java.util.stream.Collectors.toList());
        Optional<PathData> pathOpt = com.trailblazer.api.PathNameMatcher.findByName(paths, pathName);

        if (pathOpt.isPresent()) {
            plugin.getPathRendererManager().startRendering(player, pathOpt.get());
            player.sendMessage(Component.text("Showing path: " + pathName, NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Path '" + pathName + "' not found.", NamedTextColor.RED));
        }
    }

    private void handleHide(Player player, String[] args) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("Client mod handles hiding paths. Use the client UI.", NamedTextColor.YELLOW));
            return;
        }
        plugin.getPathRendererManager().stopRendering(player);
        player.sendMessage(Component.text("Path hidden.", NamedTextColor.GREEN));
    }

    private void handleDelete(Player player, String[] args) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("You have the client mod. Use the client '/trailblazer delete' command instead.", NamedTextColor.YELLOW));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /trailblazer delete <name> (quote names with spaces)", NamedTextColor.RED));
            return;
        }
        var pr = CommandUtils.parseQuoted(args, 1, true);
        String pathName = pr.value;
        String dimId4 = currentDimensionId(player.getWorld());
        Optional<PathData> pathOpt = com.trailblazer.api.PathNameMatcher.findByName(
            pathDataManager.loadPaths(player.getWorld().getUID(), player.getUniqueId()).stream()
                .filter(p -> dimId4.equals(p.getDimension()))
                .collect(java.util.stream.Collectors.toList()),
            pathName);

        if (pathOpt.isPresent()) {
            PathData path = pathOpt.get();
            boolean deleted = pathDataManager.deletePath(player.getWorld().getUID(), player.getUniqueId(), path.getPathId());
            if (deleted) {
                if (!plugin.getServerPacketHandler().isModdedPlayer(player)) {
                    plugin.getPathRendererManager().stopRendering(player);
                }
                player.sendMessage(Component.text("Path '" + pathName + "' has been removed from your list.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("Failed to remove path '" + pathName + "'.", NamedTextColor.RED));
            }
        } else {
            player.sendMessage(Component.text("Path '" + pathName + "' not found in your list.", NamedTextColor.RED));
        }
    }

    private void handleList(Player player) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("Client mod handles listing paths. Use the Trailblazer UI.", NamedTextColor.YELLOW));
            return;
        }

        String dimId5 = currentDimensionId(player.getWorld());
        List<PathData> paths = pathDataManager.loadPaths(player.getWorld().getUID(), player.getUniqueId()).stream()
            .filter(p -> dimId5.equals(p.getDimension()))
            .collect(java.util.stream.Collectors.toList());
        if (paths.isEmpty()) {
            player.sendMessage(Component.text("You have no saved paths on this server.", NamedTextColor.GRAY));
            return;
        }

        paths.sort((a, b) -> Long.compare(b.getCreationTimestamp(), a.getCreationTimestamp()));
        player.sendMessage(Component.text("--- Saved Paths ---", NamedTextColor.GOLD));
        int index = 1;
        for (PathData path : paths) {
            boolean owner = path.getOwnerUUID().equals(player.getUniqueId());
            NamedTextColor nameColor = owner ? NamedTextColor.GREEN : NamedTextColor.AQUA;
            Component line = Component.text(index++ + ". ", NamedTextColor.GRAY)
                    .append(Component.text(path.getPathName(), nameColor))
                    .append(Component.text(" (" + path.getPoints().size() + " points, " + friendlyDimension(path.getDimension()) + ")", NamedTextColor.DARK_GRAY));
            if (!owner) {
                line = line.append(Component.text(" [shared]", NamedTextColor.BLUE));
            }
            player.sendMessage(line);
        }
        player.sendMessage(Component.text("Use '/trailblazer view <name>' to show a path.", NamedTextColor.GRAY));
    }

    private String friendlyDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return "unknown";
        }
        return switch (dimensionId) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> dimensionId;
        };
    }

    private void handleRename(Player player, String[] args) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("You have the client mod. Use the client '/trailblazer rename' command instead.", NamedTextColor.YELLOW));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /trailblazer rename <oldName> <newName> (quote names with spaces)", NamedTextColor.RED));
            return;
        }
        var pr = CommandUtils.parseQuoted(args, 1, false);
        String oldName = pr.value;
        var pr2 = CommandUtils.parseQuoted(args, pr.nextIndex, true);
        String rawNewName = pr2.value;
        String sanitizedNewName = com.trailblazer.api.PathNameSanitizer.sanitize(rawNewName);
        String dimId6 = currentDimensionId(player.getWorld());
        List<PathData> paths = pathDataManager.loadPaths(player.getWorld().getUID(), player.getUniqueId()).stream()
            .filter(p -> dimId6.equals(p.getDimension()))
            .collect(java.util.stream.Collectors.toList());

        // Check if a path with the new name already exists to avoid duplicates.
        if (paths.stream().anyMatch(p -> p.getPathName().equalsIgnoreCase(sanitizedNewName))) {
            player.sendMessage(Component.text("A path with the name '" + sanitizedNewName + "' already exists.", NamedTextColor.RED));
            return;
        }

    Optional<PathData> pathOpt = com.trailblazer.api.PathNameMatcher.findByName(paths, oldName);

        if (pathOpt.isPresent()) {
            // A player can rename any path in their list. For shared paths, this is just a local alias.
            pathDataManager.renamePath(player.getWorld().getUID(), player.getUniqueId(), pathOpt.get().getPathId(), sanitizedNewName);
            if (!sanitizedNewName.equals(rawNewName)) {
                player.sendMessage(Component.text("Path '" + oldName + "' renamed to sanitized '" + sanitizedNewName + "' (invalid characters were adjusted).", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("Path '" + oldName + "' renamed to '" + sanitizedNewName + "'.", NamedTextColor.GREEN));
            }
        } else {
            player.sendMessage(Component.text("Path '" + oldName + "' not found.", NamedTextColor.RED));
        }
    }

    private void handleRenderMode(Player player, String[] args) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
             player.sendMessage(Component.text("You have the client mod! Please use the 'G' key to change render modes.", NamedTextColor.YELLOW));
             return;
        }

        if (args.length < 2) {
            RenderMode currentMode = renderSettingsManager.getRenderMode(player);
            player.sendMessage(Component.text("Your current render mode is " + currentMode.name() + ".", NamedTextColor.GRAY));
            // Show the three canonical, client-friendly aliases for consistency with the client commands
            player.sendMessage(Component.text("Usage: /trailblazer rendermode <trail | arrows>", NamedTextColor.RED));
            return;
        }

        String modeName = args[1].toUpperCase();
        Optional<RenderMode> modeOpt = RenderMode.fromString(modeName);

        if (modeOpt.isPresent()) {
            renderSettingsManager.setRenderMode(player, modeOpt.get());
            player.sendMessage(Component.text("Render mode set to " + modeOpt.get().name(), NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Invalid render mode: " + modeName, NamedTextColor.RED));
        }
    }

    private void handleSpacing(Player player, String[] args) {
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("Client mod handles spacing. Use the client UI if available.", NamedTextColor.YELLOW));
            return;
        }
        if (args.length < 2) {
            double current = renderSettingsManager.getMarkerSpacing(player);
            player.sendMessage(Component.text("Current marker spacing: " + current + " blocks.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("Usage: /trailblazer spacing <blocks> (e.g. 1.0, 2.5)", NamedTextColor.RED));
            return;
        }
        try {
            double spacing = Double.parseDouble(args[1]);
            if (spacing <= 0) {
                player.sendMessage(Component.text("Spacing must be positive.", NamedTextColor.RED));
                return;
            }
            renderSettingsManager.setMarkerSpacing(player, spacing);
            player.sendMessage(Component.text("Marker spacing set to " + spacing + " blocks.", NamedTextColor.GREEN));
        } catch (NumberFormatException ex) {
            player.sendMessage(Component.text("Invalid number: " + args[1], NamedTextColor.RED));
        }
    }

    private void handleShare(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /trailblazer share <path-name> <player1,player2,...> (quote path-name if it has spaces)", NamedTextColor.RED));
            return;
        }

        var pr = CommandUtils.parseQuoted(args, 1, true);
        String pathName = pr.value;
        List<String> targetPlayerNames = Arrays.asList(args[pr.nextIndex].split(","));

        List<Player> targetPlayers = targetPlayerNames.stream()
                .map(name -> plugin.getServer().getPlayer(name.trim()))
                .filter(p -> p != null && p.isOnline())
                .collect(Collectors.toList());

        if (targetPlayers.isEmpty()) {
            player.sendMessage(Component.text("No valid online players found to share with.", NamedTextColor.RED));
            return;
        }

        String dimId7 = currentDimensionId(player.getWorld());
        Optional<PathData> pathOpt = pathDataManager.loadPaths(player.getWorld().getUID(), player.getUniqueId()).stream()
            .filter(p -> dimId7.equals(p.getDimension()))
            .filter(p -> p.getPathName().equalsIgnoreCase(pathName))
                .findFirst();

        if (pathOpt.isPresent()) {
            PathData path = pathOpt.get();
            if (path.getOwnerUUID().equals(player.getUniqueId())) {
                List<String> succeeded = new ArrayList<>();
                List<String> alreadyHad = new ArrayList<>();
                java.util.Map<String, String> failed = new java.util.HashMap<>();

                java.util.UUID sourceWorldUid = player.getWorld().getUID();

                for (Player targetPlayer : targetPlayers) {
                    if (targetPlayer.getUniqueId().equals(player.getUniqueId())) {
                        player.sendMessage(Component.text("You cannot share a path with yourself.", NamedTextColor.YELLOW));
                        continue;
                    }

                    if (path.isSharedWith(targetPlayer.getUniqueId())) {
                        // The target already has access (idempotent behavior)
                        alreadyHad.add(targetPlayer.getName());
                        continue;
                    }

                    path.addShare(targetPlayer.getUniqueId());

                    // Avoid cross-world bleeding: only deliver/render immediately if they're in the same world.
                    if (!sourceWorldUid.equals(targetPlayer.getWorld().getUID())) {
                        targetPlayer.sendMessage(Component.text(player.getName() + " shared a path with you: " + path.getPathName() + ". It will appear when you join their world.", NamedTextColor.AQUA));
                        succeeded.add(targetPlayer.getName());
                        continue;
                    }

                    boolean targetIsModded = false;
                    try {
                        targetIsModded = plugin.getServerPacketHandler().isModdedPlayer(targetPlayer);
                    } catch (Exception ignored) {}

                    try {
                        if (targetIsModded) {
                            plugin.getServerPacketHandler().sendSharePath(targetPlayer, path);
                            targetPlayer.sendMessage(Component.text(player.getName() + " has shared a path with you: " + path.getPathName(), NamedTextColor.AQUA));
                        } else {
                            plugin.getPathRendererManager().startRendering(targetPlayer, path);
                            targetPlayer.sendMessage(Component.text(player.getName() + " has shared a path with you: " + path.getPathName(), NamedTextColor.AQUA));
                            targetPlayer.sendMessage(Component.text("It is now being displayed. Use '/path hide' to hide it or '/path view " + path.getPathName() + "' to see it again.", NamedTextColor.GRAY));
                        }
                        succeeded.add(targetPlayer.getName());
                    } catch (Exception ex) {
                        // Record failure with message so sender can act accordingly
                        String reason = ex.getMessage() != null ? ex.getMessage() : "unknown error";
                        failed.put(targetPlayer.getName(), reason);
                    }
                }
                
                if (!succeeded.isEmpty()) {
                    pathDataManager.savePath(sourceWorldUid, path);
                }
                // Send a clear per-target result summary to the sender
                if (!succeeded.isEmpty()) {
                    player.sendMessage(Component.text("Path '" + pathName + "' successfully shared with: " + String.join(", ", succeeded) + ".", NamedTextColor.GREEN));
                }
                if (!alreadyHad.isEmpty()) {
                    player.sendMessage(Component.text(String.join(", ", alreadyHad) + " already have their own copy of this path.", NamedTextColor.YELLOW));
                }

                if (!failed.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    failed.forEach((name, reason) -> sb.append(name).append(" (" + reason + ")").append(", "));
                    String list = sb.length() > 2 ? sb.substring(0, sb.length() - 2) : sb.toString();
                    player.sendMessage(Component.text("Failed to share with: " + list, NamedTextColor.RED));
                }
            } else {
                player.sendMessage(Component.text("You can only share paths that you own.", NamedTextColor.RED));
            }
        } else {
            player.sendMessage(Component.text("Path '" + pathName + "' not found.", NamedTextColor.RED));
        }
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(Component.text("--- Trailblazer Help ---", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/trailblazer record <start|stop|cancel|status> [name]", NamedTextColor.YELLOW).append(Component.text(" - Recording commands", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer list", NamedTextColor.YELLOW).append(Component.text(" - List saved paths", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer view <name>", NamedTextColor.YELLOW).append(Component.text(" - Show a path", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer hide", NamedTextColor.YELLOW).append(Component.text(" - Hide current path", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer info <name>", NamedTextColor.YELLOW).append(Component.text(" - Get path coordinates", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer rename <old> <new>", NamedTextColor.YELLOW).append(Component.text(" - Rename a path", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer delete <name>", NamedTextColor.YELLOW).append(Component.text(" - Delete a path", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer color <name> <color>", NamedTextColor.YELLOW).append(Component.text(" - Change path color", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer spacing <blocks>", NamedTextColor.YELLOW).append(Component.text(" - Set marker spacing (e.g. 3.0)", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer share <path> <players>", NamedTextColor.YELLOW).append(Component.text(" - Share path with players", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("/trailblazer rendermode <trail|arrows>", NamedTextColor.YELLOW).append(Component.text(" - Change render mode", NamedTextColor.WHITE)));
        if (plugin.getServerPacketHandler().isModdedPlayer(player)) {
            player.sendMessage(Component.text("Client mod detected: use client commands for better UI experience", NamedTextColor.GRAY));
        }
    }

    // Maps the current Bukkit world environment to our canonical dimension identifiers used in PathData
    private String currentDimensionId(org.bukkit.World world) {
        if (world == null) return "minecraft:overworld";
        org.bukkit.World.Environment env = world.getEnvironment();
        return switch (env) {
            case NORMAL -> "minecraft:overworld";
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
    }
}