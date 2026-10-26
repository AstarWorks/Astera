package com.astarworks.astera.e2e;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only observation: player positions and ownership checks run on each player's owner. */
public final class FoliaE2eProbe extends JavaPlugin {
    private final E2eStateProbe state = new E2eStateProbe(this);
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender)) return false;
        if (command.getName().equals("e2eprobe")) return state.command(sender, args);
        if (args.length != 1) return false;
        String token = args[0];
        Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            var players = Bukkit.getOnlinePlayers().toArray(Player[]::new);
            var positions = new ConcurrentHashMap<String, Location>();
            var snapshots = new CompletableFuture<?>[players.length];
            for (int i = 0; i < players.length; i++) {
                var player = players[i];
                var ready = new CompletableFuture<Void>();
                snapshots[i] = ready;
                player.getScheduler().run(this, task -> {
                    positions.put(player.getName(), player.getLocation());
                    ready.complete(null);
                }, () -> ready.completeExceptionally(new IllegalStateException("player retired")));
            }
            CompletableFuture.allOf(snapshots).thenRun(() -> {
                for (var player : players) {
                    player.getScheduler().run(this, task -> {
                        String owns = positions.entrySet().stream()
                            .filter(entry -> Bukkit.isOwnedByCurrentRegion(entry.getValue()))
                            .map(entry -> entry.getKey()).sorted().collect(Collectors.joining(","));
                        var pos = player.getLocation();
                        getLogger().info("FOLIA_E2E_REGION " + token + " player=" + player.getName()
                            + " chunk=" + pos.getChunk().getX() + "," + pos.getChunk().getZ()
                            + " thread=" + Thread.currentThread().getName() + " owns=" + owns);
                    }, null);
                }
            });
        });
        return true;
    }
}
