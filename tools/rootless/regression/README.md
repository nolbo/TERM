# Rootless exec·fork 회귀 검증

2026-09-30 최종 검증은 [호스트 100개 실행](results/host-restart-2026-09-30.json)과 [Android 56개 실행](results/android-correctness-2026-09-30.json)에 기록했다. Android 기록은 일반 앱 UID의 static/dynamic·native/emulated·identity/link·exec/fork/signal/FD assertion이며 UI·foreground service 검사나 실제 enforcing·16 KiB 기기 지원을 대신하지 않는다.

Direct Linux 실험에서 확인한 Linux ABI 검증 중 PRoot에도 필요한 exec·fork 검사를 이 디렉터리로 이관했다. 원래 실험의 예외 통과 경로는 포함하지 않는다. `concurrency-test.c`는 리더·일반 worker·리더 종료 후 worker의 exec에서 PID/TID, sibling 종료, 실패한 exec의 비파괴성, FD/CLOEXEC, cwd, signal mask와 disposition, TLS 및 mapping을 확인한다. `fork-test.c`는 다른 스레드가 FIFO와 stat 작업을 하는 동안 worker에서 30회 fork하여 child의 FD 용량, 스레드 생성, exec 및 부모 worker 생존을 확인한다.

`pin-fork-test.c`는 단일 태스크 stat가 실제로 가속되는지 확인한 뒤 세 스레드를 생성한다. libc fork와 raw clone을 번갈아 100회 수행하며 자식의 FD 집합이 정확히 유지되는지, stat/statx/fstat 결과가 맞는지, 사용자 소유 O_PATH FD가 보존되는지 확인한다. 첫 clone 후 path 가속이 중단되고 exec 뒤 다시 가속되는지도 검사한다. shared-path 모드와 요청별 context 모드 모두 실행한다.

기존 ARM64 glibc 호스트의 Clang과 라이브러리만 사용하며 다운로드하지 않는다. `prepare.py`는 존재하지 않는 출력 디렉터리에만 fixture를 만들며, 루트 파일시스템은 실제 사용자 Debian과 분리한다.

```sh
bash tools/rootless/faststat/build.sh /tmp/rootless-guest
python3 tools/rootless/regression/prepare.py /tmp/rootless-regression-root \
  --preload /tmp/rootless-guest/libproot-faststat.so
python3 tools/rootless/regression/run.py \
  --proot /absolute/path/to/host-compatible/proot \
  --loader /absolute/path/to/host-compatible/loader \
  --root /tmp/rootless-regression-root \
  --output /tmp/rootless-regression-results --repeat 3
```

호스트 runner에는 호스트에서 실행 가능한 수정 PRoot와 loader를 제공한다. Android용 payload는 앱의 nativeLibraryDir와 앱 UID에서 별도로 검증한다. 이 호스트 검사는 Android의 ptrace·seccomp 정책, APK 실행 정책, foreground service 또는 UI 검증을 대체하지 않는다.

runner는 기본·tracer 가속·guest preload의 세 설정과 native/emulated hardlink 두 모드에서 정적/동적 바이너리를 실행하고, 각 guest의 전체 PASS assertion과 종료 상태를 함께 확인한다. PRoot launcher의 0 종료만으로 성공 처리하지 않는다. timeout에는 자신이 생성한 프로세스 그룹만 종료하며, 로그와 `results.json`은 지정한 새 출력 디렉터리에 남긴다. 기본 1회는 100개 실행이며 `--repeat 3`은 300개 실행이다. `--mode native` 또는 `--mode emulated`로 한 모드의 50개 실행만 선택할 수 있다.

fork 검사는 worker fork 외에 leader 종료 후 fork, 160회 private-FD-table clone, `close_range(UNSHARE)` signal handler로 중단된 FIFO open 재시작도 포함한다. `restart-test.c`는 guest signal context의 원래 경로·syscall 인자와 `SA_RESTART`/`EINTR`, 무시된 signal, `SIGTRAP`, 상대 경로, `openat2`, 별도 signal stack, 중첩 handler, 앞선 open과 무관한 read 재시작을 검사한다. handler 안에서 추가 파일 syscall과 stack 쓰기를 수행하여 snapshot을 덮은 뒤에도 원래 대기가 정상적으로 이어지는지 확인한다.

`proot-exec-thread.patch`는 PTRACE_EVENT_EXEC의 실제 이전 TID로 실행 태스크를 찾고, 커널이 leader PID로 바꾼 뒤에도 그 태스크의 register·syscall·ELF load 상태를 유지한다. 프로세스의 parent, 최초 tracee 정책과 binding 수명은 새 leader로 이어진다. `proot-pin-fd.patch`와 faststat ABI v9는 exec로 확인한 단독 FD 테이블에서만 임시 FD를 만들고, 첫 clone/fork/vfork 진입 전에 그 허용을 취소한다. 진행 중인 pin에 대해 사용자 FD를 추정하거나 닫는 처리는 없다.

`proot-restart-path.patch`는 syscall exit를 생략한 ARM64 경로 변환에서 signal 전달 직전 원래 guest 인자를 복구한다. [Linux ARM64의 signal 처리](https://github.com/torvalds/linux/blob/v6.12/arch/arm64/kernel/signal.c#L1308-L1373)는 ptrace signal stop 전에 PC를 syscall 명령으로 되감고 x0를 복구한다. 패치는 그 PC·syscall 번호·SP·인자 6개가 직전 변환과 모두 일치하는 경우만 복구하며 PC를 바꾸지 않아 `SA_RESTART`와 `EINTR` 결정은 커널에 남긴다. 일반 open에 syscall exit stop을 추가하지 않는다. 다른 아키텍처나 ARM32 compat 실행까지 해결했다고 주장하지 않는다.
