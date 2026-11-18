# 로컬 공용 Kafka에 전용 토픽만 추가합니다. 데이터와 기존 토픽 설정은 삭제하거나 변경하지 않습니다.
$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot '..\..\ProductQueryService\compose.kafka.yaml'
if (-not (Test-Path -LiteralPath $compose)) { throw '공용 로컬 Kafka Compose 파일을 찾지 못했습니다.' }
foreach ($source in @('account','product','order')) {
    $topic = 'stockdrop.notifications.' + $source + '.v1'
    & docker compose -f $compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic $topic --partitions 3 --replication-factor 1
    if ($LASTEXITCODE -ne 0) { throw '일반 알림 로컬 토픽 준비에 실패했습니다. 기존 데이터는 유지됩니다.' }
}
Write-Host '일반 알림 전용 로컬 토픽을 준비했습니다. 운영 권한과 보존 설정은 별도입니다.'
