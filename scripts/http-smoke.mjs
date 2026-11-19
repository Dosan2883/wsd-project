// 로컬 Vite 프록시와 실행 중인 백엔드를 검증하며 테스트 회원 한 명을 생성한다.
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'

const base = process.env.SMOKE_BASE_URL ?? 'http://127.0.0.1:5173'
const target = new URL(base)
assert(['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname), 'HTTP 검증은 로컬 서버에서만 실행할 수 있습니다.')
const email = `smoke-${randomUUID()}@example.invalid`
const password = randomUUID()
let checks = 0

async function request(path, expected, options = {}) {
  const result = await fetch(`${base}/api${path}`, {
    method: options.body ? 'POST' : 'GET',
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) },
    body: options.body ? JSON.stringify(options.body) : undefined,
    signal: AbortSignal.timeout(10000),
    redirect: 'error',
  })
  assert.equal(result.status, expected, `${path} status`)
  checks++
  return result.json()
}

assert.equal((await fetch(base)).status, 200)
await request('/health', 200)
await request('/me', 401)
await request('/articles', 401)
await request('/games', 401)
const member = await request('/auth/signup', 201, { body: { email, password } })
assert.equal(member.role, 'USER')
assert.equal(member.status, 'ACTIVE')
assert.equal(member.passwordHash, undefined)
await request('/auth/signup', 409, { body: { email: email.toUpperCase(), password } })
await request('/auth/signup', 400, { body: { email: `admin-${email}`, password, role: 'ADMIN' } })
await request('/auth/login', 401, { body: { email, password: 'incorrect-password' } })
const session = await request('/auth/login', 200, { body: { email, password } })
const me = await request('/me', 200, { token: session.accessToken })
assert.equal(me.id, member.id)
await request('/admin/access', 403, { token: session.accessToken })
const games = await request('/games', 200, { token: session.accessToken })
const articles = await request('/articles', 200, { token: session.accessToken })
assert(Array.isArray(articles.items))
if (process.argv.includes('--expect-demo')) {
  const demos = articles.items.filter(article => article.demo)
  assert.equal(demos.length, 3, '데모 프로필의 공개 글 3건이 필요합니다.')
  assert(games.length >= 3)
  const ids = demos.map(article => article.id).sort((a, b) => a - b)
  for (const item of demos) {
    const detail = await request(`/articles/${item.id}`, 200, { token: session.accessToken })
    assert.equal(detail.title, item.title)
    assert.equal(detail.demo, true)
    assert(detail.body.includes('실제 Steam에서 수집한 뉴스가 아닙니다.'))
    const filtered = await request(`/articles?gameId=${item.game.id}&q=${encodeURIComponent(item.game.name)}`, 200, { token: session.accessToken })
    assert(filtered.items.some(article => article.id === item.id))
  }
  if (process.argv.includes('--save-demo-snapshot')) {
    await mkdir(new URL('../.cache/', import.meta.url), { recursive: true })
    await writeFile(new URL('../.cache/demo-article-ids.json', import.meta.url), JSON.stringify(ids))
  }
  if (process.argv.includes('--check-demo-snapshot')) {
    const before = JSON.parse(await readFile(new URL('../.cache/demo-article-ids.json', import.meta.url), 'utf8'))
    assert.deepEqual(ids, before, '서버 재시작 전후 데모 글 ID가 같아야 합니다.')
  }
}
const segments = session.accessToken.split('.')
const payload = JSON.parse(Buffer.from(segments[1], 'base64url').toString())
payload.sub = '999999'
segments[1] = Buffer.from(JSON.stringify(payload)).toString('base64url')
await request('/me', 401, { token: segments.join('.') })
console.log(`HTTP 검증 통과: 화면 및 Vite 프록시를 통한 API ${checks}건. 로컬 테스트 회원 1명을 생성했습니다.`)
