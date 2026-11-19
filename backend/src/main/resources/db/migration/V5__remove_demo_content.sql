-- 기존 데모 표시와 전용 출처를 모두 확인한다. 실제 기사·회원은 보존한다.
DELETE FROM articles
WHERE is_demo = TRUE
   OR source_id IN (SELECT id FROM sources WHERE code = 'gamelog-demo');

DELETE FROM ingestion_runs
WHERE source_id IN (SELECT id FROM sources WHERE code = 'gamelog-demo');

DELETE FROM sources WHERE code = 'gamelog-demo';

-- 데모에서만 사용한 게임은 실제 기사가 연결되어 있지 않을 때 정리한다.
DELETE FROM games
WHERE steam_app_id = 440
  AND NOT EXISTS (SELECT 1 FROM articles WHERE articles.game_id = games.id);
