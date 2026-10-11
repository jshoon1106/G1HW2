G1HW2 — Thread Pool 기반 좌석 예매 시스템

1. 조원
장승훈 20223134: 구현
정우진 20213132: 문서
김현중 20223099: 시연 영상

2. 환경
개발·정식 실측: Windows / JDK 17 / PowerShell 7
실행 도구: project.ps1 사용 시 Java 17 이상과 PowerShell 7 필요; 직접 Java 명령으로 실행할 때는 Java 17 이상만 필요
소스 빌드: JDK 17 이상(java·javac·jar); project.ps1의 Build 사용 시 PowerShell 7도 필요
도구 경로: PATH에서 검색
설치 후 새 터미널 실행 / 프로젝트 폴더 이동·쓰기 권한 필요
Windows 실행 정책: 다운로드한 project.ps1 차단 시 신뢰한 파일의 차단 해제 필요
Server: AWS EC2 master-pc / t3.micro / ap-southeast-2 / i-0248d422b27dc9f8a
주소: 13.211.23.109 / 서비스 TCP 5000
OS: Amazon Linux 2023.12.20260930, x86_64 / 커널 6.18.51-120.163.amzn2023.x86_64
Java: Amazon Corretto 17.0.20.10.1 / OpenJDK 17.0.20.1
의존성: Java 표준 라이브러리 / 외부 라이브러리·Maven·Gradle 불필요
네트워크: 보안 그룹 sg-0184572a73ef868ab, TCP 5000에 0.0.0.0/0 허용
주소·포트: 실행 인자 지정
로그 시각: 실제 KST(Asia/Seoul), HH:mm:ss.SSS / 경과시간: 동일 노드의 System.nanoTime()

3. 파일과 빌드
src/BookingServer.java: Listener, 고정 Worker, Queue, 통지, 서버 종료
src/BookingClients.java: 독립 Client 30개, 송수신, 요청 생성, 보유 상태 관리
src/SeatStore.java: 100석, 좌석별 Lock, FIFO Waitlist, 예약·취소
src/Shared.java: 설정, 자료형, 프레임 파서, 송신, 로그, Queue/CV
src/LogVerifier.java: Server·Client 로그의 독립 대조
analyze-pool.ps1: PowerShell 7 기반 읽기 전용 POOL 주기 진단
project.ps1: 환경 확인·빌드·실행·TCP 로그 수집·검증
run-client.cmd: 실행하는 Windows PC에서 Client 30개를 시작하여 원격 Server에 접속하는 실행 파일

소스 빌드(JDK 필요):
  pwsh -NoProfile -File project.ps1 -Action Build
개발 로그: profile=dev / 제출 로그: profile=final

직접 Java 개발 실행 예시:
Server·Client 실행: 별도 터미널 사용, Server 준비 → Client 실행 → 종료 후 LogVerifier 실행
재실행: dev-example을 새 runId·로그 폴더로 교체
  java -cp seat-booking.jar BookingServer --bind 127.0.0.1 --port 5010 --run-id dev-example --profile dev --clients 30 --requests 100 --min-ms 0 --max-ms 10 --log-dir logs/dev-example
  java -cp seat-booking.jar BookingClients --host 127.0.0.1 --port 5010 --run-id dev-example --profile dev --clients 30 --requests 100 --min-ms 0 --max-ms 10 --log-dir logs/dev-example --seed 20261009
  java -cp seat-booking.jar LogVerifier --log-dir logs/dev-example --profile dev
종료 코드: 정상 0 / 실행·검증 실패 1 / 인자 오류 2
로그 생성 위치: 동일 runId 폴더 안의 Server.txt·Client1~30.txt, 기존 역할별 파일 덮어쓰기 금지

4. 실행
사전 조건: Java 17 이상·PowerShell 7, 제공 JAR, 프로젝트 폴더 쓰기 권한, 같은 버전의 원격 Server 대기 상태
사전 확인(네트워크 연결 없음):
  pwsh -NoProfile -File project.ps1 -Action Check
확인: Java 버전·JAR·쓰기 권한
일반 실행: 제공 JAR과 대기 중인 원격 Server 사용, Build·원격 Server 시작 명령 불필요

4-1. 로컬 Client
Windows에서 run-client.cmd를 더블클릭하거나 터미널에서 .\run-client.cmd를 실행한다. 파일이 있는 프로젝트 폴더로 이동하여 아래 Clients 명령을 실행하며, 완료 후 결과를 볼 수 있도록 키 입력을 기다린다.
Windows 환경에서 Server IP 주소가 변경되면 run-client.cmd를 열어 -ServerHost 실행 인자 뒤의 IP 주소를 새 주소로 변경한다.
프로젝트 폴더에서 실행:
  pwsh -NoProfile -File project.ps1 -Action Clients -ServerHost 13.211.23.109 -Port 5000
runId 생략 시: 'final-KST시각-무작위값' 자동 생성 → logs/<runId>/에 Client1~30.txt 저장
접속 주소·포트 지정: -ServerHost·-Port 사용, 특정 로컬 경로 의존 없음
연결 재시도: HELLO 전 최초 연결 거부 시 최대 10초 재시도, 연결 이후 재접속·요청 재전송 없음
동시 실행 범위: 한 서비스에서 30 Client의 한 회차 처리, 여러 PC의 독립 회차 동시 실행 제한
정식 설정: profile=final, Client 30개, 각 5000건, 간격 200~1000ms
정식 회차 완료 후: TCP로 원격 Server의 같은 회차 Server.txt 자동 수집 → Server·Client 전체 로그 검증
저장 위치: logs/<runId>/ 안의 Server.txt·Client1~30.txt (총 31개)
수집 실패 시: Client 로그를 보존하고 같은 runId로 Collect를 재시도한다. 수집 성공 후 4-3의 LogVerifier 명령으로 전체 31개 로그를 검증한다. 예매 회차를 다시 실행할 필요는 없다.
개발 profile=dev: 자동 수집 없이 로컬 실행 후 LogVerifier로 검증

4-2. 원격 Server
별도 Server 구성 시 원격 Linux에서 실행:
  java -cp seat-booking.jar BookingServer --bind 0.0.0.0 --port 5000 --run-id auto --profile final --clients 30 --requests 5000 --min-ms 200 --max-ms 1000 --log-dir logs
--bind·--port: 서버 바인딩 주소·포트 지정; --log-dir: 회차별 로그의 상위 폴더 지정
--run-id auto: 첫 유효 HELLO의 runId 채택, 이미 사용한 runId의 연결 거부
직접 Java 실행: 회차 종료 후 프로세스가 종료된다. 이후 TCP로 완료 로그를 수집하려면 같은 JAR와 로그 상위 폴더를 사용하여 위 대기 Server 명령을 다시 실행한다.
제공된 원격 서비스: 정상 종료 후 systemd Restart=on-success, 1초 뒤 새 프로세스 대기

4-3. 대기·종료와 검증
대기 상태: Listener 1 + Worker 10 + Notifier 1 + Monitor 1
회차 로그 생성: 첫 유효 HELLO 수신 시 새 회차 폴더와 Server.txt 생성
회차 시작 전: 콘솔에 SERVER WAITING 출력, 회차 로그 파일 생성 없음
식별 전 무효 연결 처리: 잘못된 HELLO·시간 초과·연결 종료가 발생한 연결만 거부, Server 대기 유지
연결 수 제한: 등록 Client 최대 30개, 식별 전 Socket을 포함한 전체 수락 연결 최대 60개
정상 회차 종료: 자원 정리와 Thread join 후 exit 0 → 다음 Server 프로세스에서 좌석·Queue·Waitlist·통계 초기화
실패 회차 종료: FAIL 로그 보존, 자동 재시작 없음
평가 중: EC2와 Server 대기 서비스 유지, 전체 실험·평가 종료 후 EC2 중지

완료 회차 로그 재수집:
  pwsh -NoProfile -File project.ps1 -Action Collect -RunId RUN_ID -ServerHost 13.211.23.109 -Port 5000
RUN_ID 지정: Client 콘솔에 출력된 회차 식별자 사용, 관리자 인증 파일 불필요
로컬 Server.txt 저장: 기존 파일 덮어쓰기 금지, 수집 실패 시 임시 파일 제거
로그 수집 연결: 기존 TCP 포트로 연결 1개 사용, 예매 Client 번호·Request Queue·실험 지표에서 제외
수집 허용 대상: 종료된 원격 회차의 원본 로그, 현재 회차·미완료·실패 회차의 로그 요청 거부
수집 파일 검증: runId·바이트 수·SHA-256 대조, 최대 1GiB 제한, 임시 파일에 수신·검증 완료 후 Server.txt로 확정
수집 시간 제한: 수집 실패를 처리할 때 시작 후 30초가 지났으면 재시도를 중단한다. 각 연결 시도는 5초, 각 수신 호출은 30초의 제한을 둔다. 본문 수신 반복에서는 수집 시작 후 10분 경과 여부를 검사한다. 진행 중인 수신 호출과 헤더·종료 표식 읽기에는 이 전체 제한이 직접 적용되지 않으므로 실제 종료는 10분을 넘을 수 있다.
직접 Java Client 실행 시: 자동 수집 없음, Client 종료 후 위 Collect 명령으로 수집

기존 전체 로그 검증:
  java -cp seat-booking.jar LogVerifier --log-dir LOG_DIR --profile final
LOG_DIR 지정: 같은 회차의 Server.txt와 Client1~30.txt가 있는 폴더 사용, 현재 폴더 지정값은 .
검증 결과 기록: Server.txt와 Client1~30.txt의 마지막 VERIFIER | VERIFY 기록
각 Client: scope=client에 해당 Client의 SUCCESS·FAIL·WAITLISTED·평균 응답시간(ms)·NOTIFY·미해결·최종 보유 기록
31개 전체: scope=global에 모든 성능 지표·정합성·전체 결과별 건수 기록
검증은 원래 실행 이벤트의 바이트를 보존하고 사후 기록만 갱신; 별도 결과 파일 없이 31개 로그만 사용

5. Thread와 전달 경로
서버 응용 Thread: Listener/main 1 + Worker 10 + Notifier 1 + Monitor 1 = 13
Thread 생성: Server의 기존 main Thread를 Listener로 사용하고, 시작 시 Worker 10개·Notifier 1개·Monitor 1개를 새로 생성한다. 서버 응용 Thread는 총 13개이며 요청·연결별 추가 Thread·송신 중계·비동기 로그 Thread는 생성하지 않는다.
Client 구성: 독립 상태 30개, Client마다 송신 Thread 1개·수신 Thread 1개·TCP 연결 1개
Client main Thread 1개: 30개 Client의 시작·진행 출력·송수신 Thread join·로그 정리를 담당한다. Client 프로그램의 응용 Thread는 최대 61개(송신 30+수신 30+main 1)이다.
전체 응용 Thread: Server 13개+Client 프로그램 최대 61개=최대 74개. 명세의 Thread 수 제한은 Server에 적용되며 Client Thread 사용에는 제한이 없다. JVM 내부 Thread와 실행 스크립트는 이 수에 포함하지 않는다.
서버 역할별 수: Listener 1개는 TCP 수락·요청 파싱·Queue 적재·POOL·BYE·로그 파일 송신을 담당한다. Worker 10개는 Queue/CV 대기·좌석 처리·첫 응답 송신을 담당한다. Notifier 1개는 Notify Queue/CV 대기·NOTIFY 송신을 담당한다. Monitor 1개는 진행 정체를 감시하고 이상 상태를 기록한다.
클라이언트 전달 역할: 송신 Thread 30개는 HELLO와 요청을 보낸다. 수신 Thread 30개는 RESP·NOTIFY·BYE를 받아 요청 번호·좌석 버전으로 상태를 갱신한다.
SeatStore·Shared의 송신·로그 함수: 위 Thread에서 동기적으로 실행되며 별도 전달 Thread를 만들지 않는다. LogVerifier는 예매 종료 후 별도 프로세스의 main Thread에서 실행한다.
예매 연결: TCP 유지 연결 30개로 요청·응답·NOTIFY·BYE 전달
로그 수집: 로컬 실행 스크립트의 GET_LOG 요청 → 기존 Listener의 완료 회차 파일 조회 → TCP로 원본 파일 응답
로그 파일 송신: 기존 Listener의 NIO 부분 write 사용, 추가 Thread·중계 노드·포트 없음
동시 로그 파일 송신: 최대 2개 연결, 수집 오류의 예매 회차 FAIL 전파 없음
요청 전달·처리: Client 요청 전송 → Listener의 NIO 수신·파싱 → Request Queue 적재 → Worker 인출 → SeatStore 예약·취소 처리
첫 응답 전달: Worker → 요청을 보낸 Client
통지 전달: Worker의 좌석 인계 결과 → Notify Queue/CV → Notifier → 해당 좌석의 Waitlist 대기 Client
Listener 역할: 요청 수신·파싱·Queue 적재, 5초마다 100석 현황·Queue 길이·누적 처리량을 POOL에 기록; 예약·취소의 좌석 처리는 Worker 담당
POOL 관측 범위: 좌석별 현황, 전체 100석의 동일 시점 원자적 스냅샷 아님
Monitor: 1초마다 Queue·active·완료 수 감시, 이상 시 HEALTH 기록
Monitor 선택 이유: Listener 정체 시 독립 진단 / 단점: 선택 Thread 1개·관측 코드 추가

6. Queue·동시성 제어
Request Queue: ArrayDeque FIFO + ReentrantLock + notEmpty Condition
용량: 동적 확장, 논리 한도 Client 수 x 요청 수(정식 150000건)
요청 ID 검사: client=1~30·req=1~5000 범위와 중복 확인, 정상 입력의 Queue 용량 초과 방지
Queue 포화·요청 한도 위반 시: 회차를 FAIL로 종료, 정상 요청 드롭·임시 Worker 추가 없음
장점: Listener 포화 대기·요청 유실 방지 / 단점: 소형 고정 Queue 대비 최대 메모리 증가
Queue 통계: 인출(dequeue) 시 active 증가, 처리 종료(finish) 시 active 감소; 두 갱신과 최대 길이 기록을 동일 Queue Lock 안에서 처리
Worker 대기·깨우기: Queue가 비어 있으면 Condition.await()로 대기, 요청 적재 시 signal()로 깨우기, 종료 시 closed 설정 후 signalAll()로 전체 깨우기
Notify Queue: 별도 ArrayDeque FIFO와 Lock·Condition 사용, 논리 한도는 전체 요청 수
통지 완료 집계: 좌석 인계 시 outstanding 증가 → NOTIFY 송신과 로그 기록 완료 후 감소
Queue·통지 정리 완료 조건: Request Queue·Notify Queue 모두 비어 있음, active=0, outstanding=0
좌석별 보호: 100석 각각 ReentrantLock 1개, 해당 좌석의 owner·Waitlist·version·통계를 같은 Lock 안에서 접근
Critical Section: 상태 판정 → owner·Waitlist·통계 변경 → 응답용 불변 상태 복사
보호 효과: EMPTY 확인·배정의 원자성으로 이중예약 방지, 서로 다른 좌석의 병렬 처리
RESERVE 처리: 인자 범위 오류 시 Lock 획득 전 FAIL; EMPTY이면 배정 후 SUCCESS; 본인 소유 또는 본인 등록 Waitlist가 있으면 FAIL; 다른 Client 소유이면 Waitlist 등록 후 WAITLISTED
RESERVE_MULTI 처리: 2~4석·중복·범위 검사 → 오름차순 Lock 획득 → 전체 EMPTY 확인 → 전체 배정
MULTI 결과 처리: 실패 시 좌석·Waitlist 변경 없이 전체 FAIL, 성공·실패·중단 시 모두 finally에서 Lock 역순 해제
Lock Ordering 장점: 작은 번호부터 획득하는 공통 규약으로 순환 대기 예방
Lock Ordering 단점: 정렬·전체 Lock 획득 비용, 겹치는 좌석의 대기 / 최대 4석으로 정렬 비용 제한
CANCEL 처리: 요청 Client가 해당 좌석 owner인 경우만 SUCCESS; 대기자가 없으면 EMPTY로 변경, 대기자가 있으면 Lock 안에서 FIFO head에 직접 배정
FIFO 보장: Waitlist 등록 시 addLast, 취소에 따른 인계 시 pollFirst 사용, 중간 EMPTY 공개·신규 예약 끼어들기 방지
취소 응답·통지: 취소자에게 CANCEL 요청 번호의 첫 응답, 배정된 대기자에게 원래 RESERVE 요청 번호를 포함한 NOTIFY 전송
WAITLISTED 이후: Worker·Client의 다음 요청 처리·전송 계속, 좌석 배정 대기로 블로킹 없음
Lock 범위: Queue 접근·Socket 송신·파일 기록은 좌석 Lock 해제 후 수행, 여러 좌석 Lock의 동시 획득은 MULTI 처리에만 적용
Socket 송신: 연결별 sendLock으로 전체 프레임 송신 보호, 부분 write·0 write 발생 시 기존 송신 Thread가 OP_WRITE Selector로 대기
직접 송신 장점: 책임·경로 단순화 / 단점: 느린 Socket에서 Worker·Notifier 대기 / 메시지 제한 30초

7. 프로토콜과 Client 정책
프레임 형식: UTF-8, 줄바꿈 \n, 수신 \r\n 허용, 최대 16KiB, 분할·합쳐진 수신 처리

Client → Server:
  HELLO <run> <client> <final|dev>
  RESERVE <req> <seat>
  RESERVE_MULTI <req> <seatCsv>
  CANCEL <req> <seat>
Server → Client:
  RESP <req> <SUCCESS|FAIL|WAITLISTED> <reason> <seat:version:owner,...|->
  NOTIFY <req> <seat> <version>
  BYE <run> <unresolved>
  ERROR <reason>
종료 후 로그 수집용 별도 TCP:
  GET_LOG <run>
  LOG_BEGIN <run> <원본 바이트 수>\n
  <지정 길이의 원본 파일 바이트>
  LOG_END <SHA-256>\n
  LOG_ERROR <INVALID_RUN|NOT_READY|NOT_FOUND|BUSY>\n
수집 헤더·종료 표식: UTF-8·줄바꿈 구분
수집 파일 본문: 헤더의 바이트 수만큼 수신, 원본 바이트 유지
연결 구분: 예매 프레임과 로그 수집 본문에 별도 연결 사용, 로그 전송 이벤트는 첫 응답·성능 지표 집계에서 제외

좌석 상태: owner=0은 EMPTY / 예: RESP 8 SUCCESS OK 3:1:9,5:1:9
사유: OK, WAITLIST, ALREADY_OWNER, ALREADY_WAITING, TAKEN, NOT_OWNER, RANGE, COUNT, DUPLICATE_SEAT, FORMAT
Client 식별: 연결의 첫 프레임인 HELLO를 Listener에서 확인; Client는 접속 직후 요청 생성 시작, 전체 Client 접속 장벽 없음
첫 응답 집계: RESP 1개를 요청 완료 1건으로 계산, WAITLISTED 포함, HELLO·NOTIFY·BYE 제외
응답 순서 역전 처리: req로 원래 요청 식별, version 비교로 선행 NOTIFY와 오래된 응답 처리
Client 보유 갱신: 예약 SUCCESS 또는 NOTIFY 수신 시 신규 보유 반영, FAIL 응답으로 신규 보유 추가 없음
Client 상태 보호: 내부 Lock으로 짧게 접근, I/O·무작위 대기 중 Lock 해제
정식 설정: 30 Client x 5000건, 요청 전 200~1000ms 균등 무작위 대기
Client 송수신: 송신 Thread는 첫 응답·NOTIFY를 기다리지 않고 다음 요청 전송, 수신 Thread는 응답·통지를 별도로 처리
요청 비율(RESERVE/RESERVE_MULTI/CANCEL): 취소 가능 좌석 있음 50/20/30%, 없음 70/30/0%
다중 예약 좌석 선택: 2~4석 균등 선택, 중복 좌석 제외
예약 좌석 선택: 80% 확률로 1~10번, 나머지 20% 확률로 1~100번 선택; 취소까지 포함한 실제 인기 좌석 인자 비율 50% 이상 검증
취소 대상 선택: 확인된 본인 보유 좌석 중 이미 취소 요청을 보낸 좌석 제외; 대상이 없으면 예약 요청으로 대체
선택 이유: 인기 좌석 경합·FIFO 유도, 취소를 통한 통지 발생, 2~4석 원자성·Lock Ordering 검증
프레임 선택 이유: 로그 대조 편의·공통 파서의 TCP 경계 처리
난수: base seed=20261009, Client별 XOR 조합, 실제 seed INIT 기록 / 스케줄링·응답 순서의 완전 재현 보장 없음

8. 종료·실패·정합성 검증
정상 종료: 전체 첫 응답 송신·Queue drain → 남은 통지 완료 → BYE → 최종 좌석·통계 → Socket close → CV close/signalAll → Thread join → 로그 close
Client 종료: 5000건 송신과 첫 응답 수신 완료 후 BYE까지 연결 유지, 종료 시 통지 수·최종 보유 좌석·미해결 수 기록
미해결 Waitlist: 오류 제외, 잔여 건수 기록
송신 완료의 의미: 서버 completed·firstResponses는 전체 첫 응답 프레임을 TCP 스택에 기록한 건수이며, 상대 애플리케이션의 수신 확인은 아니다. Client RESPONSE는 수신 완료 기록이다. 검증기는 동일한 (Client ID, Request ID)의 양쪽 요청·응답을 대조하여 누락·중복·불일치를 확인한다.
회차 실패 조건: 식별된 Client의 BYE 전 연결 단절·예외·한도 위반·시간 초과
실패 후 재실행: 원본 로그 보존, 원격 Server 대기 상태 복구 후 새 runId 사용
실행 시간 제한: 식별 전 HELLO 대기 10초, 전체 Client 등록 60초, 메시지 송신 30초, 전체 회차 90분
검증 기록: 31개 로그 끝의 VERIFIER | VERIFY, phase=post-run
사후 시각은 로컬 검증 시각(KST), 측정값은 원래 실행 로그에서 계산; 원격 Server의 종료 시각과 구분
최종 정합성 PASS는 31개 로그의 전체 대조 완료 후에만 기록; 실행 종료만으로 PASS 추정하지 않음
재검증 실패 시 과거 사후 PASS를 FAIL로 교체하며, 부분 집계 값을 확정 지표로 기록하지 않음
원격 실행 기록: 원문 보존, 검증 기록은 별도 사후 결과로 표시

검증 항목:
- Client별 5000건·전체 150000건, 요청·첫 응답 일치, 누락·중복 없음
- 좌석별 seq 재생, EMPTY 배정 전제, 정당한 취소, FIFO 인계
- MULTI 성공의 전체 배정, 실패의 변경 없음, 오름차순 Lock 획득
- 배정 수−해제 수=최종 점유 수 확인, 인계 시 해제 1건과 배정 1건으로 집계
- 100석 Server owner와 Client 최종 보유 일치·중복 보유 없음
- WAITLISTED=수신 NOTIFY+미해결 Waitlist
- 인기 좌석 인자 비율, 동일 run/profile/JAR, 원격 Linux·정식 설정

지표 기준:
처리량: Server 첫 유효 Client의 TCP 수락~마지막 첫 응답 송신, 첫 응답 수/초
평균 응답시간 측정: Client 송신 직전~첫 응답 수신의 경과시간 사용, 노드 간 시각 차감 없음
Queue 최대 길이 측정: Queue Lock 안에서 기록한 정확한 최대값 사용, 5초 주기 샘플 최대값과 구분
이중예약 확인: 전체 좌석 상태 전이와 Client 최종 보유 좌석 대조
Deadlock·진행 정체 집계: 요청 대기·처리 중 완료 진전이 30초간 없는 경우 집계, 요청 없는 정상 대기 제외
Deadlock 예방: Lock Ordering 적용, 시간 제한은 진행 정체 진단과 실패 처리에 사용
Waitlist 평균 대기시간 측정: Server 대기 등록~NOTIFY 송신 완료 경과시간의 평균, 종료 시 미해결 요청 제외
Lock 경합: 좌석 tryLock 실패 횟수
최종 정합성: 전체 로그 대조 PASS/FAIL

8-1. 실측 결과
최신 정식 run: final-20261011-150558-1be5f40252144773
실행 날짜: 2026-10-11 KST. Server INIT 15:05:59.289, 정상 종료 15:57:10.406, 사후 검증 15:57:31.015
구성: Windows 11 Client 30개 → Amazon Linux EC2 13.211.23.109:5000 / 30x5000건, 200~1000ms
종료: Server·Client TERMINATE SUCCESS / 동일 회차 전체 31개 로그의 scope=global result=PASS
최종 이중예약 및 정합성 판정은 서버와 30개 클라이언트, 총 31개 로그에 기록된 VERIFIER | VERIFY의 scope=global 결과로 확인한다. 서버 종료 시의 DOUBLE_BOOKING_CHECK는 1차 수지 검사이며, PENDING은 사후 로그 검증 전 상태를 의미한다.
balance=PASS만으로 이중예약 0건을 확정하지 않는다. 최종 기록의 result=PASS, doubleBooking=0, consistency=PASS를 함께 확인한다.
JAR SHA-256: 1057b16f0817f7d0c17a99f2cde363b3243b45be0d0acd19b70c2ba979d3fd5f
버전 확인: 제출 JAR 및 Server·Client INIT SHA-256 일치; Server Linux, Client Windows_11

지표                    | 실측값         | 측정·확인 위치
------------------------|----------------|-----------------------------
전체 처리량             | 48.858296건/s  | Server, 경과 3070.103003534s
평균 응답시간           | 208.621337ms   | 30개 Client 첫 응답 시간
Request Queue 최대 길이 | 6건            | Queue Lock 내부 최대값
이중예약                | 0건            | 좌석 전이·최종 보유 대조
Deadlock/진행 정체       | 0회            | Monitor·완료 로그
Waitlist 평균 대기시간  | 68.258084s     | 완료 NOTIFY 11450건
좌석 Lock 경합          | 65회           | tryLock 실패 합계
최종 좌석 정합성        | PASS           | Server·Client 로그 대조

첫 응답: 150000=SUCCESS 36804+FAIL 101461+WAITLISTED 11735 / Client별 송신·응답 각 5000
좌석 수지: 배정 24309−해제 24283=점유 26 / 100석 Server owner·30개 Client 최종 보유 일치·중복 보유 없음
대기 수지: WAITLISTED 11735=NOTIFY 11450+미해결 285 / 인기 좌석 인자 75.9179%(검증 로그의 반올림 비율)
개별 Client1: 5000건=SUCCESS 1222+FAIL 3386+WAITLISTED 392, 평균 응답 206.760167ms, NOTIFY 382, 미해결 10
개발 폴더 로그 위치: logs/final-20261011-150558-1be5f40252144773/Server.txt 및 Client1~30.txt
제출 ZIP 로그 위치: 압축 해제 폴더 루트의 Server.txt 및 Client1~30.txt
전체 31개 로그: 129670315 bytes(약 129.67MB, 10진 MB), 사후 검증 기록 포함
최종 지표·정합성 기록: 31개 파일 끝의 scope=global result=PASS 및 각 Client의 scope=client result=PASS
수집된 원격 Server.txt는 73904660 bytes이며 SHA-256 확인 후 저장되었다. 사후 검증 기록이 포함된 제출 Server.txt는 73905139 bytes이다.
현재 제출 기준: 위 최신 정식 회차와 기존 JAR를 사용한다. 문서·별도 분석 도구 보강을 위해 Java·JAR·정식 로그를 변경하거나 부하 테스트를 재실행하지 않는다.

8-2. 공식 시나리오와 최신 실측 대조
확인 대상: 8-1과 동일한 2026-10-11 회차. 아래 예시는 해당 Server.txt의 실제 이벤트이며 고정 시나리오를 별도로 실행한 결과가 아니다.
Step 1 — 원격 Server 초기화: 100석 owner=0(EMPTY), 빈 Waitlist로 초기화. INIT의 seats=100·workers=10 및 READY의 appThreads=13을 확인했다.
Step 2 — 로컬 Client 접속: Client 1~30의 Server HELLO와 Client INIT·CONNECT 기록이 존재한다. 각 Client는 연결 후 전체 접속 장벽 없이 요청을 시작한다.
Step 3 — 단일 예약·Race Condition 검증: RESERVE 88808건. 예: 15:05:59.478 Client25 req=1, 좌석 10. 좌석별 seq 재생으로 EMPTY에서만 배정되었는지와 최종 보유 일치를 검증했다.
Step 4 — 다중 예약·Deadlock 회피 검증: RESERVE_MULTI 36909건. 예: 15:05:59.478 Client22 req=1, 좌석 [8,10]. LOCK의 오름차순과 성공 시 전체 배정·실패 시 변경 없음, 진행 정체 0회를 검증했다.
위 두 예시는 같은 기록 시각에 좌석 10을 포함한 요청이 접수된 사례다. 접수 시각은 실제 Lock 보유 구간의 동시성을 증명하지 않으며, 좌석 상태의 정당성은 seq와 요청·응답 대조로 확인한다.
Step 5 — 취소·FIFO 통지: CANCEL 24283건, HANDOFF·Server NOTIFY·Client NOTIFY 각 11450건. 15:06:00.219 Client4 req=2의 좌석 3 취소가 15:06:00.221 Client27 req=1로 직접 인계되었다. 전체 이력의 FIFO와 통지 요청 번호를 검증했다.
Step 6 — 정식 부하: Client별 SEND·RESPONSE 각 5000건, 전체 150000건. delayMs 범위 200~1000ms. 인기 좌석(1~10번) 비율 75.9179%. POOL 612개, 인접 간격 611개, 최소 4988ms·최대 5348ms·중앙값 5015ms, 6000ms 초과 0개이다.
Step 7 — 종료·정합성: 요청·통지 처리 완료 후 BYE, 100석 FINAL_SEAT, SUMMARY, TERMINATE 기록을 확인했다. 30개 Client의 최종 보유와 전체 로그의 최종 PASS를 대조했다. 세 수지 검사의 값은 8-1과 같다.

8-3. 독립 POOL 주기 진단 (PowerShell 7 필요)
개발 폴더에서:
  pwsh -NoProfile -File ./analyze-pool.ps1 -ServerLog ./logs/final-20261011-150558-1be5f40252144773/Server.txt
제출 ZIP 압축 해제 폴더에서:
  pwsh -NoProfile -File ./analyze-pool.ps1 -ServerLog ./Server.txt
예상 출력: poolSamples=612, poolIntervals=611, poolMinGapMs=4988, poolMaxGapMs=5348, poolMedianGapMs=5015, poolLongGaps=0, warningThresholdMs=6000, poolParseErrors=0, poolAmbiguousIntervals=0, poolMidnightRollovers=0, poolDiagnostic=PASS
이 스크립트는 로그를 읽기만 하며 LogVerifier 실행·로그 수정·JAR 재빌드를 하지 않는다.
서버는 System.nanoTime()으로 주기를 관리하지만 본 분석은 wall-clock HH:mm:ss.SSS 기록을 사용하므로 기록 지연·벽시계 보정의 영향을 받는다.
첫 연결→첫 POOL 및 마지막 POOL→종료 구간은 진단 대상에서 제외한다. 인접 POOL 기록 사이만 계산한다.
판정 우선순위: 시각 파싱 실패 FAIL → 기록 0~1개 INSUFFICIENT_DATA → 간격 수 불일치 FAIL → 모호한 역행·0ms 간격 또는 긴 간격 WARN → 나머지 PASS.
PASS 조건: 2개 이상 기록, 전체 시각 파싱 성공, poolIntervals=poolSamples−1, 해석 불가/비정상 간격 없음, poolLongGaps=0.
poolLongGaps는 gap > warningThresholdMs(기본 6000)인 횟수이며 정확히 6000ms는 정상 범위다. 이 기준은 과제 합격 기준이 아닌 독립 진단 기준이다.
23:59분→00:00분의 역행만 명백한 자정 경계로 가정하여 24시간을 더한다. 날짜가 없으므로 이 경우에도 시계 보정과 완전히 구별할 수 없다. 다른 역행·0ms는 원인을 확정하지 않고 WARN으로 보고한다.
역행·0ms도 관측 간격 통계에 포함하며 poolAmbiguousIntervals로 별도 집계한다. 파싱 실패 양옆의 간격은 계산하지 않는다.
poolDiagnostic=PASS는 주기 진단만 의미한다. 기존 LogVerifier의 최종 정합성 판정을 변경하거나 대신하지 않는다.
프로세스 종료 코드: PASS=0, FAIL=1, WARN=2, INSUFFICIENT_DATA=3. 파일 접근 실패는 PowerShell 오류로 보고한다.
기존 LogVerifier는 POOL 내용과 존재를 검사하며 전체 주기를 자동 검사하지 않는다.

9. 제출 구성과 압축 해제 후 확인
최종 제출명: G1HW2.zip / 조별 1명 / 업로드 전 제출 시스템 용량 제한 확인
내용: 전체 src, project.ps1, run-client.cmd, seat-booking.jar, Readme.txt, AllDefinedLogs.txt, analyze-pool.ps1, download.txt, Server.txt, Client1~30.txt
로그: 10월 11일 단일 원격 final 회차 전체 31개를 ZIP 루트에 배치. 129670315 bytes. 이전 회차·.git·임시 파일·실행 파일 복사본 제외.
영상: G1HW2.mp4, 5분 이내. download.txt에 실제 다운로드 링크가 필요하며 공유 권한·다운로드·재생을 확인한다.
현재 상태: 영상 링크 미확보로 download.txt 미포함. G1HW2-preparation.zip은 검증용 준비본이며 최종 제출본이 아니다.
압축 해제 폴더에서 환경 확인:
  pwsh -NoProfile -File ./project.ps1 -Action Check
읽기 전용 POOL 진단은 8-3의 제출 ZIP 명령을 사용한다.
정합성 재검증은 원본 보존을 위해 새 검증 디렉터리에 Server.txt 및 Client1~30.txt를 복사한 뒤 수행한다:
  java -cp seat-booking.jar LogVerifier --log-dir VERIFICATION_COPY_DIR --profile final
VERIFICATION_COPY_DIR는 새로 만든 로그 복사본 폴더의 실제 경로로 교체한다.
LogVerifier는 첫 VERIFIER | VERIFY 행부터의 사후 기록을 교체한다. 재실행 시 Server는 global 1개, 각 Client는 client 1개+global 1개를 유지하며 기존 기록에 중복 추가하지 않는다.
원본 소스·JAR·로그의 SHA-256을 압축 전후 대조하고 재검증 뒤에도 원본과 ZIP 내부 파일의 해시가 동일한지 확인한다.
