#!/bin/bash
# 녹화·테스트 전에 차 상태를 기본값으로 되돌린다.
# 사용법: tools/reset_demo.sh [배터리%]   예) tools/reset_demo.sh 77
# 버추얼 콘솔(http://127.0.0.1:8088)이 연결돼 있어야 한다 (adb forward tcp:8088 tcp:8088).
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
SOC=${1:-77}
B=http://127.0.0.1:8088/vehicle
put() {
  r=$(curl -s -m 10 -X PUT "$B/$1")
  if echo "$r" | grep -q '"error"'; then echo "❌ $1 → $r"; FAIL=1; else echo "✅ $1"; fi
}
put "gear?gear=Park"
put "ev-battery?field=charge_port_open&value=false"
put "ev-battery?field=soc&value=$SOC"
put "hvac/mode?mode=subscribe_hvac_base"
put "hvac?power=true"
put "hvac?fan_speed=3"
put "hvac?driver_temp=22"
put "hvac?driver_seat=0"
put "body?window_fl=0&window_fr=0&window_rl=0&window_rr=0"
put "body?ambient_color=%23ffffff"
if [ -n "$FAIL" ]; then
  echo "⚠️ 일부 실패. 콘솔에서 AVD를 Disabled → Enabled로 바꾼 뒤 다시 실행하세요."
  exit 1
fi
adb shell am force-stop com.chashim.app
adb shell am start -n com.chashim.app/.MainActivity >/dev/null
echo "준비 완료: 기어 P, 충전구 닫힘, 배터리 ${SOC}%, 바람 3단, 22°C, 열선 끔, 창문 닫힘, 무드등 흰색, 앱 홈 화면"
