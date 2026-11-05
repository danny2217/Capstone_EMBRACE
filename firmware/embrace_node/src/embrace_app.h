// embrace_app.h — 펌웨어 시작점
#pragma once

// 채널 표 생성 → ADC 초기화 → 큐 생성 → 센싱·전송 태스크 시작. setup() 에서 한 번 호출.
void embraceBegin();
