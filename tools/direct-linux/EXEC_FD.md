# Direct Linux: FD 기반 실행

C06의 `execveat`와 glibc `fexecve`를 구현하고 현재 ARM64 host 및 Android
검증 환경에서 확인했다. pathname exec와 같은 ELF 검증·매핑·실패 복원 경로를
사용한다. guest ELF를 Android 커널에 실행시키지 않으며, 다중 스레드와 worker
exec는 기존 [APK 런타임 재진입](CONCURRENT_EXEC.md)을 사용한다.

## 동작과 경계

- `AT_EMPTY_PATH`는 읽기 FD와 O_PATH FD 모두 지원한다. 열린 inode를 내부 FD로
  다시 열어 읽으므로 rename/unlink 후에도 실행할 수 있고, 원래 FD의 offset과
  CLOEXEC 상태를 바꾸지 않는다. 내부 FD는 기존 table별 I/O pin으로 보호한다.
- 상대 pathname은 지정한 dirfd, 절대 pathname은 guest root를 기준으로 찾는다.
  `AT_SYMLINK_NOFOLLOW`, 잘못된 flags/FD/pointer, 비-directory dirfd, directory
  실행 및 symlink 자체를 가리키는 O_PATH FD의 오류를 검사한다.
- 스크립트의 interpreter 인자와 `AT_EXECFN`에는 `/dev/fd/N` 또는
  `/dev/fd/N/P`를 사용한다. 해당 FD가 CLOEXEC이면 기존 이미지를 교체하기 전에
  ENOENT를 반환한다. 동적 ELF의 PT_INTERP는 이 스크립트 제한과 구분한다.
  이 계약은 [execveat(2)](https://man7.org/linux/man-pages/man2/execveat.2.html)와
  [Linux script loader](https://raw.githubusercontent.com/torvalds/linux/v5.10/fs/binfmt_script.c)를 따른다.
- `files.c`는 위 스크립트 경로와 libc의 과거 fexecve 방식에 필요한
  `/proc/self/fd/N` 별칭을 처리한다. 실제 호출 스레드의 FD table과 live TID를
  사용하며, guest root 안의 inode인지 확인한다. 문자열 pathname으로 다시
  찾아 삭제된 파일을 잃거나 전체 host `/proc`를 노출하지 않는다.
- 별칭의 파일 open/stat/access, directory FD 아래의 lookup, root-relative
  readlink를 제공한다. **전체 proc/devfs 구현은 아니다.** 별칭 디렉터리 열거,
  root 밖의 inode/익명 FD 별칭 및 magic-link 자체의 `O_PATH|O_NOFOLLOW` handle은
  아직 제공하지 않는다. 마지막 경우에는 host proc handle을 반환하지 않고
  EOPNOTSUPP를 반환한다. C23/C24는 계속 미완료다.

기존 ELF 크기·인자 수·읽기 권한 요구·setuid/setgid 제한과 ETXTBSY 공백은
C07–C10에서 계속 추적한다. 같은 UID의 runtime trampoline은 악성 guest를
격리하는 sandbox가 아니다. stock enforcing/16 KiB 정책 검증(C01/C02), 실제
shared-VM spawn/vfork(C04), 제품 backend/session 통합(C42)은 별도 미완료다.

## 검증

[동일 fixture의 host 비교](results/execfd-host-2026-09-25.json),
[Android 전체 실행 및 fixture hash](results/execfd-runtime-2026-09-25.json).
loader SHA-256:
`e226f8aac1219a684146640a375cdf1209d99bd08f476f1553abd9fbcb63c248`.

`execfd-test.c`를 native Linux와 Direct Linux에서 수정 없이 실행했다. 정적·동적
glibc 각각 단일 스레드와 worker exec의 18가지 경로를 검사한다. 일반/빈 경로,
O_PATH/CLOEXEC, 상대 dirfd/cwd, 절대 경로의 dirfd 무시, symlink/no-follow,
unlink한 ELF, libc fexecve, proc 경로 실행, FD/상대 경로/unlink 스크립트와
실제 dash 스크립트를 포함한다. PID/TID, argv와 interpreter 인자, 환경, auxv,
원래 FD의 생존/폐쇄·offset을 새 이미지에서 확인한다. proc 경로 검사는 libc의
과거 fallback과 동일한 경로를 직접 실행한 것이며, libc에 ENOSYS를 주입한 검사는 아니다.

각 linking variant에서 낮은 soft NOFILE=16 상태로 실패 묶음을 100회 반복한다.
묶음당 16개의 exec 오류 이후 기존 signal handler, FD flags/offset과 실제
남은 FD 용량을 확인한다. native/Direct 합계 12개 suite, 성공한 exec 144회,
실패 묶음 400회가 통과했다.

Android API 35 ARM64 redroid의 일반 앱 UID에서도 Direct 6개 suite(성공한 exec
72회, 실패 묶음 200회)를 통과했다. 전체 instrumentation은 **32.556초,
OK (2 tests)**, top-level 실행 180회(비교 표본 54개 포함), fixture hash 730개다.
기존 thread/fork/FD-table/signal/network/PTY 검사 및 kernel 재진입 200회도 통과했다.
SELinux Disabled, page=4096, initial Seccomp=0/CapEff=0/TracerPid=0이며
**stock_policy_verified=false**다. 새 FD exec 묶음의 기준은 native Linux이고,
Rootless에는 이번 묶음을 실행하지 않았다. 기존 Rootless 비교·알려진 제한은
전체 결과에 보존했다.

- Native freestanding import/TLS/interpreter/type/relocation 감사, APK 서명 통과.
- Host ELF metadata/argument copy, FD 고갈, fork 매핑 소유권 회귀 통과.
- JDK 17 canonical Gradle 및 instrumentation APK 빌드 통과:
  runtime 180 pass/1 optional skip, image 4 pass.
- 제품 APK에 Direct Linux payload가 들어가지 않았음을 검사했다.
- syscall inventory는 명시적 EOPNOTSUPP 분기를 소스에서 찾도록 변경했다.
  원래 감사 커밋의 318개 목록 재생성은 기존 파일과 정확히 일치한다. 현재 소스의
  execveat는 handled이며, 이 숫자는 전체 호환률을 뜻하지 않는다.

같은 APK에서 shell+30 외부 명령 중앙값은 이전 `f20fd01` 37.969ms,
현재 36.839ms, Rootless 49.380ms였다. 이는 그 이전 버전과의 회귀 비교이며
이번 FD 실행 기능 단독의 성능 향상으로 해석하지 않는다.

첫 native 기준 실행은 Codex host sandbox 안에서 unlink한 ELF의 exec에 ENOENT를
반환했다. 동일 바이너리를 승인된 sandbox 밖에서 다시 실행하자 성공했다.
이를 Direct 결함이나 Android 정책 검증으로 집계하지 않았다. Android MCP의
설치·실행·결과/정책 조회는 모두 성공했다. 새 Linux ABI 근거는 위 man-pages와
upstream kernel 소스로 확인했다. UI나 외부 앱 작업이 없어 Replicant/codex_apps는
사용하지 않았다.

## 재현

`build.sh`와 `fixtures.py --applications`, [ELF packaging](ELF.md#reproduction)을
사용한다. fixture root에는 writable `/tmp`가 있어야 한다.

```sh
for kind in static dynamic; do
  for scenario in single worker errors; do
    LOADER ROOT_DIRECTORY /bin/execfd-$kind "$scenario"
  done
done
```

동일한 정적·동적 fixture를 native host에서 절대 경로로 직접 실행하면 기준
동작을 검사한다. Android는 [병행 exec 재현 명령](CONCURRENT_EXEC.md#재현)에
`-e directLinuxExecFd true -e directLinuxFork true`를 추가한다.
