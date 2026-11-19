import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError } from './api'
import { koreanDate } from './articles'

export type IngestionRun = {
  id: number; source: string; trigger: 'MANUAL' | 'SCHEDULED'; status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED'
  startedAt: string; finishedAt: string | null; fetchedCount: number; savedCount: number; duplicateCount: number
  failedCount: number; errorSummary: string | null
}
type RunPage = { items: IngestionRun[]; page: number; size: number; totalElements: number; totalPages: number; enabled?: boolean; schedulingEnabled?: boolean; intervalMs?: number }
const labels = { RUNNING: '수집 중', SUCCESS: '성공', PARTIAL: '일부 실패', FAILED: '실패' }

export default function IngestionPanel({ token, onAuthFailure }: { token: string; onAuthFailure: (error: ApiError) => void }) {
  const [page, setPage] = useState(0)
  const [history, setHistory] = useState<RunPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [collecting, setCollecting] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<IngestionRun | null>(null)
  const readRequest = useRef<AbortController | null>(null)
  const writeRequest = useRef<AbortController | null>(null)

  const load = useCallback(async (targetPage: number) => {
    readRequest.current?.abort()
    const controller = new AbortController(); readRequest.current = controller
    setLoading(true); setError('')
    try {
      const rows = await api<RunPage>(`/admin/ingestions?page=${targetPage}&size=10`, { token, signal: controller.signal })
      if (!controller.signal.aborted) setHistory(rows)
    } catch (caught) {
      if (controller.signal.aborted) return
      const failure = caught as ApiError
      setHistory(null)
      if (failure.status === 401 || failure.code === 'ACCOUNT_SUSPENDED') onAuthFailure(failure)
      else setError(failure.message)
    } finally { if (!controller.signal.aborted) setLoading(false) }
  }, [token, onAuthFailure])

  useEffect(() => { void load(page); return () => readRequest.current?.abort() }, [page, load])
  useEffect(() => () => { readRequest.current?.abort(); writeRequest.current?.abort() }, [])
  useEffect(() => {
    if (!collecting && !history?.items.some(run => run.status === 'RUNNING')) return
    const timer = window.setInterval(() => { void load(page) }, 5000)
    return () => window.clearInterval(timer)
  }, [collecting, history, load, page])

  async function collect() {
    if (collecting) return
    const controller = new AbortController(); writeRequest.current = controller
    setCollecting(true); setError(''); setResult(null)
    try {
      const completed = await api<IngestionRun>('/admin/ingestions', { method: 'POST', token, signal: controller.signal })
      if (controller.signal.aborted) return
      setResult(completed); setPage(0); await load(0)
    } catch (caught) {
      if (controller.signal.aborted) return
      const failure = caught as ApiError
      if (failure.status === 401 || failure.code === 'ACCOUNT_SUSPENDED') onAuthFailure(failure)
      else {
        if (failure.status === 409) await load(page)
        if (!controller.signal.aborted) setError(failure.message)
      }
    } finally { if (!controller.signal.aborted) setCollecting(false) }
  }

  const running = collecting || !!history?.items.some(run => run.status === 'RUNNING' && Date.now() - Date.parse(run.startedAt) < 600000)
  return <main className="news-layout">
    <div className="news-heading"><div><span className="eyebrow">관리자 · 뉴스 수집</span><h1>Steam 뉴스 수집 관리</h1><p>기본 5개 게임의 공식 소식을 수집하고 실행 결과를 확인합니다.</p></div></div>
    <div className="ingestion-controls"><div><h2>자동 수집과 즉시 실행</h2><p>{history?.schedulingEnabled === false ? '현재 예약 수집은 꺼져 있습니다.' : `예약 수집은 ${Math.round((history?.intervalMs ?? 1800000) / 60000)}분 간격입니다.`} 즉시 실행도 같은 저장·중복 검사 과정을 사용합니다.</p><p className="field-help">Counter-Strike 2 · Dota 2 · PUBG: BATTLEGROUNDS · ELDEN RING · Baldur's Gate 3</p></div>
      <button className="primary-button ingestion-start" disabled={running || history?.enabled === false} onClick={() => { void collect() }}>{history?.enabled === false ? '수집 비활성화' : running ? '수집 중…' : '지금 수집하기'}</button>
    </div>
    {error && <p className="error" role="alert">{error}</p>}
    {result && <p className="notice" role="status">수집 결과: {labels[result.status]} · 수신 {result.fetchedCount} · 신규 {result.savedCount} · 중복 {result.duplicateCount} · 실패 {result.failedCount}</p>}
    <div className="list-summary"><h2>실행 이력 <span>{history?.totalElements ?? 0}</span></h2><button className="filter-button" disabled={loading} onClick={() => { void load(page) }}>이력 새로고침</button></div>
    {loading && !history && <p className="news-state" role="status">수집 이력을 불러오는 중입니다…</p>}
    {history && history.items.length === 0 && <p className="news-state">아직 수집 이력이 없습니다. 지금 수집하거나 예약 실행을 기다려 주세요.</p>}
    {history && history.items.length > 0 && <div className="ingestion-table-wrapper"><table className="ingestion-table"><caption>Steam 수집 실행 이력</caption><thead><tr><th>실행</th><th>시각 (한국 시간)</th><th>방식</th><th>상태</th><th>수신</th><th>신규</th><th>중복</th><th>실패</th><th>오류 요약</th></tr></thead><tbody>{history.items.map(run => <tr key={run.id}><td>#{run.id}</td><td><time dateTime={run.startedAt}>{koreanDate(run.startedAt)}</time><br /><span>{run.finishedAt ? `${koreanDate(run.finishedAt)} 종료` : '진행 중'}</span></td><td>{run.trigger === 'MANUAL' ? '즉시 실행' : '예약 실행'}</td><td><span className={`ingestion-status ${run.status.toLowerCase()}`}>{labels[run.status]}</span></td><td>{run.fetchedCount}</td><td>{run.savedCount}</td><td>{run.duplicateCount}</td><td>{run.failedCount}</td><td className="ingestion-error">{run.errorSummary ?? '—'}</td></tr>)}</tbody></table></div>}
    {history && history.totalPages > 1 && <nav className="pagination" aria-label="수집 이력 페이지"><button className="filter-button" disabled={page === 0 || loading} onClick={() => setPage(value => value - 1)}>이전</button><span>{page + 1} / {history.totalPages}</span><button className="filter-button" disabled={page + 1 >= history.totalPages || loading} onClick={() => setPage(value => value + 1)}>다음</button></nav>}
  </main>
}
