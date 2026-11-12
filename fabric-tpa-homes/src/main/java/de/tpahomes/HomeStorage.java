package de.tpahomes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Stores homes per player in &lt;world&gt;/tpahomes_homes.json. */
public class HomeStorage {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<Map<String, LinkedHashMap<String, Home>>>() {}.getType();

	public static class Home {
		public String dimension;
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float pitch;

		public Home() {}

		public Home(String dimension, double x, double y, double z, float yaw, float pitch) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
		}
	}

	private final Path file;
	private final Map<UUID, LinkedHashMap<String, Home>> homes = new HashMap<>();

	public HomeStorage(MinecraftServer server) {
		this.file = server.getWorldPath(LevelResource.ROOT).resolve("tpahomes_homes.json").normalize();
		load();
	}

	private void load() {
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, LinkedHashMap<String, Home>> raw = GSON.fromJson(reader, TYPE);
			if (raw == null) return;
			raw.forEach((uuid, map) -> {
				try {
					homes.put(UUID.fromString(uuid), map);
				} catch (IllegalArgumentException ignored) {
				}
			});
			TpaHomes.LOGGER.info("Loaded homes for {} players", homes.size());
		} catch (Exception e) {
			TpaHomes.LOGGER.error("Could not read {}", file, e);
		}
	}

	public synchronized void save() {
		Map<String, LinkedHashMap<String, Home>> raw = new LinkedHashMap<>();
		homes.forEach((uuid, map) -> {
			if (!map.isEmpty()) raw.put(uuid.toString(), map);
		});
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(raw, TYPE, writer);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			TpaHomes.LOGGER.error("Could not write {}", file, e);
		}
	}

	public Map<String, Home> getHomes(UUID player) {
		LinkedHashMap<String, Home> map = homes.get(player);
		return map == null ? Collections.emptyMap() : Collections.unmodifiableMap(map);
	}

	/** Case-insensitive lookup; returns the stored name or null. */
	public String findName(UUID player, String name) {
		for (String key : getHomes(player).keySet()) {
			if (key.equalsIgnoreCase(name)) return key;
		}
		return null;
	}

	public void setHome(UUID player, String name, Home home) {
		homes.computeIfAbsent(player, k -> new LinkedHashMap<>()).put(name, home);
		save();
	}

	public boolean deleteHome(UUID player, String name) {
		LinkedHashMap<String, Home> map = homes.get(player);
		if (map == null || map.remove(name) == null) return false;
		save();
		return true;
	}
}
