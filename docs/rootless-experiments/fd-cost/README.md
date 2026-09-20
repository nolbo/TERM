# FD 호출 비용 분해 — 2026-09-20

기준은 dev `bc39fb4`이다. 시험용 DSO에만 `instrument.patch`를 적용해
`faststat.c`의 명시적 `syscall`과 `fast_syscall`을 계수했다. 생산 소스·앱·런타임
입력 해시는 변경하지 않았다. `profile.py`는 준비/워밍업 후 각 동작 직전에
계수기를 초기화하고 동작 직후 읽는다. 파일 내용과 크기도 검증한다.

Android MCP로 API 35 ARM64 redroid의 앱 UID에서 실행했다. 최신 엔진과 계측
DSO를 두 형식에 공통 사용하고 둘 다 openat2를 거부했다. 이전 하드링크 릴리즈와의
속도 비교가 아니라, **같은 엔진에서 저장 형식만 달리한 호출 수 비교**다.
각 형식은 새 게스트 프로세스에서 한 번 실행했으며 single_safe는 모두 1이었다.

| Python 동작 | 에뮬레이션 DSO 호출 수 | 하드링크 DSO 호출 수 | 차이 |
| --- | ---: | ---: | --- |
| open(path, 'rb') | 13 | 12 | readlinkat 1회 |
| read() | 4 | 3 | readlinkat 1회 |
| close() | 5 | 5 | 없음 |
| 합계 | 22 | 20 | readlinkat 2회 |

libc 내부의 read/ioctl/lseek, stdio DSO 및 tracer 내부 호출은 계수 범위 밖이다.
따라서 전체 커널 진입 수로 해석하면 안 된다. 계수기의 atomic 연산도 추가되므로
시간을 측정하지 않았다. 원본 관측 수치는 [results.json](results.json)에 기록했다.

일반 os.open은 두 형식 모두 9회이며, os.fstat만 4회/3회로 차이가 났다.
Python은 열기와 read()에서 각각 fstat를 사용하므로 파일 하나를 읽을 때도 링크
분류의 추가 비용이 두 번 발생한다. 이 증거는 다음 최적화 대상을 fstat의 링크
분류로 좁힌다. 단순 FD 캐시는 열린 파일의 링크 생성, rename, FD 재사용과 다른
세션의 수정을 놓칠 수 있어 아직 채택하지 않는다. 호출 수만으로 기존 속도 차이
전체가 설명되거나 목표 성능을 달성했다고 주장하지 않는다.

재현 시 임시 소스 복사본에 패치를 적용하고 기존 ARM64 clang으로 combined DSO를
빌드한다. 기존 faststat 빌드 옵션과 include 경로를 유지하고 stdio-buffer.c도
연결한다. 진단용 내보내기 심볼은 proot_cost_count/proot_cost_reset이다.
별도 dev.terminal.prootperftest APK의 DSO만 교체하고 기존 시험 runner의 restricted,
native 모드에 profile.py를 표준 입력으로 전달한다. 사용자 앱을 재설치하지 않는다.

계측 매크로의 첫 빌드는 인자 없는 syscall 처리 오류로 실패했고 GNU 가변 인자
콤마 제거를 적용한 뒤 -Wall -Wextra -Werror 빌드가 통과했다. 시험 APK는 incremental
설치 거부 후 streamed 설치로 성공했다. 두 모드의 내용/크기 검사는 통과했다.
생산 변경이 없어 Gradle 빌드는 반복하지 않았다. 삼성 Android 17 검사는 미실행이다.
