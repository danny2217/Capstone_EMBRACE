// ===============================================
// DTP 스마트 조끼 공압 구동부 진단 테스트 코드
// MCU: ESP32-S3 DevKitC-1
// Driver: L298N Dual H-Bridge Module
// ===============================================

// 핀 정의
const int PUMP_IN1 = 4;   // L298N IN1 (펌프 +)
const int PUMP_IN2 = 5;   // L298N IN2 (펌프 -)
const int VALVE_IN3 = 6;  // L298N IN3 (밸브 +)
const int VALVE_IN4 = 7;  // L298N IN4 (밸브 -)

void setup() {
  Serial.begin(115200);
  delay(1000);
  
  Serial.println("\n=================================");
  Serial.println("  DTP 에어펌프/밸브 테스트 시작  ");
  Serial.println("=================================");

  // 핀 출력 모드 설정
  pinMode(PUMP_IN1, OUTPUT);
  pinMode(PUMP_IN2, OUTPUT);
  pinMode(VALVE_IN3, OUTPUT);
  pinMode(VALVE_IN4, OUTPUT);

  // 초기 상태: 전원 모두 차단
  stopAll();
}

void loop() {
  // [1단계] 에어펌프 가동 (에어백 가압)
  Serial.println("\n[STEP 1] 에어펌프 작동 중... (가압 테스트 5초)");
  digitalWrite(VALVE_IN3, LOW);   // 밸브 닫힘 (공기 유출 방지)
  digitalWrite(VALVE_IN4, LOW);
  
  digitalWrite(PUMP_IN1, HIGH);  // 펌프 가동
  digitalWrite(PUMP_IN2, LOW);
  delay(5000);

  // [2단계] 압력 유지 (밀폐 및 누설 테스트)
  Serial.println("[STEP 2] 펌프 정지 / 압력 유지 중... (밀폐 테스트 5초)");
  digitalWrite(PUMP_IN1, LOW);   // 펌프 정지
  digitalWrite(PUMP_IN2, LOW);
  digitalWrite(VALVE_IN3, LOW);  // 밸브 닫힘 유지를 통해 공기 갇힘
  digitalWrite(VALVE_IN4, LOW);
  delay(5000);

  // [3단계] 솔레노이드 밸브 개방 (비상 배기 테스트)
  Serial.println("[STEP 3] 솔레노이드 밸브 개방! (배기 테스트 3초)");
  digitalWrite(PUMP_IN1, LOW);   // 펌프 정지
  digitalWrite(PUMP_IN2, LOW);
  
  digitalWrite(VALVE_IN3, HIGH); // 밸브 전원 인가 (개방)
  digitalWrite(VALVE_IN4, LOW);
  delay(3000);

  // [4단계] 전체 정지 및 대기
  Serial.println("[STEP 4] 사이클 완료 - 3초 대기 후 재시작");
  stopAll();
  delay(3000);
}

// 전체 시스템 안전 정지 함수
void stopAll() {
  digitalWrite(PUMP_IN1, LOW);
  digitalWrite(PUMP_IN2, LOW);
  digitalWrite(VALVE_IN3, LOW);
  digitalWrite(VALVE_IN4, LOW);
}