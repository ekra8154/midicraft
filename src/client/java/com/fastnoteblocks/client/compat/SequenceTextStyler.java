package com.fastnoteblocks.client.compat;

import com.fastnoteblocks.NoteSequence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

final class SequenceTextStyler {
	private static final Style NORMAL = Style.EMPTY.withColor(ChatFormatting.WHITE);
	private static final Style NOTE = Style.EMPTY.withColor(ChatFormatting.AQUA);
	private static final Style DELAY = Style.EMPTY.withColor(ChatFormatting.GOLD);
	private static final Style PUNCTUATION = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY);
	private static final Style INVALID = Style.EMPTY.withColor(ChatFormatting.RED).withUnderlined(true);

	private SequenceTextStyler() {
	}

	static Style[] styles(String text) {
		Style[] styles = new Style[text.length()];
		Arrays.fill(styles, NORMAL);
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == ',') {
				styles[i] = PUNCTUATION;
			}
		}
		try {
			for (NoteSequence.Token token : NoteSequence.tokens(text)) {
				styleToken(text, styles, token.from(), token.to());
			}
		} catch (IllegalArgumentException ignored) {
			for (int i = 0; i < text.length(); i++) {
				if (!Character.isWhitespace(text.charAt(i)) && text.charAt(i) != ',') {
					styles[i] = INVALID;
				}
			}
		}
		return styles;
	}

	private static void styleToken(String text, Style[] styles, int from, int to) {
		int start = from;
		int end = to;
		while (start < end && Character.isWhitespace(text.charAt(start))) {
			start++;
		}
		while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
			end--;
		}
		if (start == end) {
			return;
		}

		String token = text.substring(start, end);
		Style style = INVALID;
		try {
			if (token.endsWith("d") || token.endsWith("D")) {
				int delay = Integer.parseInt(token.substring(0, token.length() - 1).trim());
				if (delay >= 1 && delay <= 64) {
					style = DELAY;
				}
			} else {
				int note = Integer.parseInt(token);
				if (note >= 0 && note <= 24) {
					style = NOTE;
				}
			}
		} catch (NumberFormatException ignored) {
		}
		Arrays.fill(styles, start, end, style);
	}

	static FormattedCharSequence sequence(String text, Style[] styles, int from, int to) {
		if (from >= to) {
			return FormattedCharSequence.EMPTY;
		}
		List<FormattedCharSequence> parts = new ArrayList<>();
		int runStart = from;
		Style runStyle = styles[from];
		for (int i = from + 1; i < to; i++) {
			if (!styles[i].equals(runStyle)) {
				parts.add(FormattedCharSequence.forward(text.substring(runStart, i), runStyle));
				runStart = i;
				runStyle = styles[i];
			}
		}
		parts.add(FormattedCharSequence.forward(text.substring(runStart, to), runStyle));
		return FormattedCharSequence.composite(parts);
	}
}
