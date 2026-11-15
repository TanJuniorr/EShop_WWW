package com.example.eshop.ai;

import java.text.Normalizer;
import java.util.Locale;

public final class ChatText {
    private ChatText() {}

    private static String fold(String original) {
        var folded = new StringBuilder(original.length());
        for (char ch : original.toCharArray()) {
            char base = Normalizer.normalize(String.valueOf(Character.toLowerCase(ch)), Normalizer.Form.NFD).charAt(0);
            folded.append(base == 'đ' ? 'd' : base);
        }
        return folded.toString();
    }

    public static String words(String text) {
        return fold(text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

}
