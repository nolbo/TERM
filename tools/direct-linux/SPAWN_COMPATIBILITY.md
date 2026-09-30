# Direct Linux: 실제 spawn, 실행 인자, timer와 파일 노드

2026-09-30. C04/C07/C18/C20의 기능을 함께 확장했다. [전체 45개 목표](COMPATIBILITY_PROGRESS.md)는 계속 진행 중이다. 기존 Rootless의 rootfs·package DB·제품 backend는 변경하지 않았다.

## 실제 vfork와 posix_spawn

이전 COW fork+pipe 경로를 실제 kernel `CLONE_VM|CLONE_VFORK`로 교체했다. 같은 guest stack을 사용하는 glibc vfork와 별도 stack을 사용하는 glibc posix_spawn 모두 자식의 메모리 쓰기가 부모에게 보인다. kernel이 호출한 부모 thread만 기다리게 하며 다른 부모 thread는 FIFO peer 등의 작업을 계속할 수 있다. child exec는 APK의 동일 freestanding runtime을 kernel 재실행한다. guest app-data ELF를 kernel exec하지 않으며 기존 seccomp 필터를 유지한다.

- FD table을 참조 수와 잠금을 가진 객체로 분리했다. pthread의 CLONE_FILES는 공유하고 private-table clone, vfork, close_range UNSHARE는 독립 identity를 사용한다. 복사 직전의 internal FD pin 목록을 고정하고 자식의 private table에서만 닫는다. vfork 자식은 목록 mapping을 해제하지 않으며 부모만 kernel 완료 뒤 해제한다.
- thread/files/sighand 잠금의 소유 TID를 기록한다. vfork 자식 bootstrap이 부모가 잡은 잠금을 풀고, 그 중간에 SIGKILL을 받으면 부모가 남은 자기 소유 잠금만 CAS로 해제한다. 이미 다른 부모 thread가 획득한 잠금을 풀지 않는다.
- 부모가 자식별 16 MiB exec 준비 arena를 예약한다. 자식은 그 안의 extent만 사용하며 다음 exec 시도에서 rewind한다. SIGKILL이 bump pointer 변경 도중 발생해도 부모는 목록을 따라가지 않고 전체 arena를 회수한다. guest clear-child-TID와 wait status는 runtime 회수용으로 대체하거나 소비하지 않는다.
- vfork record는 일반 proc 기반 reaper에서 제외한다. creator의 kernel vfork 반환이 이전 VM 이탈의 근거이며, 이때 signal stack·arena·private table을 회수한다. 일반 CLONE_VM의 관찰 불가능한 이탈 문제를 이 경로의 완료 근거로 사용하지 않는다.
- posix_spawn의 파일 동작, cwd, pgroup, signal mask/default, reset identity와 scheduler 속성에 필요한 syscall을 연결했다. UID 변경과 scheduling 권한은 실제 Android kernel이 검사한다.

`spawn-test.c`는 각 정적·동적 glibc 실행에서 505회 자식 생성으로 공유 메모리, private FD/cwd/disposition, 부모 signal 지연, 다른 thread의 FIFO peer, 준비 중 SIGKILL 64회, 속성 적용, 병행 PATH spawn 160회와 NOFILE=16에서 실패·회수 256회를 확인한다. Python의 os.posix_spawn, Popen(close_fds=False), 기본 Popen도 별도로 실행한다.

일반 CLONE_VM, clone3, nested creation과 vfork 자식의 임의 mmap/munmap/madvise·brk 확대는 미지원이다. vfork 자식은 exec/_exit와 spawn 준비 계약으로 제한한다. 이는 전체 raw clone 조합의 완료가 아니다. glibc가 clone3의 ENOSYS 뒤 사용하는 실제 legacy clone 경로를 검증했으며 COW fallback으로 성공을 대신하지 않는다.

## Linux 실행 인자 한도

argv 128개/env 256개의 고정 배열을 제거했다. 현재 RLIMIT_STACK에서 `max(128 KiB, min(RLIMIT_STACK / 4, 6 MiB))`를 계산하고 pointer 비용과 실행 파일 이름을 포함한 문자열 비용을 함께 검사한다. 각 문자열은 NUL을 포함하여 32 host page 이내이며 NULL argv는 Linux처럼 빈 argv[0] 하나로 보정한다. shebang은 기존 argv[0] 공간을 회수한다. 잘못된 pointer와 보호 page는 준비 단계에서 EFAULT로 처리하여 기존 이미지를 보존한다.

kernel 재진입 handoff도 가변 크기이며 pointer offset을 256개씩 기록한다. 최대 pointer table을 한 번 더 만들지 않는다. 보수적으로 계산한 16 MiB arena 최대 사용량은 4 KiB page에서 14,794,752 bytes, 16 KiB에서 14,925,824 bytes, 64 KiB에서 15,400,960 bytes다. 16/64 KiB 계산은 해당 기기의 실행 검증을 대체하지 않는다.

`arguments-test.c`는 native Linux와 Direct 양쪽의 정적·동적 glibc에서 같은 104개 exec 조건을 검사한다. argv/env 각각 8192개, 최대 약 699,047개 빈 인자, 정확한 바이트·pointer 비용 경계, 문자열 한도, 256 KiB/1 MiB/8 MiB/32 MiB stack limit, NULL/EFAULT, 보호 page, 정렬되지 않은 table을 fork/vfork/posix_spawn으로 확인한다. 별도 metadata 검사는 shebang argv[0] 공간 회수를 확인한다.

## Timer, queued signal과 FD 이벤트

get/setitimer, POSIX timer_create/gettime/getoverrun/settime/delete, timerfd_create/settime/gettime, signalfd4, rt_sigqueueinfo/rt_tgsigqueueinfo를 연결했다. POSIX timer ID는 VM·signal disposition이 아닌 process 객체가 소유한다. pthread는 같은 객체를 공유하고 vfork는 빈 객체를 받는다. COW fork는 복사된 metadata를 버리며 부모의 timer ID를 삭제하지 않는다. 일반 task 회수도 foreign TGID의 ID에 timer_delete를 호출하지 않는다.

단일 thread의 in-process exec는 자신의 POSIX timer를 삭제한다. kernel 재실행은 kernel의 삭제를 사용한다. interval timer 및 non-CLOEXEC timer/signalfd의 상속은 kernel 계약을 보존한다. 생성 결과 pointer가 EFAULT이면 생성한 kernel timer와 metadata를 되돌린다.

`timers-test.c`는 만료·overrun, realtime queue의 순서/값/신원, thread 대상 신호, SA_SIGINFO와 논리 mask, signalfd/epoll, timerfd dup/fork, CPU clock, 생성 thread 종료, glibc SIGEV_THREAD, 단일·다중 thread exec를 검사한다. 예약된 SIGSYS를 생성하는 timer·queue 또는 signalfd mask와 vfork 자식의 POSIX timer API는 EOPNOTSUPP다. C16과 일반 shared-VM 확대가 남아 있으므로 C18을 무조건 전체 완료 처리하지 않는다.

## 실제 hardlink, FIFO와 node 정책

linkat은 root 안의 inode를 pin하여 실제 kernel hardlink를 만든다. inode/nlink, 쓰기·chmod 공유, rename/unlink, symlink 자체의 hardlink, dirfd와 FD 별칭 및 재시작 후 내구성을 유지한다. mknodat은 일반 파일/FIFO/socket inode 생성을 제공한다. FIFO의 blocking peer, ENXIO/EAGAIN/EOF, EINTR/SA_RESTART는 실제 kernel 동작이다. 앱이 먼저 FIFO fixture를 만들어 주는 검사와 구분하여 guest가 직접 mkfifo한다.

고정 /dev 원본을 guest 안에 hardlink하는 요청은 EXDEV, 고정 device 목적지 변경은 EROFS다. character/block node 생성과 AT_EMPTY_PATH는 host 권한·정책 오류를 보존한다. 성공한 것처럼 fake device를 만들지 않는다. Android enforcing 정책에서 hardlink가 거부될 수 있으며 metadata 기반 대안과 제품 package 계약은 여전히 남아 있다.

`node-test.c`는 native/Direct 정적·동적 검사에 더해 별도 loader 실행 사이의 hardlink·symlink·FIFO 영속성을 검사한다. 반복 EEXIST/EFAULT 이후 FD 회수도 확인한다.

## 검증과 재현

결과와 hash는 [통합 실행 기록](results/spawn-compatibility-runtime-2026-09-30.json)에 저장한다. 이는 API 35 ARM64 redroid, 4 KiB, SELinux Disabled의 일반 앱 UID 결과다. stock enforcing과 다른 페이지 크기의 통과 증거는 아니다.

- Native build 및 import/TLS/ELF 감사, 호스트 통합 Direct 20개 suite, 새 기능의 native/Direct 정적·동적 대조를 통과했다. scratch 192회, sighand 192회, task lifecycle 256회와 vfork 잠금 경계 SIGKILL 64회·FD snapshot SIGKILL 32회, signal reset/exec metadata/FD 회귀도 통과했다.
- JDK 17 canonical `:android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test` 및 `:android-runtime:assembleDebugAndroidTest`가 통과했다. runtime 186 pass/1 optional skip, image 4 pass다.
- Android 전체 검사는 **32.967초, 3 tests 통과**, 139회 상위 실행, 741개 fixture hash, kernel 재진입 200회다. 의도한 거부와 기존 PRoot reference 제약은 결과에 별도로 남으며 지원 성공으로 세지 않는다.
- loader SHA-256은 `696d2296d416d21f3eed67f382f655f093c0e7262b38987e8792557aa678b30a`, component는 `80b67c2c926d779fdf2c2e779e2f69c44eed0b96866aa221871edde883d8869f`다.

첫 Android spawn/node 검사는 ProcessBuilder가 SIGUSR1 차단 mask를 상속해 fixture의 신호 전제가 틀려 실패했다. 같은 mask로 native 실패를 재현한 뒤 fixture에서 필요한 초기 mask를 설정했다. node는 검사 후 원래 mask를 복원하며 timer의 exec helper는 상속 검증을 위해 그대로 유지한다. 차단 mask를 상속시킨 native/Direct 정적·동적 검사와 최종 Android에서 통과했다. 기존 sighand 구성요소 adapter의 새 process/FD 의존성 링크 실패도 수정하고 192회를 재검증했다. host runner의 private-clone 대상 fixture 오지정은 수정했고 sandbox의 process_vm 제약은 승인된 동일 바이너리 실행으로 확인했다. 실패를 runtime 통과로 세지 않았다.

기존 [APK 준비 절차](ELF.md#reproduction)에 `fixtures.py --applications`로 만든 새 fixture를 사용한다. component probe에는 `tasks-host-test.c`를 scratch.c/syscall.S/threads.S와 `-pthread`로 빌드한다. 전체 instrumentation 옵션은 다음과 같다.

```sh
am instrument -w \
  -e class dev.terminal.runtime.directlinux.DirectLinuxTaskOwnershipTest,dev.terminal.runtime.directlinux.DirectLinuxLoaderTest,dev.terminal.runtime.directlinux.DirectLinuxExecTransitionTest \
  -e directLinuxTaskOwnership true -e directLinuxTaskOwnershipSha256 COMPONENT_SHA256 \
  -e directLinuxLoader true -e directLinuxLoaderSha256 LOADER_SHA256 \
  -e directLinuxReexec true -e directLinuxSpawn true -e directLinuxArguments true \
  -e directLinuxNodes true -e directLinuxTimers true \
  -e directLinuxThreads true -e directLinuxFork true -e directLinuxExecFd true \
  -e directLinuxSignalMasks true -e directLinuxNetwork true -e directLinuxPty true \
  dev.terminal.runtime.test/androidx.test.runner.AndroidJUnitRunner
```

성능 비교 APK의 probe slot에는 직전 commit 1619589 loader를 넣고 directLinuxBaselineSha256을 지정한다. component와 baseline은 같은 slot을 사용하므로 APK를 구분한다. 같은 APK에서 baseline/새 Direct/최신 가속 PRoot 순서를 교대하는 18회·54표본의 shell external command 30회 workload이며, 전체 앱 성능으로 일반화하지 않는다.

| Engine | 중앙값 | p95 (nearest rank) |
| --- | ---: | ---: |
| 이전 Direct 1619589 | 37.254 ms | 42.774 ms |
| 새 Direct | 42.669 ms | 43.865 ms |
| 가속 PRoot | 48.760 ms | 50.564 ms |

이 측정에서 새 Direct는 이전 Direct보다 **14.5% 느리며**, 가속 PRoot보다 12.5% 빠르다. 실제 shared-VM vfork와 kernel 재실행을 사용하는 변경에서 관찰된 회귀이며 원인별 비용을 아직 분해하지 않았다. 성능만을 위해 COW vfork 의미로 되돌리지 않는다. 전체 workload 성능 목표가 달성되었다는 뜻도 아니다.

설계 근거는 [Linux clone/vfork](https://man7.org/linux/man-pages/man2/clone.2.html), [glibc 2.39 spawn](https://github.com/bminor/glibc/blob/glibc-2.39/sysdeps/unix/sysv/linux/spawni.c), [Linux v6.17 exec](https://github.com/torvalds/linux/blob/v6.17/fs/exec.c)와 [shebang](https://github.com/torvalds/linux/blob/v6.17/fs/binfmt_script.c), [POSIX timers](https://man7.org/linux/man-pages/man2/timer_create.2.html), [linkat](https://man7.org/linux/man-pages/man2/link.2.html)을 대조했다. Context7에 해당 세부 kernel 계약이 없어 upstream 원문으로 보완했다. Android MCP는 설치·실행·정책·결과를 제공했다. UI/외부 앱 변경이 없어 Replicant/codex_apps는 사용하지 않았다.
