package com.gamenews.articles;

public final class TextExcerpt {
    private TextExcerpt() {}
    public static String of(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.codePointCount(0, flat.length()) <= 250 ? flat : limit(flat, 249) + "…";
    }
    public static String limit(String text, int points) {
        return text.codePointCount(0, text.length()) <= points ? text : text.substring(0, text.offsetByCodePoints(0, points));
    }
}
