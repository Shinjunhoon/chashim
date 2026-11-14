#!/bin/bash
# 시연 영상: 화면 전체와 컴퓨터 소리를 녹화하면서 레이스를 자동 조종으로 끝까지 진행한다.
# 사용법: tools/record_race.sh [대기초=10] [녹화초=240]
ADB=~/Library/Android/sdk/platform-tools/adb
APP=com.chashim.app/.MainActivity
WAIT=${1:-10}; LEN=${2:-240}
OUT=~/Desktop/차쉼_레이스_$(date +%H%M).mov
am() { $ADB shell am start -n $APP "$@" >/dev/null; }

am --es cmd home --ez belt false       # 처음 화면, 벨트 안 맨 상태
echo "$WAIT초 뒤 녹화 시작"; sleep "$WAIT"
"$(dirname "$0")/screenrec" "$OUT" "$LEN" &
REC=$!
sleep 2
am --ez race_auto true --es cmd race   # 캐릭터 선택 → 벨트 화면
sleep 8
am --ez belt true                      # 벨트 맴 → 출발
wait $REC
echo "저장: $OUT"
