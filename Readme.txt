G1HW2 — Thread Pool 기반 좌석 예매 시스템

제출 상태: PENDING — 키 없는 수집 버전의 정식 부하 검증·시연 영상 링크 대기 / 기존 정식 실측 보존

1. 조원
장승훈 20223134: 구현
정우진 20213132: 문서
김현중 20223099: 시연 영상

2. 환경
개발·기존 실측: Windows / JDK 17 / PowerShell 7
실행 도구: Java 17 이상·PowerShell 7 / 소스 빌드: JDK 17 이상(java·javac·jar)
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
project.ps1: 환경 확인·빌드·실행·TCP 로그 수집·검증

소스 빌드(JDK 필요):
  pwsh -NoProfile -File project.ps1 -Action Build
개발 로그: profile=dev / 제출 로그: profile=final

직접 Java 개발 실행 예시:
Server·Client: 별도 터미널 / Server 준비 후 Client 실행 / 종료 후 LogVerifier 실행
재실행: dev-example을 새 runId·로그 폴더로 교체
  java -cp seat-booking.jar BookingServer --bind 127.0.0.1 --port 5010 --run-id dev-example --profile dev --clients 30 --requests 100 --min-ms 0 --max-ms 10 --log-dir logs/dev-example
  java -cp seat-booking.jar BookingClients --host 127.0.0.1 --port 5010 --run-id dev-example --profile dev --clients 30 --requests 100 --min-ms 0 --max-ms 10 --log-dir logs/dev-example --seed 20261009
  java -cp seat-booking.jar LogVerifier --log-dir logs/dev-example --profile dev
종료 코드: 정상 0 / 실행·검증 실패 1 / 인자 오류 2
로그 생성: 동일 runId 폴더의 Server.txt·Client1~30.txt / 기존 역할별 파일 덮어쓰기 금지

4. 실행
사전 조건: Java 17 이상·PowerShell 7, 제공 JAR, 프로젝트 폴더 쓰기 권한, 같은 버전의 원격 Server 대기 상태
사전 확인(네트워크 연결 없음):
  pwsh -NoProfile -File project.ps1 -Action Check
확인: Java 버전·JAR·쓰기 권한 / JDK 도구는 빌드·개발 검증용 안내
일반 실행: 제공 JAR 사용 / Build·원격 Server 실행 불필요

4-1. 로컬 Client
프로젝트 폴더에서 실행:
  pwsh -NoProfile -File project.ps1 -Action Clients -ServerHost 13.211.23.109 -Port 5000
runId 생략: final-KST시각-무작위값 자동 생성 / logs/<runId>/Client1~30.txt 저장
주소·포트: -ServerHost·-Port로 변경 / 특정 로컬 경로 의존 없음
최초 연결 거부: HELLO 전 최대 10초 재시도 / 연결 후 재접속·요청 재전송 없음
동시 실행: 한 서비스당 한 회차(30 Client) / 여러 PC의 독립 회차 동시 실행 제한
정식 설정: profile=final, Client 30개, 각 5000건, 간격 200~1000ms
완료 후: TCP로 같은 회차 Server.txt 자동 수집 → 전체 로그 검증
저장: logs/<runId>/Server.txt·Client1~30.txt·verification.txt
수집 실패: Client 로그 보존 / 같은 runId의 Collect 재시도 / 예매 재실행 불필요
개발 profile=dev: 자동 수집 제외 / 기존 로컬 개발 검증 사용

4-2. 원격 Server
별도 Server 구성 시 원격 Linux에서 실행:
  java -cp seat-booking.jar BookingServer --bind 0.0.0.0 --port 5000 --run-id auto --profile final --clients 30 --requests 5000 --min-ms 200 --max-ms 1000 --log-dir logs
--bind·--port: 서버 바인딩 주소·포트 / --log-dir: 회차별 로그의 상위 폴더
--run-id auto: 첫 유효 HELLO의 runId 채택 / 기존 runId 거부
직접 Java 실행: 회차 종료 후 프로세스 종료
제공된 원격 서비스: 정상 종료 후 systemd Restart=on-success, 1초 뒤 새 프로세스 대기

4-3. 대기·종료와 검증
대기 상태: Listener 1 + Worker 10 + Notifier 1 + Monitor 1
회차 로그: 첫 유효 HELLO에서 새 폴더·Server.txt 생성
회차 시작 전 출력: 콘솔 SERVER WAITING / 공용 회차 로그 없음
식별 전 무효 HELLO·시간 초과·연결 종료: 해당 연결만 거부, 대기 유지
연결 한도: 등록 Client 30개 / 식별 전 Socket 포함 임시 수락 60개
정상 종료: 전체 정리·join 후 exit 0 / 다음 프로세스의 좌석·Queue·Waitlist·통계 초기화
실패 회차: FAIL 로그 보존, 자동 재시작 없음
평가 기간: EC2·대기 서비스 유지 / 전체 실험·평가 종료 후 EC2 중지

완료 회차 로그 재수집:
  pwsh -NoProfile -File project.ps1 -Action Collect -RunId RUN_ID -ServerHost 13.211.23.109 -Port 5000
RUN_ID: Client 콘솔의 회차 식별자 / 관리자 인증 파일 불필요
기존 Server.txt: 덮어쓰기 금지 / 수집 실패 시 임시 파일 제거
수집 전용 연결 1개: 같은 TCP 포트 / 예매 Client 번호·Request Queue·실험 지표에서 제외
수집 조건: 종료된 원격 회차의 원본 로그 / 현재 회차·미완료·실패 로그 거부
검사: runId·바이트 수·SHA-256 / 최대 1GiB / 부분 수신·임시 파일 후 확정
제한: 재시작·연결 재시도 30초, 수신 무진전 30초, 전체 수집 10분
직접 Java Client 실행: 자동 수집 제외 / 종료 후 위 Collect 명령 사용

기존 전체 로그 검증:
  java -cp seat-booking.jar LogVerifier --log-dir LOG_DIR --profile final
LOG_DIR: 같은 회차의 Server.txt·Client1~30.txt가 있는 폴더 / 현재 폴더는 . 지정
결과: verification.txt 및 Server.txt의 사후 검증 기록

5. Thread와 전달 경로
서버 응용 Thread: Listener/main 1 + Worker 10 + Notifier 1 + Monitor 1 = 13
생성 시점: 서버 시작 / 요청·연결별 추가 Thread, 송신 중계, 비동기 로그 Thread 없음
Client: 독립 상태 30개, 각각 송신·수신 Thread 1개와 TCP 연결 1개
예매 TCP 유지 연결: 30개 / 요청·응답·NOTIFY·BYE 공용
로그 수집: 로컬 실행 스크립트 → 기존 Listener → 완료된 회차 파일 / 직접 TCP 응답
수집 송신: 같은 Listener의 NIO 부분 write / 추가 Thread·중계 노드·포트 없음
동시 파일 송신: 최대 2개 / 수집 오류의 회차 FAIL 전파 없음
요청: Client → Listener(NIO 감시·파싱) → Request Queue → Worker → SeatStore
첫 응답: Worker → 요청 Client / 통지: Worker → Notify Queue/CV → Notifier → 대기 Client
Listener: 예약·취소 처리 제외 / 5초마다 100석·Queue 길이·누적 처리량 POOL 기록
POOL: 좌석별 관측값 / 전체 100석의 동일 시점 원자적 스냅샷 아님
Monitor: 1초마다 Queue·active·완료 수 감시, 이상 시 HEALTH 기록
Monitor 선택 이유: Listener 정체 시 독립 진단 / 단점: 선택 Thread 1개·관측 코드 추가

6. Queue·동시성 제어
Request Queue: ArrayDeque FIFO + ReentrantLock + notEmpty Condition
용량: 동적 확장, 논리 한도 Client 수 x 요청 수(정식 150000건)
중복·한도 검사: client 1~30, req 1~5000 / 정상 입력의 용량 초과 방지
포화·한도 위반: 실행 FAIL / 정상 요청 드롭·임시 Worker 추가 없음
장점: Listener 포화 대기·요청 유실 방지 / 단점: 소형 고정 Queue 대비 최대 메모리 증가
dequeue·active 증가, finish·active 감소, 최대 길이: 동일 Queue Lock 내부 처리
Worker 대기·깨우기: 빈 Queue에서 Condition.await(), 적재 시 signal(), 종료 시 closed+signalAll()
Notify Queue: 별도 ArrayDeque FIFO + Lock/Condition / 논리 한도 전체 요청 수
통지 수지: 인계 시 outstanding 증가, NOTIFY 송신·로그 완료 후 감소
종료 조건: 두 Queue 비어 있음, active=0, outstanding=0

좌석 보호: 100석별 ReentrantLock 1개 / owner·Waitlist·version·통계의 동일 Lock 보호
Critical Section: 상태 판정 → owner·Waitlist·통계 변경 → 응답용 불변 상태 복사
근거: EMPTY 확인·배정의 원자성으로 이중예약 방지 / 서로 다른 좌석의 병렬 처리
RESERVE: 범위 오류→Lock 전 FAIL, EMPTY→SUCCESS, 본인 소유·기존 대기→FAIL, 타인 소유→WAITLISTED
RESERVE_MULTI: 2~4석·중복·범위 검사→오름차순 Lock 획득→전체 EMPTY 확인→전체 배정
MULTI 실패: 좌석·Waitlist 변경 없이 전체 FAIL / 성공·실패·중단 모두 finally 역순 unlock
Lock Ordering 장점: 작은 번호부터 획득하는 공통 규약으로 순환 대기 예방
Lock Ordering 단점: 정렬·전체 Lock 획득 비용, 겹치는 좌석의 대기 / 최대 4석으로 정렬 비용 제한
CANCEL: 본인 owner만 SUCCESS / 대기 없음→EMPTY, 대기 있음→Lock 안에서 FIFO head로 직접 인계
FIFO: addLast 등록·pollFirst 인계 / 중간 EMPTY 공개·신규 예약 끼어들기 방지
통지: 취소자의 CANCEL req 응답, 대기자의 원래 RESERVE req로 NOTIFY
WAITLISTED: 첫 응답 후 Worker·Client의 다음 요청 진행 / 좌석 배정 대기 블로킹 없음
좌석 Lock 해제 후 Queue·송신·파일 기록 / 여러 좌석 Lock의 동시 획득은 MULTI만 해당
Socket 송신: 연결별 sendLock으로 전체 프레임 보호 / 부분 write·0 write는 기존 Thread의 OP_WRITE Selector 대기
직접 송신 장점: 책임·경로 단순화 / 단점: 느린 Socket에서 Worker·Notifier 대기 / 메시지 제한 30초

7. 프로토콜과 Client 정책
형식: UTF-8, 줄바꿈 \n, 수신 \r\n 허용, 최대 16KiB / 분할·합쳐진 수신 처리

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
수집 헤더·종료 표식: UTF-8·줄바꿈 / 파일 본문: 길이 기준, 원본 바이트 유지
예매 프레임과 수집 본문: 별도 연결 / 로그 전송 이벤트의 첫 응답·성능 지표 합산 제외

좌석 상태: owner=0은 EMPTY / 예: RESP 8 SUCCESS OK 3:1:9,5:1:9
사유: OK, WAITLIST, ALREADY_OWNER, ALREADY_WAITING, TAKEN, NOT_OWNER, RANGE, COUNT, DUPLICATE_SEAT, FORMAT
HELLO: 첫 프레임, Listener 식별·검증 / 접속 직후 요청 생성, 전체 접속 장벽 없음
집계: RESP 1개=첫 응답 1건, WAITLISTED 포함 / HELLO·NOTIFY·BYE 제외
응답 역전: req 매칭·version 비교로 NOTIFY 선행 및 오래된 응답 처리
보유 갱신: 성공 예약·NOTIFY만 신규 배정 / FAIL의 신규 보유 추가 금지
Client 상태: 짧은 내부 Lock 보호 / I/O·무작위 대기 중 Lock 해제

정식 설정: 30 Client x 5000건, 요청 전 200~1000ms 균등 무작위 대기
전송: 첫 응답·NOTIFY 대기 없이 다음 요청 진행 / 별도 수신 Thread
요청 비율(RESERVE/RESERVE_MULTI/CANCEL): 취소 가능 좌석 있음 50/20/30%, 없음 70/30/0%
MULTI: 2~4석 균등 선택, 중복 제외
예약 좌석: 80% 확률로 1~10번, 나머지 20%는 1~100번 / 취소 포함 실제 인기 좌석 인자 비율 50% 이상 검증
CANCEL: 확인된 본인 보유·취소 진행 중 제외 / 대상 없음→예약
선택 이유: 인기 좌석 경합·FIFO 유도, 취소를 통한 통지 발생, 2~4석 원자성·Lock Ordering 검증
프레임 선택 이유: 로그 대조 편의·공통 파서의 TCP 경계 처리
난수: base seed=20261009, Client별 XOR 조합, 실제 seed INIT 기록 / 스케줄링·응답 순서의 완전 재현 보장 없음

8. 종료·실패·정합성 검증
정상 종료: 전체 첫 응답 송신·Queue drain → 남은 통지 완료 → BYE → 최종 좌석·통계 → Socket close → CV close/signalAll → Thread join → 로그 close
Client 종료: 5000건 송신·첫 응답 수신 후 BYE까지 연결 유지 / 통지·최종 보유·미해결 수 기록
미해결 Waitlist: 오류 제외, 잔여 건수 기록
송신 완료: OS 버퍼 전달 / 실제 Client 수신·전체 정합성은 로그 대조로 확인
실패: 식별된 Client의 BYE 전 단절·예외·한도 위반·시간 초과 / 원본 로그 보존, 새 runId 재실행
제한: 식별 전 HELLO 10초, 전체 Client 등록 60초, 메시지 송신 30초, 전체 회차 90분
검증 기록: verification.txt + 수집본 Server.txt의 VERIFIER | VERIFY, phase=post-run
원격 실행 기록: 원문 보존 / 검증 기록은 별도 사후 결과로 표시

검증 항목:
- Client별 5000건·전체 150000건, 요청·첫 응답 일치, 누락·중복 없음
- 좌석별 seq 재생, EMPTY 배정 전제, 정당한 취소, FIFO 인계
- MULTI 성공의 전체 배정, 실패의 변경 없음, 오름차순 Lock 획득
- 배정 수−해제 수=최종 점유 수 / 인계: 해제 1+배정 1
- 100석 Server owner와 Client 최종 보유 일치·중복 보유 없음
- WAITLISTED=수신 NOTIFY+미해결 Waitlist
- 인기 좌석 인자 비율, 동일 run/profile/JAR, 원격 Linux·정식 설정

지표 기준:
처리량: Server 첫 유효 Client의 TCP 수락~마지막 첫 응답 송신, 첫 응답 수/초
평균 응답: Client 송신 직전~첫 응답 수신 / 노드 간 시각 차감 없음
최대 Queue: Queue Lock 안의 정확한 최대값 / 5초 샘플 최대값과 구분
이중예약: 전체 좌석 전이·최종 보유 대조
Deadlock: 요청 대기·처리 중 30초간 완료 진전 없는 횟수 / 정상 대기 제외
Deadlock 예방: Lock Ordering / 시간 제한은 정체 진단·실패 처리
Waitlist 평균: Server 등록~NOTIFY 송신 완료 / 미해결 제외
Lock 경합: 좌석 tryLock 실패 횟수 / 최종 정합성: 전체 로그 대조 PASS/FAIL

8-1. 실측 결과
실측 버전: 로그 수집 개선 이전 JAR / 아래 값은 현재 새 버전의 정식 부하 결과가 아님
정식 run: final-20261009-163739-33d47a7f
일시: 2026-10-09 16:37:40~17:28:12 KST
구성: 로컬 Windows Client 30개 → Amazon Linux EC2 Server / 30x5000건, 200~1000ms
종료: Client·Server 코드 0 / 동일 회차 로그 31개 대조 PASS
JAR SHA-256: 4fbcb3306e3c03b000b913d172504694ff278a102fb27eac4b7fe56dbf55d192

지표                    | 실측값         | 측정·확인 위치
------------------------|----------------|-----------------------------
전체 처리량             | 49.483732건/s  | Server, 경과 3031.299285818s
평균 응답시간           | 207.409053ms   | 30개 Client 첫 응답 시간
Request Queue 최대 길이 | 4건            | Queue Lock 내부 최대값
이중예약                | 0건            | 좌석 전이·최종 보유 대조
Deadlock/진행 정체       | 0회            | Monitor·완료 로그
Waitlist 평균 대기시간  | 68.481945s     | 완료 NOTIFY 11312건
좌석 Lock 경합          | 24회           | tryLock 실패 합계
최종 좌석 정합성        | PASS           | Server·Client 로그 대조

첫 응답: 150000=SUCCESS 36489+FAIL 101909+WAITLISTED 11602 / Client별 송신·응답 각 5000
좌석 수지: 배정 24077−해제 24051=점유 26 / 100석 owner·보유 일치
대기 수지: WAITLISTED 11602=NOTIFY 11312+미해결 290 / 인기 좌석 인자 76.0597%
제출 검증 파일: verification.txt / 압축 해제한 프로젝트 폴더 루트
기존 정식 검증: 당시 소스·정식 JAR 일치, 전체 로그 대조 PASS
현재 배포: 로컬·EC2 JAR 일치, 원격 13 Thread 대기 / 로그 요청의 NOT_FOUND 응답 확인
현재 JAR SHA-256: 3134303b5d06df0a8241a29237c5d9ff1ee5669cf4503032bd01e9586ad93358
현재 검증 범위: 실행 환경·소스·스크립트 점검 / 원격 파일 전체 수집·정식 부하 미수행
개발·실패 이력: 별도 logs 보존 / 제출용 정식 회차에서 제외

9. 제출 파일 (제출 시 삭제)
제출 파일: G1HW2.zip / 조별 1명 / 마감 2026-10-12 23:59 KST
내용: 전체 src, project.ps1, seat-booking.jar, Readme.txt, AllDefinedLogs.txt, download.txt, verification.txt, Server.txt, Client1~30.txt
로그: 단일 원격 final 회차 전체 31개 / ZIP 루트 배치
제외: tests·secrets·known_hosts·temp·out·개발/실패 로그
영상: G1HW2.mp4, 5분 이내 / download.txt의 다운로드 가능한 HTTPS 링크
영상 확인: 공유 권한·다운로드·재생
압축:
  pwsh -NoProfile -File project.ps1 -Action Package -RunId RUN_ID
Package: 정식 검증·JAR 해시·문서·링크 확인 → 허용 파일 압축 → 새 폴더 해제 → 빌드·실행 환경 확인
미완료 항목 존재 시 최종 제출 ZIP 생성 제한
