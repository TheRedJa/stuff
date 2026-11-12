package de.tpahomes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** All commands of the mod. None of them has a permission requirement: everybody may use them. */
public final class ModCommands {
	private ModCommands() {}

	private static final Pattern HOME_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

	private static final SuggestionProvider<CommandSourceStack> OWN_HOMES = (ctx, builder) -> {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null || TpaHomes.homes() == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(TpaHomes.homes().getHomes(player.getUUID()).keySet(), builder);
	};

	private static final SuggestionProvider<CommandSourceStack> INCOMING_REQUESTERS = (ctx, builder) -> {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(
				TpaHomes.tpa().getIncoming(player.getUUID()).stream().map(TpaManager.Request::requesterName), builder);
	};

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		// ---------------- TPA
		LiteralCommandNode<CommandSourceStack> tpa = d.register(literal("tpa")
				.then(argument("spieler", EntityArgument.player())
						.executes(ctx -> tpa(ctx, EntityArgument.getPlayer(ctx, "spieler")))));
		d.register(literal("tps").redirect(tpa));

		for (String name : List.of("tpaccept", "tpaaccept")) {
			d.register(literal(name)
					.executes(ctx -> accept(ctx, null))
					.then(argument("spieler", StringArgumentType.word()).suggests(INCOMING_REQUESTERS)
							.executes(ctx -> accept(ctx, StringArgumentType.getString(ctx, "spieler")))));
		}
		for (String name : List.of("tpadeny", "tpdeny")) {
			d.register(literal(name)
					.executes(ctx -> deny(ctx, null))
					.then(argument("spieler", StringArgumentType.word()).suggests(INCOMING_REQUESTERS)
							.executes(ctx -> deny(ctx, StringArgumentType.getString(ctx, "spieler")))));
		}
		d.register(literal("tpacancel").executes(ModCommands::cancel));

		// ---------------- Homes
		d.register(literal("sethome")
				.then(argument("name", StringArgumentType.word()).suggests(OWN_HOMES)
						.executes(ctx -> setHome(ctx, StringArgumentType.getString(ctx, "name")))));
		d.register(literal("home")
				.executes(ModCommands::listHomes)
				.then(argument("name", StringArgumentType.word()).suggests(OWN_HOMES)
						.executes(ctx -> home(ctx, StringArgumentType.getString(ctx, "name")))));
		d.register(literal("delhome")
				.then(argument("name", StringArgumentType.word()).suggests(OWN_HOMES)
						.executes(ctx -> delHome(ctx, StringArgumentType.getString(ctx, "name")))));
		d.register(literal("homes").executes(ModCommands::listHomes));
	}

	// ======================================================================= TPA

	private static int tpa(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		TpaManager tpa = TpaHomes.tpa();

		if (player.getUUID().equals(target.getUUID())) {
			player.sendSystemMessage(Msg.error("Du kannst dir nicht selbst eine Anfrage schicken."));
			return 0;
		}
		long cd = tpa.tpaCooldownLeft(player.getUUID());
		if (cd > 0) {
			player.sendSystemMessage(Msg.error("Bitte warte noch " + Msg.seconds(cd) + ", bevor du erneut /tpa nutzt."));
			return 0;
		}
		if (tpa.isInWarmup(player.getUUID())) {
			player.sendSystemMessage(Msg.error("Du bist gerade schon in einem Teleport."));
			return 0;
		}

		TpaManager.Request old = tpa.getOutgoing(player.getUUID());
		tpa.addRequest(player, target);
		tpa.startTpaCooldown(player.getUUID());

		if (old != null && !old.target().equals(target.getUUID())) {
			ServerPlayer oldTarget = player.level().getServer().getPlayerList().getPlayer(old.target());
			if (oldTarget != null) {
				oldTarget.sendSystemMessage(Msg.info("Die TPA-Anfrage von " + player.getScoreboardName() + " wurde zurückgezogen."));
			}
		}

		String name = player.getScoreboardName();
		player.sendSystemMessage(Msg.ok("TPA-Anfrage an ").append(Msg.hl(target.getScoreboardName()))
				.append(Component.literal(" gesendet. ").withStyle(ChatFormatting.GREEN))
				.append(Msg.button("Abbrechen", ChatFormatting.RED, "/tpacancel", "Anfrage zurückziehen")));

		MutableComponent msg = Msg.prefix().append(Msg.hl(name))
				.append(Msg.gray(" möchte sich zu dir teleportieren. "))
				.append(Msg.button("Annehmen", ChatFormatting.GREEN, "/tpaccept " + name, "Klicke, um die Anfrage von " + name + " anzunehmen"))
				.append(Component.literal(" "))
				.append(Msg.button("Ablehnen", ChatFormatting.RED, "/tpadeny " + name, "Klicke, um die Anfrage von " + name + " abzulehnen"));
		target.sendSystemMessage(msg);
		target.sendSystemMessage(Msg.info("Oder nutze /tpaccept bzw. /tpadeny. Die Anfrage läuft in "
				+ TpaHomes.config().tpaRequestTimeoutSeconds + " Sekunden ab."));
		return 1;
	}

	private static TpaManager.Request findIncoming(ServerPlayer player, String requesterName) {
		List<TpaManager.Request> list = TpaHomes.tpa().getIncoming(player.getUUID());
		if (list.isEmpty()) {
			player.sendSystemMessage(Msg.error("Du hast keine offenen TPA-Anfragen."));
			return null;
		}
		if (requesterName == null) {
			return list.get(list.size() - 1); // newest
		}
		for (TpaManager.Request r : list) {
			if (r.requesterName().equalsIgnoreCase(requesterName)) return r;
		}
		player.sendSystemMessage(Msg.error("Du hast keine TPA-Anfrage von " + requesterName + "."));
		return null;
	}

	private static int accept(CommandContext<CommandSourceStack> ctx, String requesterName) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		TpaManager tpa = TpaHomes.tpa();
		TpaManager.Request req = findIncoming(player, requesterName);
		if (req == null) return 0;

		ServerPlayer requester = player.level().getServer().getPlayerList().getPlayer(req.requester());
		if (requester == null) {
			tpa.removeRequest(req);
			player.sendSystemMessage(Msg.error(req.requesterName() + " ist nicht mehr online."));
			return 0;
		}
		if (tpa.isInWarmup(player.getUUID()) || tpa.isInWarmup(requester.getUUID())) {
			player.sendSystemMessage(Msg.error("Einer von euch ist gerade schon in einem Teleport. Versuche es gleich nochmal."));
			return 0;
		}
		tpa.removeRequest(req);
		tpa.startWarmup(requester, player);

		int s = TpaHomes.config().tpaWarmupSeconds;
		if (s > 0) {
			String still = "Bleibt beide " + s + " Sekunden stehen, sonst wird der Teleport abgebrochen!";
			player.sendSystemMessage(Msg.ok("Du hast die Anfrage von " + requester.getScoreboardName() + " angenommen. ")
					.append(Component.literal(still).withStyle(ChatFormatting.GOLD)));
			requester.sendSystemMessage(Msg.ok(player.getScoreboardName() + " hat deine Anfrage angenommen. ")
					.append(Component.literal(still).withStyle(ChatFormatting.GOLD)));
		}
		return 1;
	}

	private static int deny(CommandContext<CommandSourceStack> ctx, String requesterName) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		TpaManager.Request req = findIncoming(player, requesterName);
		if (req == null) return 0;
		TpaHomes.tpa().removeRequest(req);
		player.sendSystemMessage(Msg.info("Du hast die Anfrage von " + req.requesterName() + " abgelehnt."));
		ServerPlayer requester = player.level().getServer().getPlayerList().getPlayer(req.requester());
		if (requester != null) {
			requester.sendSystemMessage(Msg.error(player.getScoreboardName() + " hat deine TPA-Anfrage abgelehnt."));
		}
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		TpaManager.Request req = TpaHomes.tpa().removeOutgoing(player.getUUID());
		if (req == null) {
			player.sendSystemMessage(Msg.error("Du hast keine offene TPA-Anfrage."));
			return 0;
		}
		player.sendSystemMessage(Msg.info("Deine Anfrage an " + req.targetName() + " wurde zurückgezogen."));
		ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(req.target());
		if (target != null) {
			target.sendSystemMessage(Msg.info("Die TPA-Anfrage von " + player.getScoreboardName() + " wurde zurückgezogen."));
		}
		return 1;
	}

	// ===================================================================== Homes

	private static int setHome(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		HomeStorage storage = TpaHomes.homes();
		if (!HOME_NAME.matcher(name).matches()) {
			player.sendSystemMessage(Msg.error("Ungültiger Name. Erlaubt: Buchstaben, Zahlen, _ und - (max. 32 Zeichen)."));
			return 0;
		}
		String existing = storage.findName(player.getUUID(), name);
		int max = TpaHomes.config().maxHomes;
		if (existing == null && storage.getHomes(player.getUUID()).size() >= max) {
			player.sendSystemMessage(Msg.error("Du hast bereits die maximale Anzahl von " + max
					+ " Homes. Lösche eines mit /delhome <name>."));
			return 0;
		}
		String key = existing != null ? existing : name;
		if (existing != null) storage.deleteHome(player.getUUID(), existing);
		storage.setHome(player.getUUID(), key, new HomeStorage.Home(
				player.level().dimension().identifier().toString(),
				player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
		int count = storage.getHomes(player.getUUID()).size();
		player.sendSystemMessage(Msg.ok(existing != null ? "Home " : "Home ").append(Msg.hl(key))
				.append(Component.literal(existing != null ? " wurde aktualisiert. " : " wurde gesetzt. ").withStyle(ChatFormatting.GREEN))
				.append(Msg.gray("(" + count + "/" + max + ")")));
		return 1;
	}

	private static int home(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		HomeStorage storage = TpaHomes.homes();
		TpaManager tpa = TpaHomes.tpa();

		String key = storage.findName(player.getUUID(), name);
		if (key == null) {
			player.sendSystemMessage(Msg.error("Du hast kein Home namens \"" + name + "\"."));
			return 0;
		}
		long cd = tpa.homeCooldownLeft(player.getUUID());
		if (cd > 0) {
			player.sendSystemMessage(Msg.error("Bitte warte noch " + Msg.seconds(cd) + ", bevor du erneut /home nutzt."));
			return 0;
		}
		if (tpa.isInWarmup(player.getUUID())) {
			player.sendSystemMessage(Msg.error("Du bist gerade in einem TPA-Teleport."));
			return 0;
		}
		HomeStorage.Home home = storage.getHomes(player.getUUID()).get(key);
		Identifier dimId = Identifier.tryParse(home.dimension);
		ServerLevel level = dimId == null ? null
				: player.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
		if (level == null) {
			player.sendSystemMessage(Msg.error("Die Dimension von diesem Home existiert nicht mehr."));
			return 0;
		}
		if (player.isPassenger()) player.stopRiding();
		player.teleportTo(level, home.x, home.y, home.z, Set.of(), home.yaw, home.pitch, true);
		tpa.startHomeCooldown(player.getUUID());
		player.sendSystemMessage(Msg.ok("Du wurdest zu Home ").append(Msg.hl(key))
				.append(Component.literal(" teleportiert.").withStyle(ChatFormatting.GREEN)));
		return 1;
	}

	private static int delHome(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		HomeStorage storage = TpaHomes.homes();
		String key = storage.findName(player.getUUID(), name);
		if (key == null || !storage.deleteHome(player.getUUID(), key)) {
			player.sendSystemMessage(Msg.error("Du hast kein Home namens \"" + name + "\"."));
			return 0;
		}
		player.sendSystemMessage(Msg.ok("Home ").append(Msg.hl(key))
				.append(Component.literal(" wurde gelöscht.").withStyle(ChatFormatting.GREEN)));
		return 1;
	}

	private static int listHomes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Map<String, HomeStorage.Home> homes = TpaHomes.homes().getHomes(player.getUUID());
		int max = TpaHomes.config().maxHomes;
		if (homes.isEmpty()) {
			player.sendSystemMessage(Msg.info("Du hast noch keine Homes. Setze eines mit /sethome <name>. (0/" + max + ")"));
			return 0;
		}
		MutableComponent msg = Msg.info("Deine Homes (" + homes.size() + "/" + max + "): ");
		boolean first = true;
		for (Map.Entry<String, HomeStorage.Home> e : homes.entrySet()) {
			if (!first) msg.append(Msg.gray(", "));
			first = false;
			HomeStorage.Home h = e.getValue();
			String dim = h.dimension.startsWith("minecraft:") ? h.dimension.substring(10) : h.dimension;
			String hover = String.format(Locale.ROOT, "%s: %d, %d, %d\nKlicke zum Teleportieren", dim,
					(int) Math.floor(h.x), (int) Math.floor(h.y), (int) Math.floor(h.z));
			msg.append(Msg.button(e.getKey(), ChatFormatting.AQUA, "/home " + e.getKey(), hover));
		}
		player.sendSystemMessage(msg);
		return homes.size();
	}
}
