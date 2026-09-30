# 가속 해제 후 비용과 독립 작업의 FD 계통

2026-09-30의 로컬 작업 후보를 개선했다. 첫 clone/fork/vfork 이후 임시 FD 가속이 안전하게 해제된 상태에서도 기존 DSO는 매번 signal mask와 PID 조회를 수행했다. tracer는 한 작업의 자식 생성 때 이미 별도로 exec한 다른 작업까지 가속을 해제했다. 수정은 해제된 경로의 시도 비용을 제거하고, exec로 독립 FD 테이블이 확인된 작업의 가속을 유지한다. 제품 기본 배포판은 교체하지 않는다.

## 구현과 안전 조건

`faststat.c`는 thread별로 명시적 pin 거절 또는 identity page의 해제를 기억한다. 이 상태에서는 stat/lstat/statx, open 및 unlink의 가속 시도를 일찍 종료한다. transient syscall 실패를 영구 거절로 취급하지 않으며 가속 성공 자격을 캐시하지 않는다. fork로 상속한 해제 상태도 가속을 허용하지 않고 다음 exec가 새 TLS 상태를 만든다. 가속이 가능한 경로에서는 기존 signal mask와 PID 검증을 유지한다.

`fastfd.c`와 `proot-fd-scope.patch`는 exec로 독립된 작업을 식별하고 fork 자손은 보수적으로 같은 FD 계통에 둔다. clone/fork/vfork 진입 전에 해당 계통의 가속을 모두 해제한다. `CLONE_FILES`가 다른 PID 사이에서 FD 테이블을 공유할 수 있으므로 PID/TGID만으로 그룹을 나누지 않는다. 계통이 불명확한 태스크는 넓게 해제하고 자식은 자신의 exec 전까지 가속 불가 상태를 유지한다. 같은 작업이 직접 thread를 생성하는 경우의 가속은 복원하지 않았다. user FD를 추정해 닫거나 Android 실행 정책·상속된 filter를 바꾸지 않는다.

## 관찰 결과

[검증 기록](results/fallback-scope-2026-09-30.json)은 실제 guest PASS assertion, 종료 상태와 로그 digest를 포함한다. 경로 가속의 해제 검사는 테스트 전용 DSO의 dispatcher syscall counter로 확인했다. 프로덕션 DSO에는 이 counter가 없다.

| 검사 | 결과와 범위 |
| --- | --- |
| 해제된 경로의 반복 처리 | shared 모드의 20회 묶음에서 새 thread의 signal mask **240→0회**, PID 조회 **120→0회**. context 모드는 **200→0회**, **100→0회**. 기존 mapping·fork child에서도 수정판은 두 counter가 0이고 내용·identity·ENOENT/EFAULT가 유지된다. 호스트 네 모드와 Android 네 모드 PASS. |
| host exec/fork/signal/FD | [102/102 PASS](../regression/results/host-fd-scope-2026-09-30.json): native/emulated·plain/tracer/preload·static/dynamic 조합과 독립 exec reader의 가속 유지. 시간 채택 자료가 아니다. |
| Android 정확성 | 기존 core·private clone·unshare·restart 56개와 해제 dispatcher 4개·독립 계통 2개, **62/62 PASS**. 일반 앱 UID, Ubuntu 24.04, native/emulated. |
| 독립 작업 동시성 진단 | Debian 13 emulated에서 reader가 stat/lstat 10,000회 수행하고 별도 exec 작업은 fork/CLONE_FILES clone을 101회 수행한다. 기존/수정판 3쌍을 교대했고 FD timestamp 직행은 양쪽 모두 끈 상태다. reader 구간 중앙값 **262.29→93.53ms**, 약 **64.3% 단축**, 가속 성공 **0→10,000회**. 전체 workflow·Git·설치·에이전트 완료 시간 개선율이 아니다. |
| 첫 dispatcher 수정의 Git | [1만 파일·2쌍](results/fallback-git-2026-09-30.json): default status 중앙값 **288.34→294.00ms**, diff **265.37→265.05ms**. system CPU 중앙값은 **58.50→39.22ms**, **54.98→28.73ms**지만 시간 이득은 뚜렷하지 않았다. stdout hash가 같고 사용자 Git 설정은 바꾸지 않았다. 이 표본은 최종 FD 계통 수정 전 엔진으로 측정했다. |
| build | JDK 17 canonical Gradle 세 작업 PASS·139 tasks, 고정 로컬 payload와 최종 debug APK의 SHA 검증 PASS. |

기존 후보의 Git 실험을 새 FD 계통 후보의 실제 Git 시간 증거로 합치지 않는다. 세 쌍의 작은 동시성 진단은 제품 채택 기준인 실제 작업 10쌍을 충족하지 않는다. stock enforcing 및 물리 16 KiB 기기, 실제 인증된 Codex/Claude 작업은 검증하지 않았다. UI나 세션 소유권을 변경하지 않았으므로 replicant UI 검사는 수행하지 않았으며 Android MCP로 앱 UID의 실행 상태와 assertion을 확인했다.

## 재현

고정 프로덕션 PRoot·loader·DSO와 APK 준비 방법은 [로컬 payload 문서](../WORKLOAD_PAYLOAD.md)에 있다. 회귀 runner는 [exec/fork 하네스](../regression/README.md)에 명시한 scratch root를 사용한다. probe 빌드는 기존 ARM64 glibc 호스트의 Clang을 사용하며 다운로드하지 않는다.

```sh
PROOT_BUILD_TEST_PROBES=1 bash tools/rootless/faststat/build.sh /tmp/rootless-fallback-probe
python3 tools/rootless/regression/prepare.py /tmp/rootless-fallback-root \
  --preload /tmp/rootless-fallback-probe/libproot-faststat-probe.so
cp /tmp/rootless-fallback-probe/fallback-cost-test /tmp/rootless-fallback-root/bin/
python3 tools/rootless/faststat/fallback-cost-test.py \
  --proot /absolute/path/to/host-compatible/proot \
  --loader /absolute/path/to/host-compatible/loader \
  --root /tmp/rootless-fallback-root --output /tmp/rootless-fallback-results
```

출력 디렉터리는 새 disposable 경로여야 한다. timeout에는 실행기가 생성한 프로세스 그룹만 회수한다. 기존 DSO의 probe 빌드는 새 zero-helper assertion에서 실패하는 negative control이며, 실패를 correctness regression으로 혼동하지 않는다. 초기 sandbox의 ptrace 거부는 허용된 호스트 실행으로 확인했다. 앱 내부 raw 로그의 공용 임시 폴더 복사는 자동 승인 검토가 거부했으며, 복사 대신 지정된 synthetic assertion·counter·JSON·SHA만 직접 읽어 기록했다.

다음 성능 과제는 같은 Git 작업의 thread가 사용하는 경로 조회를 안전하게 가속하는 것이다. 이번 수정은 이미 독립적으로 exec한 다른 작업을 보호하며 같은 FD 테이블의 thread에 임시 pin을 허용하지 않는다.
