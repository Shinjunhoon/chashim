#!/bin/bash
# 에뮬레이터용 충전 시뮬레이터: 버추얼 콘솔의 배터리 %를 충전 속도에 맞춰 올린다.
# 사용법: tools/simulate_charging.sh [시작%] [목표%] [kW]   예) tools/simulate_charging.sh 77 80 50
# 버추얼 콘솔(http://127.0.0.1:8088)이 연결돼 있어야 한다 (adb forward tcp:8088 tcp:8088).
START=${1:-77}; TARGET=${2:-80}; KW=${3:-50}
CAP_WH=$(curl -s http://127.0.0.1:8088/vehicle/state | python3 -c "import json,sys; print(json.load(sys.stdin)['ev_battery']['maximum_capacity_kwh']*1000)")
STEP_SEC=$(python3 -c "print(round($CAP_WH/100/($KW*1000)*3600, 1))")   # 1% 충전에 걸리는 시간
put() { curl -s -m 10 -X PUT "http://127.0.0.1:8088/vehicle/ev-battery?field=$1&value=$2" >/dev/null; }
echo "용량 ${CAP_WH}Wh, ${KW}kW → 1%마다 ${STEP_SEC}초"
put soc "$START"
put charge_port_open true
echo "$(date +%T) 충전 시작 ${START}%"
for ((soc=START+1; soc<=TARGET; soc++)); do
  sleep "$STEP_SEC"
  put soc "$soc"
  echo "$(date +%T) ${soc}%"
done
echo "목표 도달. 충전구는 열어 둔 채로 둡니다."
