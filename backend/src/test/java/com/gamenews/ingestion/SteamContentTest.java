package com.gamenews.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SteamContentTest {
    private final SteamContent content = new SteamContent();
    @Test void 비비코드와HTML을제거하고첫Steam이미지를추출한다() {
        var clean = content.clean("[h1]업데이트[/h1]<p>[b]내용[/b] &amp; 안내</p>[url=https://example.com]자세히[/url][img]{STEAM_CLAN_IMAGE}/123/first.jpg[/img][img]{STEAM_CLAN_IMAGE}/123/second.jpg[/img]<script>나쁜 스크립트</script>");
        assertThat(clean.body()).contains("업데이트", "내용", "& 안내", "자세히").doesNotContain("[h1]", "[url", "[img]", "<p>", "나쁜 스크립트", "first.jpg");
        assertThat(clean.thumbnailUrl()).isEqualTo("https://clan.fastly.steamstatic.com/images/123/first.jpg");
    }
    @Test void 인코딩된스크립트와위험한이미지주소를버린다() {
        var clean = content.clean("&lt;script&gt;실행 금지&lt;/script&gt;<img src='javascript:alert(1)'><img src='https://evil.example/image.jpg'><p>안전한 안내</p>");
        assertThat(clean.body()).isEqualTo("안전한 안내");
        assertThat(clean.thumbnailUrl()).isNull();
        assertThat(SteamContent.safeImage("https://steamstatic.com.evil.example/a.jpg")).isNull();
        assertThat(SteamContent.safeImage("https://user:pass@clan.fastly.steamstatic.com/a.jpg")).isNull();
    }
    @Test void 발췌문은유니코드문자경계를지켜250자로제한한다() {
        var clean = content.clean("🎮".repeat(400));
        assertThat(clean.excerpt().codePointCount(0, clean.excerpt().length())).isEqualTo(250);
        assertThat(clean.excerpt()).endsWith("…").doesNotContain("�");
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "<script>내용 없음</script>", "[img]{STEAM_CLAN_IMAGE}/1/a.jpg[/img]"})
    void 비어있는정제결과는거절한다(String raw) { assertThatThrownBy(() -> content.clean(raw)).isInstanceOf(IllegalArgumentException.class); }
    @Test void 실제응답25건의정제결과가평문이고발췌가저장범위안에있다() throws Exception {
        int checked = 0;
        for (var game : SteamCatalog.GAMES) {
            try (var file = getClass().getResourceAsStream("/fixtures/steam/" + game.appId() + ".json")) {
                var items = new ObjectMapper().readTree(file).path("appnews").path("newsitems");
                for (var item : items) {
                    var clean = content.clean(item.path("contents").asText());
                    assertThat(clean.body()).doesNotContain("[h1]", "[/h1]", "[img]", "{STEAM_CLAN_IMAGE}", "<script", "<p>");
                    assertThat(clean.excerpt().codePointCount(0, clean.excerpt().length())).isLessThanOrEqualTo(250);
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(25);
    }
}
