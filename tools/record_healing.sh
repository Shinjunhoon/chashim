#!/bin/bash
# 시연 영상: 화면 전체와 컴퓨터 소리를 녹화하면서 충전 연동 힐링 모드("비 오는 숲")를 진행한다.
# 충전구 열림 → "충전을 시작했어요" 안내 → 쉬기 시작 → 충전 완료에 맞춰 마무리 → 설정 복구
# 사용법: tools/record_healing.sh [대기초=10] [녹화초=200] [시작%=78]
ADB=~/Library/Android/sdk/platform-tools/adb
APP=com.chashim.app/.MainActivity
WAIT=${1:-10}; LEN=${2:-200}; SOC=${3:-78}
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT=~/Desktop/차쉼_힐링_$(date +%H%M).mov
B=http://127.0.0.1:8088/vehicle
am() { $ADB shell am start -n $APP "$@" >/dev/null 2>&1; }
put() { curl -s -m 10 -X PUT "$B/$1" >/dev/null; }

# 준비: 처음 화면, 충전구 닫힘, 배터리 SOC%, 바람 3단, 창문 닫힘, 무드등 흰색
am --es cmd home --ef charge_kw 50
put "gear?gear=Park"
put "ev-battery?field=charge_port_open&value=false"
put "ev-battery?field=soc&value=$SOC"
put "hvac?power=true"; put "hvac?fan_speed=3"; put "hvac?driver_seat=0"
put "body?window_fl=0&window_fr=0&window_rl=0&window_rr=0"
put "body?ambient_color=%23ffffff"

echo "$WAIT초 뒤 녹화 시작"; sleep "$WAIT"
"$DIR/screenrec" "$OUT" "$LEN" &
REC=$!
sleep 3
"$DIR/simulate_charging.sh" "$SOC" 80 50 &   # 충전구 열기 → 안내창
sleep 6
am --es cmd start                             # "쉬기 시작"
wait $REC
echo "저장: $OUT"
