<#
일반 PostgreSQL 컨테이너 안에 Notification 전용 논리 DB, 역할, 스키마만 준비합니다.
기존 역할 비밀번호와 다른 서비스 DB는 변경하지 않으며 비밀값, SQL 원문은 출력하지 않습니다.
#>
param([string]$ContainerName = 'stockdrop-account-postgres')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $workspace '.env'
$utf8 = New-Object Text.UTF8Encoding($false)
if (-not (Test-Path -LiteralPath $envFile)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $generated = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    $sample = [IO.File]::ReadAllText((Join-Path $workspace '.env.example'))
    $sample = $sample.Replace('replace-with-local-notification-password', $generated)
    # 새 파일만 작성하며 기존 사용자의 .env는 덮어쓰지 않습니다.
    $stream = [IO.File]::Open($envFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try { $content = $utf8.GetBytes($sample); $stream.Write($content,0,$content.Length) } finally { $stream.Dispose() }
}
$settings = [IO.File]::ReadAllText($envFile)
$passwordMatches = [regex]::Matches($settings, '(?m)^DB_PASSWORD=([^\r\n]*)')
$usernameMatches = [regex]::Matches($settings, '(?m)^DB_USERNAME=([^\r\n]*)')
if ($passwordMatches.Count -ne 1 -or [string]::IsNullOrWhiteSpace($passwordMatches[0].Groups[1].Value)) {
    throw 'DB_PASSWORD는 비어 있지 않은 한 줄이어야 합니다.'
}
$password = $passwordMatches[0].Groups[1].Value
if ($password -eq 'replace-with-local-notification-password' -or $password.Contains("'") -or $password.Contains('\')) {
    throw '샘플 비밀번호나 SQL 초기화에서 지원하지 않는 비밀번호 문자는 사용할 수 없습니다.'
}
if ($usernameMatches.Count -ne 1 -or $usernameMatches[0].Groups[1].Value -ne 'notification') {
    throw 'DB_USERNAME=notification가 정확히 한 줄 필요합니다.'
}
$sql = @"
\set ON_ERROR_STOP on
\set ai_password '$password'
DO `$`$
BEGIN
 IF current_database() <> 'stockdrop' OR NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='account')
 THEN RAISE EXCEPTION 'Not the local general database'; END IF;
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='notification' AND
   (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolcanlogin))
   OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON m.member=r.oid WHERE r.rolname='notification')
 THEN RAISE EXCEPTION 'Notification role privileges are not isolated'; END IF;
 IF EXISTS (SELECT 1 FROM pg_database d JOIN pg_roles r ON d.datdba=r.oid
   WHERE d.datname='notification_service' AND r.rolname <> current_user)
 THEN RAISE EXCEPTION 'Notification database owner mismatch'; END IF;
END `$`$;
SELECT format('CREATE ROLE notification LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L', :'ai_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='notification') \gexec
SELECT 'CREATE DATABASE notification_service' WHERE NOT EXISTS
 (SELECT 1 FROM pg_database WHERE datname='notification_service') \gexec
REVOKE ALL ON DATABASE notification_service FROM PUBLIC;
GRANT CONNECT ON DATABASE notification_service TO notification;
\connect notification_service
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS notification AUTHORIZATION notification;
REVOKE ALL ON SCHEMA notification FROM PUBLIC;
DO `$`$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON n.nspowner=r.oid WHERE n.nspname='notification' AND r.rolname='notification')
 THEN RAISE EXCEPTION 'Notification schema owner mismatch'; END IF;
END `$`$;
ALTER ROLE notification IN DATABASE notification_service SET search_path=notification;
"@
try {
    # SQL은 표준 입력으로 전달하고 실패 원문의 비밀값 노출을 방지합니다.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = $sql | & docker exec -i $ContainerName sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' 2>&1
    $result = $LASTEXITCODE
    if ($result -ne 0) { throw 'Notification DB 초기화가 실패했습니다. 기존 데이터와 역할 비밀번호는 유지됩니다.' }
    # 기존 역할의 오래된 비밀번호를 성공으로 오인하지 않도록 실제 TCP 로그인을 확인합니다.
    # Windows 파이프의 CRLF가 비밀번호에 CR을 붙이지 않도록 첫 구분자를 LF로 고정합니다.
    # SQL도 stdin으로 전달해 sh -c 내부의 중첩 인용 문제를 피하고 실제 TCP 인증은 유지합니다.
    $loginInput = $password + "`nSELECT current_user;`n"
    $login = $loginInput | & docker exec -i $ContainerName sh -c 'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -X -h "$HOSTNAME" -U notification -d notification_service -v ON_ERROR_STOP=1 -At' 2>&1
    if ($LASTEXITCODE -ne 0 -or ($login -join '').Trim() -ne 'notification') {
        throw 'Notification TCP 로그인 검증이 실패했습니다. 기존 역할의 비밀번호는 변경하지 않았습니다.'
    }
} finally { $ErrorActionPreference = $previous; $loginInput = $null }
Write-Host '일반 PostgreSQL의 notification_service DB와 notification 전용 계정, 스키마 연결을 확인했습니다.'
