package de.tpahomes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TpaHomes implements ModInitializer {
	public static final String MOD_ID = "tpahomes";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModConfig config;
	private static TpaManager tpa;
	private static HomeStorage homes;

	public static ModConfig config() {
		return config;
	}

	public static TpaManager tpa() {
		return tpa;
	}

	public static HomeStorage homes() {
		return homes;
	}

	@Override
	public void onInitialize() {
		config = ModConfig.load();
		tpa = new TpaManager(config);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				ModCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> homes = new HomeStorage(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (homes != null) homes.save();
			homes = null;
			tpa.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> tpa.tick(server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				tpa.onDisconnect(server, handler.getPlayer().getUUID()));

		LOGGER.info("TPA & Homes loaded (max homes: {}, tpa cooldown: {}s, home cooldown: {}s)",
				config.maxHomes, config.tpaCooldownSeconds, config.homeCooldownSeconds);
	}
}
