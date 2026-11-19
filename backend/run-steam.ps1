[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$cacheDirectory = Join-Path $projectDirectory '.cache'
$logPath = Join-Path $cacheDirectory 'backend-steam-startup.log'

# 현재 프로세스에서만 JDK와 키를 준비하고 시스템 설정은 변경하지 않는다.
$javaCandidates = @($env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Java\jdk-21'))
$jdkDirectory = $null
foreach ($candidate in $javaCandidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $releasePath = Join-Path $candidate 'release'
    $compilerPath = Join-Path $candidate 'bin\javac.exe'
    if ((Test-Path -LiteralPath $releasePath) -and (Test-Path -LiteralPath $compilerPath)) {
        if (Get-Content -LiteralPath $releasePath | Select-String '^JAVA_VERSION="21[."]') {
            $jdkDirectory = $candidate
            break
        }
    }
}
if (-not $jdkDirectory) {
    throw 'JDK 21을 찾지 못했습니다. 설치 경로를 JAVA_HOME에 지정한 뒤 다시 실행해 주세요.'
}
$env:JAVA_HOME = $jdkDirectory

$existingServer = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
if ($existingServer) {
    throw '8080 포트를 사용 중인 서버가 있습니다. 기존 API 서버를 종료한 뒤 다시 실행해 주세요.'
}

if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    # PowerShell 5.1에서도 동작하는 난수 생성 방식이다.
    $randomBytes = New-Object byte[] 32
    $randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $randomGenerator.GetBytes($randomBytes) }
    finally { $randomGenerator.Dispose() }
    $env:JWT_SECRET = [Convert]::ToBase64String($randomBytes)
    Write-Host '로컬 실행용 JWT 키를 생성했습니다. 새 터미널에서 실행하면 기존 토큰은 만료되므로 다시 로그인해 주세요.'
} else {
    try { $decodedKey = [Convert]::FromBase64String($env:JWT_SECRET) }
    catch { throw 'JWT_SECRET은 Base64 문자열이어야 합니다. 키 값은 로그에 붙여 넣지 마세요.' }
    if ($decodedKey.Length -lt 32) { throw 'JWT_SECRET은 Base64 인코딩 전 난수 길이가 32바이트 이상이어야 합니다.' }
}

New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
$repositoryDirectory = Join-Path $cacheDirectory 'm2'
$mavenArguments = @("-Dmaven.repo.local=$repositoryDirectory", '-B', 'spring-boot:run', '-Dspring-boot.run.profiles=steam')
Write-Host "API 서버를 steam 프로필로 실행합니다. 로그: $logPath"
Write-Host '서버 종료는 Ctrl+C, 화면 주소는 http://127.0.0.1:5173 입니다.'

Push-Location $PSScriptRoot
try {
    # Windows PowerShell의 표준 오류도 로그에 남기고 실제 Maven 종료 코드를 확인한다.
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & (Join-Path $PSScriptRoot 'mvnw.cmd') @mavenArguments 2>&1 | Tee-Object -FilePath $logPath
        $mavenExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorPreference }
    if ($mavenExitCode -ne 0) {
        throw "API 서버 실행에 실패했습니다. 마지막 Maven 요약보다 위쪽 원인을 확인해 주세요. 전체 로그: $logPath"
    }
} finally { Pop-Location }
