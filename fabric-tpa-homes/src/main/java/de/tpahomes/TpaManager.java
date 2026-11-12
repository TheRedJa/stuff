package de.tpahomes;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Handles TPA requests, warmups (stand still) and cooldowns. */
public class TpaManager {
	/** A request from {@code requester} to teleport to {@code target}. */
	public record Request(UUID requester, String requesterName, UUID target, String targetName, long createdAt) {}

	/** An accepted request currently in its "stand still" phase. */
	private static final class Warmup {
		final UUID requester;
		final UUID target;
		final Vec3 requesterStart;
		final Vec3 targetStart;
		/** Server tick at which the teleport happens (20 ticks = 1 second). */
		final long endsAtTick;
		long lastAnnouncedSecond = -1;

		Warmup(UUID requester, UUID target, Vec3 requesterStart, Vec3 targetStart, long endsAtTick) {
			this.requester = requester;
			this.target = target;
			this.requesterStart = requesterStart;
			this.targetStart = targetStart;
			this.endsAtTick = endsAtTick;
		}
	}

	/** target -> (requester -> request), insertion ordered so the newest is last. */
	private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new HashMap<>();
	private final List<Warmup> warmups = new ArrayList<>();
	private final Map<UUID, Long> tpaCooldowns = new HashMap<>();
	private final Map<UUID, Long> homeCooldowns = new HashMap<>();

	private final ModConfig config;

	public TpaManager(ModConfig config) {
		this.config = config;
	}

	// ---------------------------------------------------------------- cooldowns

	public long tpaCooldownLeft(UUID player) {
		return left(tpaCooldowns, player);
	}

	public long homeCooldownLeft(UUID player) {
		return left(homeCooldowns, player);
	}

	public void startTpaCooldown(UUID player) {
		tpaCooldowns.put(player, System.currentTimeMillis() + config.tpaCooldownSeconds * 1000L);
	}

	public void startHomeCooldown(UUID player) {
		homeCooldowns.put(player, System.currentTimeMillis() + config.homeCooldownSeconds * 1000L);
	}

	private static long left(Map<UUID, Long> map, UUID player) {
		Long until = map.get(player);
		if (until == null) return 0;
		long left = until - System.currentTimeMillis();
		if (left <= 0) {
			map.remove(player);
			return 0;
		}
		return left;
	}

	// ---------------------------------------------------------------- requests

	/** Adds a request. Any older request of the same requester (to anyone) is replaced. */
	public void addRequest(ServerPlayer requester, ServerPlayer target) {
		removeOutgoing(requester.getUUID());
		incoming.computeIfAbsent(target.getUUID(), k -> new LinkedHashMap<>())
				.put(requester.getUUID(), new Request(requester.getUUID(), requester.getScoreboardName(),
						target.getUUID(), target.getScoreboardName(), System.currentTimeMillis()));
	}

	public List<Request> getIncoming(UUID target) {
		LinkedHashMap<UUID, Request> map = incoming.get(target);
		return map == null ? List.of() : new ArrayList<>(map.values());
	}

	public Request getOutgoing(UUID requester) {
		for (LinkedHashMap<UUID, Request> map : incoming.values()) {
			Request r = map.get(requester);
			if (r != null) return r;
		}
		return null;
	}

	public Request removeOutgoing(UUID requester) {
		for (Iterator<LinkedHashMap<UUID, Request>> it = incoming.values().iterator(); it.hasNext(); ) {
			LinkedHashMap<UUID, Request> map = it.next();
			Request r = map.remove(requester);
			if (map.isEmpty()) it.remove();
			if (r != null) return r;
		}
		return null;
	}

	public void removeRequest(Request request) {
		LinkedHashMap<UUID, Request> map = incoming.get(request.target());
		if (map != null) {
			map.remove(request.requester());
			if (map.isEmpty()) incoming.remove(request.target());
		}
	}

	public boolean isInWarmup(UUID player) {
		for (Warmup w : warmups) {
			if (w.requester.equals(player) || w.target.equals(player)) return true;
		}
		return false;
	}

	/** Starts the "stand still" phase for an accepted request. */
	public void startWarmup(ServerPlayer requester, ServerPlayer target) {
		MinecraftServer server = requester.level().getServer();
		Warmup w = new Warmup(requester.getUUID(), target.getUUID(), requester.position(), target.position(),
				server.getTickCount() + config.tpaWarmupSeconds * 20L);
		warmups.add(w);
		if (config.tpaWarmupSeconds <= 0) {
			tick(server);
		}
	}

	// ---------------------------------------------------------------- ticking

	public void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		long timeout = config.tpaRequestTimeoutSeconds * 1000L;

		// Expire old requests
		for (Iterator<LinkedHashMap<UUID, Request>> it = incoming.values().iterator(); it.hasNext(); ) {
			LinkedHashMap<UUID, Request> map = it.next();
			for (Iterator<Request> rit = map.values().iterator(); rit.hasNext(); ) {
				Request r = rit.next();
				if (now - r.createdAt() > timeout) {
					rit.remove();
					ServerPlayer requester = server.getPlayerList().getPlayer(r.requester());
					ServerPlayer target = server.getPlayerList().getPlayer(r.target());
					if (requester != null) {
						requester.sendSystemMessage(Msg.error("Deine TPA-Anfrage an " + r.targetName() + " ist abgelaufen."));
					}
					if (target != null) {
						target.sendSystemMessage(Msg.info("Die TPA-Anfrage von " + r.requesterName() + " ist abgelaufen."));
					}
				}
			}
			if (map.isEmpty()) it.remove();
		}

		// Warmups
		double maxMoveSq = config.tpaMaxMoveDistance * config.tpaMaxMoveDistance;
		for (Iterator<Warmup> it = warmups.iterator(); it.hasNext(); ) {
			Warmup w = it.next();
			ServerPlayer requester = server.getPlayerList().getPlayer(w.requester);
			ServerPlayer target = server.getPlayerList().getPlayer(w.target);

			if (requester == null || target == null || !requester.isAlive() || !target.isAlive()) {
				it.remove();
				String reason = (requester == null || target == null)
						? "ein Spieler hat den Server verlassen." : "ein Spieler ist gestorben.";
				if (requester != null) requester.sendSystemMessage(Msg.error("Teleport abgebrochen: " + reason));
				if (target != null) target.sendSystemMessage(Msg.error("Teleport abgebrochen: " + reason));
				continue;
			}

			boolean requesterMoved = requester.position().distanceToSqr(w.requesterStart) > maxMoveSq;
			boolean targetMoved = target.position().distanceToSqr(w.targetStart) > maxMoveSq;
			if (requesterMoved || targetMoved) {
				it.remove();
				String who = requesterMoved && targetMoved ? "Ihr habt euch beide bewegt"
						: (requesterMoved ? requester.getScoreboardName() : target.getScoreboardName()) + " hat sich bewegt";
				Component msg = Msg.error("Teleport abgebrochen: " + who + "!");
				requester.sendSystemMessage(msg);
				target.sendSystemMessage(msg);
				requester.sendSystemMessage(Component.literal("Teleport abgebrochen").withStyle(ChatFormatting.RED), true);
				target.sendSystemMessage(Component.literal("Teleport abgebrochen").withStyle(ChatFormatting.RED), true);
				continue;
			}

			long remainingTicks = w.endsAtTick - server.getTickCount();
			if (remainingTicks <= 0) {
				it.remove();
				teleport(requester, target);
				continue;
			}

			long second = (remainingTicks + 19) / 20;
			if (second != w.lastAnnouncedSecond) {
				w.lastAnnouncedSecond = second;
				Component bar = Component.literal("Teleport in " + second + "s – nicht bewegen!").withStyle(ChatFormatting.GOLD);
				requester.sendSystemMessage(bar, true);
				target.sendSystemMessage(bar, true);
			}
		}
	}

	private void teleport(ServerPlayer requester, ServerPlayer target) {
		ServerLevel level = target.level();
		if (requester.isPassenger()) requester.stopRiding();
		requester.teleportTo(level, target.getX(), target.getY(), target.getZ(), Set.of(),
				requester.getYRot(), requester.getXRot(), true);
		requester.sendSystemMessage(Msg.ok("Du wurdest zu " + target.getScoreboardName() + " teleportiert."));
		target.sendSystemMessage(Msg.ok(requester.getScoreboardName() + " wurde zu dir teleportiert."));
		Component bar = Component.literal("Teleportiert!").withStyle(ChatFormatting.GREEN);
		requester.sendSystemMessage(bar, true);
		target.sendSystemMessage(bar, true);
	}

	// ---------------------------------------------------------------- cleanup

	public void onDisconnect(MinecraftServer server, UUID player) {
		// Outgoing request
		Request out = removeOutgoing(player);
		if (out != null) {
			ServerPlayer target = server.getPlayerList().getPlayer(out.target());
			if (target != null) {
				target.sendSystemMessage(Msg.info("Die TPA-Anfrage von " + out.requesterName() + " wurde entfernt (Spieler offline)."));
			}
		}
		// Incoming requests
		LinkedHashMap<UUID, Request> in = incoming.remove(player);
		if (in != null) {
			for (Request r : in.values()) {
				ServerPlayer requester = server.getPlayerList().getPlayer(r.requester());
				if (requester != null) {
					requester.sendSystemMessage(Msg.error("Deine TPA-Anfrage an " + r.targetName() + " wurde entfernt (Spieler offline)."));
				}
			}
		}
		// Warmups are cancelled in tick() once the player is gone.
	}

	public void clear() {
		incoming.clear();
		warmups.clear();
		tpaCooldowns.clear();
		homeCooldowns.clear();
	}
}
