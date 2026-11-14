#!/bin/bash
# 시연 영상 합치기: 레이스 하이라이트 → 소개 → 힐링 → 레이스 → 마무리
# 입력: ~/Desktop/차쉼_힐링_편집.mp4, ~/Desktop/차쉼_레이스_편집.mp4  출력: ~/Desktop/차쉼_시연영상.mp4
set -e
cd "$(dirname "$0")"
H=~/Desktop/차쉼_힐링_편집.mp4
R=~/Desktop/차쉼_레이스_편집.mp4
T=$(mktemp -d); N=0; : > "$T/list.txt"
ENC="-c:v libx264 -crf 20 -preset fast -pix_fmt yuv420p -r 30 -c:a aac -b:a 192k -ar 48000 -ac 2"

tempo() { case $1 in 1) echo "anull";; 1.5) echo "atempo=1.5";; 2) echo "atempo=2";; 3) echo "atempo=1.5,atempo=2";; esac; }
badge() { [ "$1" = 1 ] && echo "" || echo "▶▶ ${1}배속"; }

# clip 파일 시작 끝 배속 구분 제목 설명
clip() {
  N=$((N+1)); local o="$T/$(printf %02d $N).mp4"
  ./caption "$T/c$N.png" "$5" "$6" "$7" "$(badge $4)"
  ffmpeg -v error -y -ss "$2" -to "$3" -i "$1" -i "$T/c$N.png" -filter_complex \
    "[0:v]setpts=PTS/$4[s];[s][1:v]overlay=0:0,format=yuv420p[v];[0:a]$(tempo $4)[a]" \
    -map "[v]" -map "[a]" $ENC "$o"
  echo "file '$o'" >> "$T/list.txt"; echo "· $6"
}
# card 길이 구분 제목 설명
card() {
  N=$((N+1)); local o="$T/$(printf %02d $N).mp4"
  ./caption "$T/c$N.png" "$2" "$3" "$4" "" card
  ffmpeg -v error -y -loop 1 -t "$1" -i "$T/c$N.png" -f lavfi -t "$1" -i anullsrc=r=48000:cl=stereo \
    -vf "fade=in:0:12,fade=out:st=$(echo "$1-0.4" | bc):d=0.4,format=yuv420p" -shortest $ENC "$o"
  echo "file '$o'" >> "$T/list.txt"; echo "· [카드] $3"
}

# 1. 레이스 하이라이트
clip "$R" 44 47 1 "" "" ""
clip "$R" 92 95 1 "" "" ""
clip "$R" 104 107 1 "" "" ""
clip "$R" 120 123 1 "" "" ""
# 2. 소개
card 6 "현대자동차그룹 차량용 앱 개발 공모전" "차쉼" "충전을 기다리는 시간,\n차가 영상에 맞춰 바람·열선·창문·무드등을 움직이는 4D 휴식"
# 3. 힐링 모드
card 3 "모드 1" "힐링 모드 · 비 오는 숲" "충전이 끝나는 시간에 맞춰 쉬어요"
clip "$H" 0 10 1 "힐링 모드" "충전구를 꽂으면" "충전 속도를 재서\n80%까지 남은 시간을 계산하고\n쉬어 갈지 물어봐요"
clip "$H" 10 40 3 "힐링 모드" "안개 낀 숲" "실내 온도(19°C)를 읽고\n시트 열선부터 켜요\n무드등은 초록"
clip "$H" 40 70 3 "힐링 모드" "빗방울" "바람이 잠깐 한 단계 세져요\n무드등은 파랑"
clip "$H" 70 113 2 "힐링 모드" "폭우" "돌풍처럼 바람이 세졌다가\n돌아와요\n번개 때 화면과 무드등이 번쩍"
clip "$H" 113 130 2 "힐링 모드" "처마 밑" "시트 열선\n무드등은 주황"
clip "$H" 130 163 2 "힐링 모드" "비 그친 뒤" "창문을 10% 열고\n열선은 꺼요\n무드등은 노랑"
clip "$H" 163 175 1 "힐링 모드" "충전 완료와 함께 끝나요" "배터리 80%에 맞춰 마무리하고\n바람·창문·열선·무드등을\n시작 전으로 되돌려요"
# 4. 엔터테인 모드
card 3 "모드 2" "엔터테인 모드 · 제주 하늘 레이스" "차의 핸들·브레이크·방향지시등으로 조종하는 3D 레이스"
clip "$R" 0 18 1.5 "엔터테인 모드" "고르고, 벨트 매고, 출발" "핸들로 캐릭터를 넘기고\n브레이크로 골라요\n안전벨트를 매야 출발해요"
clip "$R" 18 50 3 "엔터테인 모드" "핸들로 조종해요" "조작판의 핸들 각도가\n게임과 같이 움직여요\n빨라질수록 바람이 세져요\n바다: 창문 10%, 바깥 공기"
clip "$R" 50 98 3 "엔터테인 모드" "한라산 구름 속으로" "구름 속: 와이퍼, 시트 통풍,\n실내 공기 순환\n별을 먹으면 무드등이 반짝"
clip "$R" 98 126 2 "엔터테인 모드" "해돋이와 성산일출봉" "시트 열선·핸들 열선\n무드등은 주황"
clip "$R" 126 138 1 "엔터테인 모드" "결승, 그리고 복구" "끝나면 바람·창문·열선·무드등을\n시작 전으로 되돌려요"
# 5. 마무리
card 7 "안전하게" "차쉼" "P단에서만 시작 · 기어가 바뀌면 바로 멈춤 · 에어컨 온도는 그대로\n테마를 더해 가며 넓혀 가는 차 안 휴식 앱"

ffmpeg -v error -y -f concat -safe 0 -i "$T/list.txt" -c copy ~/Desktop/차쉼_시연영상.mp4
rm -rf "$T"
echo "완성: ~/Desktop/차쉼_시연영상.mp4"
