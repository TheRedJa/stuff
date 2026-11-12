package de.tpahomes.test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import de.tpahomes.TpaHomes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import java.util.Set;

public class TpaHomesGameTest {

	private static int counter = 0;

	/** Mock player with a unique name that records every chat message it receives. */
	public static class TestPlayer extends ServerPlayer {
		public final List<Component> messages = new ArrayList<>();

		TestPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
			super(server, level, profile, info);
		}

		@Override
		public GameType gameMode() {
			return GameType.SURVIVAL;
		}

		@Override
		public void sendSystemMessage(Component message, boolean overlay) {
			if (!overlay) messages.add(message);
			super.sendSystemMessage(message, overlay);
		}

		boolean received(String text) {
			return messages.stream().anyMatch(m -> m.getString().contains(text));
		}
	}

	private static TestPlayer player(GameTestHelper h, double x, double z) {
		ServerLevel level = h.getLevel();
		MinecraftServer server = level.getServer();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "Tester" + (++counter));
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		TestPlayer p = new TestPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, p, cookie);
		Vec3 pos = h.absoluteVec(new Vec3(x, 1, z));
		p.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
		return p;
	}

	/** Command source without any operator permissions (normal player). */
	private static CommandSourceStack nonOp(ServerPlayer p) {
		return p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
	}

	private static int run(ServerPlayer p, String cmd) {
		CommandDispatcher<CommandSourceStack> d = p.level().getServer().getCommands().getDispatcher();
		try {
			return d.execute(cmd, nonOp(p));
		} catch (CommandSyntaxException e) {
			throw new RuntimeException("Command failed: /" + cmd + " -> " + e.getMessage(), e);
		}
	}

	private static List<String> suggest(ServerPlayer p, String partial) {
		CommandDispatcher<CommandSourceStack> d = p.level().getServer().getCommands().getDispatcher();
		ParseResults<CommandSourceStack> parse = d.parse(partial, nonOp(p));
		return d.getCompletionSuggestions(parse).join().getList().stream().map(Suggestion::getText).toList();
	}

	private static boolean hasClick(TestPlayer p, String command) {
		for (Component m : p.messages) {
			for (Component part : m.toFlatList()) {
				if (part.getStyle().getClickEvent() instanceof ClickEvent.RunCommand rc && rc.command().equals(command)) return true;
			}
		}
		return false;
	}

	private static boolean near(ServerPlayer a, ServerPlayer b) {
		return a.position().distanceTo(b.position()) < 0.01 && a.level() == b.level();
	}

	private static void cleanup(GameTestHelper h, ServerPlayer... players) {
		for (ServerPlayer p : players) h.getLevel().getServer().getPlayerList().remove(p);
	}

	@GameTest(maxTicks = 200)
	public void tpaAcceptAfterStandingStill(GameTestHelper h) {
		TestPlayer a = player(h, 1, 1);
		TestPlayer b = player(h, 6, 6);
		h.assertTrue(run(a, "tpa " + b.getScoreboardName()) == 1, "tpa should succeed");
		h.assertTrue(TpaHomes.tpa().getIncoming(b.getUUID()).size() == 1, "b should have 1 request");
		h.assertTrue(b.received(a.getScoreboardName() + " möchte sich zu dir teleportieren"), "b should get request message");
		h.assertTrue(hasClick(b, "/tpaccept " + a.getScoreboardName()), "request message must contain clickable accept button");
		h.assertTrue(hasClick(b, "/tpadeny " + a.getScoreboardName()), "request message must contain clickable deny button");
		h.assertTrue(run(b, "tpaccept") == 1, "tpaccept should succeed");
		h.assertTrue(TpaHomes.tpa().isInWarmup(a.getUUID()), "warmup should be running");
		h.runAfterDelay(80, () -> h.assertTrue(!near(a, b), "a must NOT be teleported before 5s"));
		h.runAfterDelay(110, () -> {
			h.assertTrue(near(a, b), "a should be teleported to b after 5s");
			h.assertTrue(a.received("Du wurdest zu " + b.getScoreboardName() + " teleportiert"), "a gets success message");
			h.assertTrue(!TpaHomes.tpa().isInWarmup(a.getUUID()), "warmup should be finished");
			cleanup(h, a, b);
			h.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void tpaCancelledWhenTargetMoves(GameTestHelper h) {
		TestPlayer a = player(h, 1, 1);
		TestPlayer b = player(h, 6, 6);
		run(a, "tps " + b.getScoreboardName()); // alias /tps
		h.assertTrue(run(b, "tpaaccept") == 1, "tpaaccept alias should succeed");
		h.runAfterDelay(30, () -> {
			Vec3 p = b.position();
			b.teleportTo(h.getLevel(), p.x + 2, p.y, p.z, Set.of(), 0, 0, true);
		});
		h.runAfterDelay(140, () -> {
			h.assertTrue(!near(a, b), "a must not be teleported (b moved)");
			h.assertTrue(a.position().distanceTo(h.absoluteVec(new Vec3(1, 1, 1))) < 0.01, "a should still be at start");
			h.assertTrue(!TpaHomes.tpa().isInWarmup(a.getUUID()), "warmup should be cancelled");
			h.assertTrue(a.received("Teleport abgebrochen: " + b.getScoreboardName() + " hat sich bewegt"), "a told that b moved");
			cleanup(h, a, b);
			h.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void tpaCancelledWhenRequesterMoves(GameTestHelper h) {
		TestPlayer a = player(h, 1, 1);
		TestPlayer b = player(h, 6, 6);
		run(a, "tpa " + b.getScoreboardName());
		run(b, "tpaccept");
		h.runAfterDelay(20, () -> {
			Vec3 p = a.position();
			a.teleportTo(h.getLevel(), p.x, p.y, p.z + 1, Set.of(), 0, 0, true);
		});
		h.runAfterDelay(140, () -> {
			h.assertTrue(!near(a, b), "a must not be teleported (a moved)");
			h.assertTrue(!TpaHomes.tpa().isInWarmup(a.getUUID()), "warmup should be cancelled");
			cleanup(h, a, b);
			h.succeed();
		});
	}

	@GameTest
	public void tpaCooldownDenyAndCancel(GameTestHelper h) {
		TestPlayer a = player(h, 1, 1);
		TestPlayer b = player(h, 4, 4);
		TestPlayer c = player(h, 7, 7);
		h.assertTrue(run(a, "tpa " + a.getScoreboardName()) == 0, "tpa to self must fail");
		h.assertTrue(run(a, "tpa " + b.getScoreboardName()) == 1, "first tpa ok");
		h.assertTrue(run(a, "tpa " + c.getScoreboardName()) == 0, "second tpa within 15s must fail (cooldown)");
		long cd = TpaHomes.tpa().tpaCooldownLeft(a.getUUID());
		h.assertTrue(cd > 14000 && cd <= 15000, "cooldown should be ~15s but was " + cd);
		h.assertTrue(run(b, "tpadeny") == 1, "deny ok");
		h.assertTrue(TpaHomes.tpa().getIncoming(b.getUUID()).isEmpty(), "request removed after deny");
		h.assertTrue(run(b, "tpaccept") == 0, "accept without request fails");

		h.assertTrue(run(c, "tpa " + b.getScoreboardName()) == 1, "c tpa b ok");
		h.assertTrue(run(c, "tpacancel") == 1, "cancel ok");
		h.assertTrue(TpaHomes.tpa().getIncoming(b.getUUID()).isEmpty(), "request removed after cancel");
		cleanup(h, a, b, c);
		h.succeed();
	}

	@GameTest
	public void tpaAcceptByNameSuggestions(GameTestHelper h) {
		TestPlayer a = player(h, 1, 1);
		TestPlayer b = player(h, 4, 4);
		run(a, "tpa " + b.getScoreboardName());
		List<String> s = suggest(b, "tpaccept ");
		h.assertTrue(s.contains(a.getScoreboardName()), "tpaccept should suggest requester, got " + s);
		h.assertTrue(run(b, "tpaccept " + a.getScoreboardName()) == 1, "accept by name ok");
		cleanup(h, a, b);
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void homesLimitTeleportCooldownSuggestions(GameTestHelper h) {
		TestPlayer p = player(h, 2, 2);
		for (int i = 1; i <= 10; i++) {
			h.assertTrue(run(p, "sethome h" + i) == 1, "sethome h" + i + " ok");
		}
		h.assertTrue(TpaHomes.homes().getHomes(p.getUUID()).size() == 10, "10 homes stored");
		h.assertTrue(run(p, "sethome h11") == 0, "11th home must fail (limit 10)");
		h.assertTrue(run(p, "sethome h1") == 1, "overwriting existing home is allowed");
		h.assertTrue(run(p, "sethome bad.name") == 0, "invalid home name must fail");

		List<String> s = suggest(p, "home ");
		h.assertTrue(s.size() == 10 && s.contains("h1") && s.contains("h10"), "home suggestions should list own homes, got " + s);
		List<String> s2 = suggest(p, "home h1");
		h.assertTrue(s2.contains("h10") && !s2.contains("h2"), "filtered suggestions, got " + s2);

		Vec3 homePos = p.position();
		Vec3 away = h.absoluteVec(new Vec3(8, 1, 8));
		p.teleportTo(h.getLevel(), away.x, away.y, away.z, Set.of(), 0, 0, true);
		h.assertTrue(run(p, "home H1") == 1, "/home (case-insensitive) ok");
		h.assertTrue(p.position().distanceTo(homePos) < 0.01, "player should be at home");
		h.assertTrue(run(p, "home h2") == 0, "second /home within 30s must fail (cooldown)");
		long cd = TpaHomes.tpa().homeCooldownLeft(p.getUUID());
		h.assertTrue(cd > 29000 && cd <= 30000, "home cooldown should be ~30s but was " + cd);
		h.assertTrue(run(p, "home nope") == 0, "unknown home fails");

		h.assertTrue(run(p, "delhome h1") == 1, "delhome ok");
		h.assertTrue(TpaHomes.homes().getHomes(p.getUUID()).size() == 9, "9 homes left");
		h.assertTrue(run(p, "sethome h11") == 1, "after deleting one, a new home can be set");
		h.assertTrue(run(p, "homes") == 10, "/homes lists 10");

		// other players do not see these homes
		TestPlayer q = player(h, 3, 3);
		h.assertTrue(suggest(q, "home ").isEmpty(), "other player has no home suggestions");
		cleanup(h, p, q);
		h.succeed();
	}

	@GameTest
	public void everyoneCanUseCommands(GameTestHelper h) {
		TestPlayer p = player(h, 1, 1);
		CommandSourceStack src = nonOp(p);
		var d = p.level().getServer().getCommands().getDispatcher();
		for (String cmd : List.of("tpa", "tps", "tpaccept", "tpaaccept", "tpadeny", "tpacancel", "sethome", "home", "delhome", "homes")) {
			var node = d.getRoot().getChild(cmd);
			h.assertTrue(node != null, "command /" + cmd + " registered");
			h.assertTrue(node.canUse(src), "/" + cmd + " usable without op");
		}
		cleanup(h, p);
		h.succeed();
	}
}
