package de.tpahomes;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Chat helpers. */
public final class Msg {
	private Msg() {}

	public static MutableComponent prefix() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("TPA").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	public static MutableComponent info(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	public static MutableComponent ok(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.GREEN));
	}

	public static MutableComponent error(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	public static MutableComponent hl(String text) {
		return Component.literal(text).withStyle(ChatFormatting.YELLOW);
	}

	public static MutableComponent gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	public static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
		return Component.literal("[" + label + "]").withStyle(style -> style
				.withColor(color)
				.withBold(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover).withStyle(ChatFormatting.GRAY))));
	}

	public static String seconds(long millis) {
		long s = (millis + 999) / 1000;
		return s == 1 ? "1 Sekunde" : s + " Sekunden";
	}
}
