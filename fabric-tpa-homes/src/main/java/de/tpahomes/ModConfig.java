package de.tpahomes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple JSON config stored in config/tpahomes.json. */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public int maxHomes = 10;
	public int tpaCooldownSeconds = 15;
	public int homeCooldownSeconds = 30;
	public int tpaWarmupSeconds = 5;
	public int tpaRequestTimeoutSeconds = 60;
	/** Max distance (in blocks) a player may move during the warmup before it is cancelled. */
	public double tpaMaxMoveDistance = 0.2;

	public static ModConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("tpahomes.json");
		ModConfig config = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, ModConfig.class);
			} catch (Exception e) {
				TpaHomes.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}
		if (config == null) {
			config = new ModConfig();
		}
		config.sanitize();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (Exception e) {
			TpaHomes.LOGGER.error("Could not write {}", path, e);
		}
		return config;
	}

	private void sanitize() {
		maxHomes = Math.max(0, maxHomes);
		tpaCooldownSeconds = Math.max(0, tpaCooldownSeconds);
		homeCooldownSeconds = Math.max(0, homeCooldownSeconds);
		tpaWarmupSeconds = Math.max(0, tpaWarmupSeconds);
		tpaRequestTimeoutSeconds = Math.max(5, tpaRequestTimeoutSeconds);
		if (tpaMaxMoveDistance <= 0) tpaMaxMoveDistance = 0.2;
	}
}
