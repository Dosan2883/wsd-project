package com.gamenews.articles;

import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public class DemoArticles implements ApplicationRunner {
    private final GameRepository games;
    private final SourceRepository sources;
    private final ArticleStore store;
    public DemoArticles(GameRepository games, SourceRepository sources, ArticleStore store) {
        this.games = games; this.sources = sources; this.store = store;
    }

    @Override public void run(ApplicationArguments args) {
        var source = sources.findByCode("gamelog-demo").orElseGet(() -> sources.saveAndFlush(new Source("gamelog-demo", "GAMELOG 데모")));
        String[] names = {"Dota 2", "Counter-Strike 2", "Team Fortress 2"};
        long[] appIds = {570L, 730L, 440L};
        String[] topics = {"업데이트 소식", "커뮤니티 소식", "이벤트 안내"};
        int created = 0;
        for (int index = 0; index < names.length; index++) {
            final int i = index;
            var game = games.findBySteamAppId(appIds[i]).orElseGet(() -> games.saveAndFlush(new Game(names[i], appIds[i])));
            String body = "이 글은 GAMELOG의 저장·조회 흐름을 확인하기 위해 준비한 데모입니다. 실제 Steam에서 수집한 뉴스가 아닙니다.\n\n"
                + names[i] + "의 " + topics[i] + "을 가정한 예시 콘텐츠입니다. 게임 이름, 글 제목, 출처, 발행 시각과 본문이 함께 표시되는지 확인해 보세요.\n\n"
                + "확인 순서\n1. 로그인 후 글 목록에서 이 글을 선택합니다.\n2. 상세 화면의 게임·출처·발행 시각과 본문을 확인합니다.\n3. 목록으로 돌아가 제목 검색과 게임 필터를 사용합니다.\n\n"
                + "아래 링크는 해당 게임의 Steam 상점 페이지입니다. 이 데모 글의 실제 기사 원문은 존재하지 않습니다.";
            var saved = store.store(new ArticleStore.Draft(source.getId(), game.getId(), "demo-v1-" + appIds[i],
                "[데모] " + names[i] + " " + topics[i], body, "https://store.steampowered.com/app/" + appIds[i] + "/",
                Instant.parse("2026-11-17T03:00:00Z").minusSeconds(i * 3600L), true));
            if (saved.created()) created++;
        }
        LoggerFactory.getLogger(DemoArticles.class).info("데모 글 준비 완료: 신규 {}건, 기존 글의 내용·공개 상태는 유지합니다.", created);
    }
}
