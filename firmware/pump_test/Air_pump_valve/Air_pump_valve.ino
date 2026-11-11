// ESP32-S3 L298N 에어펌프 & 솔레노이드 밸브 제어 예제

// 핀 정의 (ESP32-S3 남는 GPIO 핀 사용)
const int PUMP_IN1 = 15;  // L298N IN1 -> 펌프 제어 A
const int PUMP_IN2 = 16;  // L298N IN2 -> 펌프 제어 B
const int VALVE_IN3 = 17; // L298N IN3 -> 밸브 제어 A
const int VALVE_IN4 = 18; // L298N IN4 -> 밸브 제어 B

void setup() {
  Serial.begin(115200);

  // 제어 핀 출력 설정
  pinMode(PUMP_IN1, OUTPUT);
  pinMode(PUMP_IN2, OUTPUT);
  pinMode(VALVE_IN3, OUTPUT);
  pinMode(VALVE_IN4, OUTPUT);

  // 초기 상태: 모두 OFF
  stopPump();
  closeValve();

  Serial.println("ESP32-S3 Air Pump & Valve Controller Ready");
}

// 에어펌프 가동
void startPump() {
  digitalWrite(PUMP_IN1, HIGH);
  digitalWrite(PUMP_IN2, LOW);
  Serial.println("Pump: ON");
}

// 에어펌프 정지
void stopPump() {
  digitalWrite(PUMP_IN1, LOW);
  digitalWrite(PUMP_IN2, LOW);
  Serial.println("Pump: OFF");
}

// 솔레노이드 밸브 열기 (통전)
void openValve() {
  digitalWrite(VALVE_IN3, HIGH);
  digitalWrite(VALVE_IN4, LOW);
  Serial.println("Valve: OPEN");
}

// 솔레노이드 밸브 닫기 (차단)
void closeValve() {
  digitalWrite(VALVE_IN3, LOW);
  digitalWrite(VALVE_IN4, LOW);
  Serial.println("Valve: CLOSED");
}

void loop() {
  // 테스트 동작 시퀀스 예시
  Serial.println("--- 시퀀스 시작 ---");
  
  // 1. 밸브 닫고 펌프 작동 (공기 주입/압력 형성)
  closeValve();
  startPump();
  delay(3000); // 3초간 공기 주입

  // 2. 펌프 끄고 밸브 열기 (배기/공기 배출)
  stopPump();
  openValve();
  delay(2000); // 2초간 배기

  // 3. 밸브 닫기
  closeValve();
  delay(3000);
}