package com.gamenews.ingestion;

import com.gamenews.articles.TextExcerpt;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

@Component
public class SteamContent {
    private static final String CLAN = "https://clan.fastly.steamstatic.com/images/";
    private static final Pattern IMAGE = Pattern.compile("(?is)\\[img(?:=[^\\]]*)?\\](.*?)\\[/img\\]");
    private static final Pattern TAG = Pattern.compile("(?i)\\[/?(?:h[1-6]|b|i|u|s|strike|url|list|olist|li|quote|spoiler|table|tr|td|th|code|p|hr|img|previewyoutube|youtube|dynamiclink|noparse)(?:=[^\\]\\r\\n]*)?\\]");
    public record Cleaned(String body, String excerpt, String thumbnailUrl) {}

    public Cleaned clean(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 1_000_000) throw new IllegalArgumentException("뉴스 본문이 없거나 너무 큽니다.");
        String expanded = Parser.unescapeEntities(raw, false).replace("{STEAM_CLAN_IMAGE}/", CLAN);
        String html = IMAGE.matcher(expanded).replaceAll(match -> {
            String url = safeImage(match.group(1).strip());
            return java.util.regex.Matcher.quoteReplacement(url == null ? " " : "<img src=\"" + org.jsoup.nodes.Entities.escape(url) + "\">");
        });
        var document = Jsoup.parse(html);
        document.select("script,style,iframe,object,embed,svg,math,form,template,noscript").remove();
        String thumbnail = document.select("img[src]").stream().map(image -> safeImage(image.attr("src")))
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (thumbnail == null) {
            var marker = Pattern.compile(Pattern.quote(CLAN) + "[^\\s\\[\\]<>\"']+").matcher(expanded);
            if (marker.find()) thumbnail = safeImage(marker.group());
        }
        document.select("img").remove();
        document.select("br,p,div,li,h1,h2,h3,h4,h5,h6,blockquote,tr").forEach(element -> element.appendText("\n"));
        String text = TAG.matcher(document.body().wholeText()).replaceAll(" ").replace("[*]", "\n• ");
        var plain = Jsoup.parse(text);
        plain.select("script,style,iframe,object,embed,svg,math").remove();
        text = plain.body().wholeText().replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("[\\p{Zs}\\t\\x0B\\f]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").strip();
        if (text.isBlank()) throw new IllegalArgumentException("정제 후 뉴스 본문이 비어 있습니다.");
        // 글자 경계와 DB 문자 길이를 모두 지키며 원문 링크로 이어 읽을 수 있게 한다.
        if (text.length() > 100000) text = text.substring(0, Character.isHighSurrogate(text.charAt(99999)) ? 99999 : 100000);
        return new Cleaned(text, TextExcerpt.of(text), thumbnail);
    }

    public String title(String raw) { return raw == null ? "" : Jsoup.parse(Parser.unescapeEntities(raw, false)).text().strip(); }

    public static String safeImage(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = host.equals("steamstatic.com") || host.endsWith(".steamstatic.com")
                || host.equals("steamusercontent.com") || host.endsWith(".steamusercontent.com")
                || host.equals("steamcdn-a.akamaihd.net");
            return allowed && "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null ? uri.toASCIIString() : null;
        } catch (IllegalArgumentException error) { return null; }
    }
}
