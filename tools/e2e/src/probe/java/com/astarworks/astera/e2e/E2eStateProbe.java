package com.astarworks.astera.e2e;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Read-only, test-only state. RCON submits then polls: never wait on a game thread. */
final class E2eStateProbe {
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<String, CompletableFuture<String>> requests = new ConcurrentHashMap<>();

    E2eStateProbe(JavaPlugin plugin) { this.plugin = plugin; }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        String token = UUID.fromString(args[0]).toString();
        if (args.length > 1) {
            var result = new CompletableFuture<String>();
            if (requests.putIfAbsent(token, result) != null) throw new IllegalArgumentException("duplicate token");
            // Bound abandoned results without scheduling more game work.
            CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> requests.remove(token, result));
            try {
                switch (args[1]) {
                    case "entity" -> entity(args[2], args[3]).whenComplete((value, error) -> complete(result, value, error));
                    case "block" -> {
                        World world = world(args[2]);
                        int x = Integer.parseInt(args[3]), y = Integer.parseInt(args[4]), z = Integer.parseInt(args[5]);
                        Bukkit.getRegionScheduler().execute(plugin, world, x >> 4, z >> 4, () -> observe(result, () -> {
                            String type = world.getBlockAt(x, y, z).getType().getKey().toString();
                            return Boolean.toString(args[6].equals("nonair") ? !type.equals("minecraft:air") : type.equals(args[6]));
                        }));
                    }
                    case "count", "nearest" -> {
                        // near a named player, or an explicit world position; '*' means all loaded chunks.
                        CompletableFuture<Location> centre;
                        if (args[2].equals("at")) {
                            centre = owned(target(args[3]), Entity::getLocation);
                        } else {
                            centre = CompletableFuture.completedFuture(new Location(world(args[2]),
                                Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5])));
                        }
                        int offset = args[2].equals("at") ? 4 : 6;
                        centre.thenCompose(pos -> scan(pos, args[offset], args[offset + 1], args[offset + 2],
                            args[1].equals("nearest") ? args[offset + 3] : null))
                            .whenComplete((value, error) -> complete(result, value, error));
                    }
                    default -> throw new IllegalArgumentException("unknown probe");
                }
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        }
        var result = requests.get(token);
        String prefix = "E2E_PROBE " + token + " ";
        if (result == null) sender.sendMessage(prefix + "ERROR " + encode("unknown or expired request"));
        else if (!result.isDone()) sender.sendMessage(prefix + "PENDING");
        else {
            requests.remove(token, result);
            try { sender.sendMessage(prefix + "OK " + encode(result.join())); }
            catch (RuntimeException error) { sender.sendMessage(prefix + "ERROR " + encode(error.toString())); }
        }
        return true;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static World world(String name) {
        var world = Bukkit.getWorld(name);
        if (world == null) throw new IllegalArgumentException("unknown world " + name);
        return world;
    }

    private static Entity target(String id) {
        Entity entity;
        try {
            UUID uuid = UUID.fromString(id);
            entity = Bukkit.getPlayer(uuid);
            if (entity == null) entity = Bukkit.getEntity(uuid);
        } catch (IllegalArgumentException ignored) { entity = Bukkit.getPlayerExact(id); }
        if (entity == null) throw new IllegalArgumentException("missing entity " + id);
        return entity;
    }

    private <T> CompletableFuture<T> owned(Entity entity, java.util.function.Function<Entity, T> read) {
        var result = new CompletableFuture<T>();
        var task = entity.getScheduler().run(plugin, scheduled -> observe(result, () -> read.apply(entity)),
            () -> result.completeExceptionally(new IllegalStateException("entity retired")));
        if (task == null) result.completeExceptionally(new IllegalStateException("entity retired"));
        return result;
    }

    private CompletableFuture<String> entity(String id, String field) {
        return owned(target(id), entity -> field(entity, field));
    }

    private static String field(Entity entity, String field) {
        Location pos = entity.getLocation();
        return switch (field) {
            case "Pos" -> "[" + pos.getX() + "d, " + pos.getY() + "d, " + pos.getZ() + "d]";
            case "Dimension" -> "\"" + entity.getWorld().getKey() + "\"";
            case "Health" -> ((LivingEntity) entity).getHealth() + "f";
            case "Rotation" -> "[" + pos.getYaw() + "f, " + pos.getPitch() + "f]";
            case "OnGround" -> Boolean.toString(entity.isOnGround());
            case "foodLevel" -> Integer.toString(((Player) entity).getFoodLevel());
            case "playerGameType" -> " " + switch (((Player) entity).getGameMode()) {
                case SURVIVAL -> 0; case CREATIVE -> 1; case ADVENTURE -> 2; case SPECTATOR -> 3;
            };
            case "CustomName" -> entity.customName() == null ? "null" : PlainTextComponentSerializer.plainText().serialize(entity.customName());
            case "id" -> entity.getType().getKey().toString();
            case "UUID" -> entity.getUniqueId().toString();
            case "attack_damage" -> Double.toString(((LivingEntity) entity).getAttribute(Attribute.ATTACK_DAMAGE).getValue());
            case "movement_speed" -> Double.toString(((LivingEntity) entity).getAttribute(Attribute.MOVEMENT_SPEED).getValue());
            case "Inventory[].id" -> java.util.Arrays.stream(((Player) entity).getInventory().getContents())
                .filter(item -> item != null && !item.isEmpty()).map(item -> item.getType().getKey().toString())
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            case "slots" -> slots((Player) entity);
            case "SelectedItem" -> ((Player) entity).getInventory().getItemInMainHand().toString();
            default -> {
                if (field.startsWith("contains:")) {
                    String material = field.substring(9);
                    yield Boolean.toString(java.util.Arrays.stream(((Player) entity).getInventory().getStorageContents())
                        .anyMatch(item -> item != null && !item.isEmpty() && item.getType().getKey().toString().equals(material)));
                }
                if (field.startsWith("model:")) {
                    ItemStack item = ((Player) entity).getInventory().getItem(Integer.parseInt(field.substring(6)));
                    yield item == null || !item.hasItemMeta() ? "null" : "\"" + item.getItemMeta().getItemModel() + "\"";
                }
                throw new IllegalArgumentException("unknown field " + field);
            }
        };
    }

    private static String slots(Player player) {
        var view = player.getOpenInventory();
        if (view.getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING)
            throw new IllegalStateException("close the player's screen before reading crafting slots");
        StringBuilder hotbar = new StringBuilder(), crafting = new StringBuilder();
        for (int i = 0; i < 9; i++) hotbar.append(occupied(player.getInventory().getItem(i)));
        for (int i = 1; i <= 4; i++) crafting.append(occupied(view.getItem(i)));
        boolean compass = player.getInventory().contains(org.bukkit.Material.COMPASS);
        return "hotbar[" + hotbar + "] crafting[" + crafting + "] " + (compass ? "compass-in-inventory" : "no-compass");
    }

    private static String occupied(ItemStack item) { return item == null || item.isEmpty() ? "." : "X"; }

    private record Sample(UUID id, double distance, String value) {}

    private CompletableFuture<String> scan(Location centre, String type, String tag, String radiusText, String field) {
        var result = new CompletableFuture<String>();
        double radius = radiusText.equals("*") ? Double.POSITIVE_INFINITY : Double.parseDouble(radiusText);
        if (radius < 0 || Double.isNaN(radius)) throw new IllegalArgumentException("invalid radius");
        // Chunk enumeration is safe globally; every chunk/entity read is dispatched to its owner.
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                World world = centre.getWorld();
                var chunks = new ArrayList<CompletableFuture<List<Sample>>>();
                for (var chunk : world.getLoadedChunks()) {
                    int x = chunk.getX(), z = chunk.getZ();
                    if ((x + 1) * 16 < centre.getX() - radius || x * 16 > centre.getX() + radius
                        || (z + 1) * 16 < centre.getZ() - radius || z * 16 > centre.getZ() + radius) continue;
                    var samples = new CompletableFuture<List<Sample>>();
                    chunks.add(samples);
                    Bukkit.getRegionScheduler().execute(plugin, world, x, z, () -> {
                        try {
                            if (!world.isChunkLoaded(x, z)) { samples.complete(List.of()); return; }
                            var found = new ArrayList<Sample>();
                            for (var entity : world.getChunkAt(x, z).getEntities()) {
                                if ((!type.equals("*") && !entity.getType().getKey().toString().equals(type))
                                    || (!tag.equals("*") && !entity.getScoreboardTags().contains(tag))) continue;
                                // Chunk entities are read while this scheduler owns the chunk.
                                double distance = entity.getLocation().distanceSquared(centre);
                                if (distance <= radius * radius) found.add(new Sample(entity.getUniqueId(), distance,
                                    field == null ? "" : field(entity, field)));
                            }
                            samples.complete(found);
                        } catch (RuntimeException error) { samples.completeExceptionally(error); }
                    });
                }
                CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).whenComplete((unused, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else observe(result, () -> {
                        var samples = chunks.stream().flatMap(chunk -> chunk.join().stream())
                            .collect(java.util.stream.Collectors.toMap(Sample::id, s -> s, (a, b) -> a)).values();
                        return field == null ? Integer.toString(samples.size()) : samples.stream()
                            .min(Comparator.comparingDouble(Sample::distance)).map(Sample::value).orElse("null");
                    });
                });
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private static <T> void observe(CompletableFuture<T> result, Supplier<T> read) {
        try { result.complete(read.get()); }
        catch (RuntimeException error) { result.completeExceptionally(error); }
    }

    private static <T> void complete(CompletableFuture<T> result, T value, Throwable error) {
        if (error == null) result.complete(value); else result.completeExceptionally(error);
    }
}
