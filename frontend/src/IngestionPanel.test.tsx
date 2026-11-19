import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IngestionPanel from './IngestionPanel'
import type { IngestionRun } from './IngestionPanel'

const run: IngestionRun = { id: 1, source: 'Steam 공식 소식', trigger: 'MANUAL', status: 'SUCCESS', startedAt: new Date().toISOString(), finishedAt: new Date().toISOString(), fetchedCount: 25, savedCount: 25, duplicateCount: 0, failedCount: 0, errorSummary: null }
const rows = (items: IngestionRun[] = []) => ({ items, page: 0, size: 10, totalElements: items.length, totalPages: items.length ? 1 : 0 })
const reply = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response
const denied = vi.fn()
beforeEach(() => { denied.mockReset(); vi.stubGlobal('fetch', vi.fn(async () => reply(rows()))) })
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
const show = () => render(<IngestionPanel token="admin-token" onAuthFailure={denied} />)

describe('관리자 Steam 수집 화면', () => {
  it('이력을읽고관리자토큰으로즉시수집한다', async () => {
    vi.mocked(fetch).mockImplementation(async (_input, options) => options?.method === 'POST' ? reply(run) : reply(rows([run])))
    show(); const actor = userEvent.setup()
    await screen.findByRole('table')
    await actor.click(screen.getByRole('button', { name: '지금 수집하기' }))
    expect(await screen.findByText('수집 결과: 성공 · 수신 25 · 신규 25 · 중복 0 · 실패 0')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/admin/ingestions', expect.objectContaining({ method: 'POST', headers: { Authorization: 'Bearer admin-token' } }))
  })
  it('수집중에는중복클릭을막는다', async () => {
    let finish!: (response: Response) => void
    vi.mocked(fetch).mockImplementation(async (_input, options) => options?.method === 'POST' ? new Promise(resolve => { finish = resolve }) : reply(rows()))
    show(); const actor = userEvent.setup(); await screen.findByText(/아직 수집 이력이 없습니다/)
    await actor.click(screen.getByRole('button', { name: '지금 수집하기' }))
    expect(screen.getByRole('button', { name: '수집 중…' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '수집 중…' }))
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
    await act(async () => finish(reply(run)))
    expect(await screen.findByRole('button', { name: '지금 수집하기' })).toBeEnabled()
  })
  it('일부실패의건수와오류요약을확인할수있다', async () => {
    vi.mocked(fetch).mockResolvedValue(reply(rows([{ ...run, status: 'PARTIAL', savedCount: 19, failedCount: 2, errorSummary: 'AppID 730: Steam HTTP 503' }])))
    show()
    expect(await screen.findByText('일부 실패')).toBeInTheDocument()
    expect(screen.getByText('AppID 730: Steam HTTP 503')).toBeInTheDocument()
  })
  it('일반회원의직접접근거절을안내한다', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reply({ code: 'FORBIDDEN', message: '관리자 권한이 필요합니다.' }, 403))
    show()
    expect(await screen.findByRole('alert')).toHaveTextContent('관리자 권한이 필요합니다')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
  it('만료와정지응답을공통인증안내로전달한다', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(reply({ code: 'ACCOUNT_SUSPENDED', message: '정지된 계정입니다.', suspension: { reason: '운영 정책 위반', suspendedAt: '2026-11-19T00:00:00Z' } }, 403))
    show()
    await waitFor(() => expect(denied).toHaveBeenCalledWith(expect.objectContaining({ code: 'ACCOUNT_SUSPENDED', suspension: expect.objectContaining({ reason: '운영 정책 위반' }) })))
  })
  it('중복실행거절을이력새로고침후에도보여준다', async () => {
    vi.mocked(fetch).mockImplementation(async (_input, options) => options?.method === 'POST' ? reply({ code: 'INGESTION_RUNNING', message: '이미 수집 중입니다.' }, 409) : reply(rows()))
    show(); const actor = userEvent.setup(); await screen.findByText(/아직 수집 이력이 없습니다/)
    await actor.click(screen.getByRole('button', { name: '지금 수집하기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('이미 수집 중입니다')
  })
  it('진행중이력을주기적으로다시읽는다', async () => {
    vi.useFakeTimers()
    vi.mocked(fetch).mockResolvedValueOnce(reply(rows([{ ...run, status: 'RUNNING', finishedAt: null }]))).mockResolvedValue(reply(rows([run])))
    await act(async () => { show() })
    expect(screen.getByRole('button', { name: '수집 중…' })).toBeDisabled()
    await act(async () => { vi.advanceTimersByTime(5100) })
    expect(screen.getByRole('button', { name: '지금 수집하기' })).toBeEnabled()
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('화면을나간뒤완료된요청은화면에반영하지않는다', async () => {
    let finish!: (response: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const view = show(); view.unmount()
    await act(async () => finish(reply(rows([run]))))
    expect(screen.queryByRole('table')).not.toBeInTheDocument(); expect(denied).not.toHaveBeenCalled()
  })
})
