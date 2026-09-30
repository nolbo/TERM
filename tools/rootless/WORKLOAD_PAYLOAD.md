# 로컬 Rootless 작업 개선판 빌드

`rootlessWorkloadPayload=true`는 이번에 검증한 로컬 PRoot·합본 DSO를 선택하는 별도 APK 빌드 옵션이다. `rootlessAcceleratedPayload`와 `rootlessStdioBuffer=true`가 모두 필요하다. 기본 payload와 기존 배포판의 허용 해시는 유지하며, 옵션에 맞지 않는 엔진이나 DSO를 넣으면 빌드가 실패한다. 준비 도구는 다운로드·릴리즈·배포를 수행하지 않는다.

이 엔진에는 FD 기반 timestamp syscall의 불필요한 추적 제거와 함께 worker exec 상태 유지, fork의 임시 FD 누수 방지, emulated hardlink의 소유권 및 실패한 링크 변경 처리, ARM64 신호 후 syscall 재시작 수정이 포함된다. 다중 스레드에서는 임시 FD가 필요한 guest 경로 가속을 중단하고 기존 tracer 처리로 돌아간다. 새 옵션은 tracer 환경에만 `PROOT_FD_UTIMENS_DIRECT=1`을 추가한다. Native·AVF의 선택과 실행 경로에는 영향을 주지 않는다.

이미 소스에서 빌드한 세 파일을 검증·준비한다. 출력은 새 디렉터리여야 하며 원래 입력은 변경하지 않는다. DSO가 별도 위치에 있다면 `--preload /absolute/path/libproot-faststat.so`를 지정한다.

```sh
python3 tools/rootless/prepare-workload-runtime.py \
  --engine-directory /absolute/path/to/local-build \
  --output /tmp/rootless-workload-payload
```

기존 ARM64 glibc 호스트, Clang 및 저장소의 Android NDK 설정에서 다시 빌드하려면 완전한 로컬 입력 캐시를 제공한다. `--build`는 PRoot 소스 zip과 talloc·android-shmem의 네 패키지를 고정 해시로 확인한 뒤 임시 캐시에 복사한다. 누락·변조된 입력은 빌드 전에 거절한다. 이후 기존 빌더를 `PROOT_BUILD_FASTSTAT=1`, `PROOT_BUILD_RENAME_LOCAL=1`, `PROOT_BUILD_STDIO=1`로 실행하고 결과도 고정 해시와 ELF/16 KiB LOAD 정렬로 검증한다. 새 도구나 패키지를 설치하지 않는다.

```sh
python3 tools/rootless/prepare-workload-runtime.py --build \
  --input-cache /absolute/path/to/existing/pinned-inputs \
  --output /tmp/rootless-workload-rebuilt
```

두 경로에서 허용하는 입력은 같다. 소스나 툴체인 변경으로 다른 바이트가 생성되면 실패하며, 해시를 자동 갱신하지 않는다.

| 파일 | SHA-256 |
| --- | --- |
| `proot` | `85dc5a9a527dba926ab496e5e7929ba8ded4e6dbd7da5f77a072ee0af255af42` |
| `proot-loader` | `2567ab8ad3b418a8804696342056052eef37835e50d200e9f4ff48aae564c237` |
| `libproot-faststat.so` | `619bc9a2c3270fb89f787b5e20003adbe484a1f33843cd8e382584eb6fa2a406` |

앱 빌드와 APK 바이트 검증은 다음과 같다.

```sh
./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test \
  -ProotlessAcceleratedPayload=/tmp/rootless-workload-payload \
  -ProotlessStdioBuffer=true -ProotlessWorkloadPayload=true --console=plain
python3 tools/rootless/prepare-workload-runtime.py \
  --verify-apk android-app/build/outputs/apk/debug/android-app-debug.apk
```

기존 배포판의 다운로드 준비 도구인 `prepare-release-runtime.py`와 기본 빌드 선택은 그대로다. 이 로컬 옵션을 추가한 것만으로 기존 배포판이 교체되거나 앱이 설치·배포되지는 않는다.

[실제 작업 결과](../../docs/ROOTLESS_AGENT_PERFORMANCE.md#2026-09-30-결과와-채택-판단)는 정확성 검사를 통과했지만 dpkg 전체 시간 이득과 큰 저장소 회귀 없는 가속을 입증하지 못했다. 따라서 이 후보는 기본 배포 입력으로 채택하지 않는다. version/help와 실제 인증 작업을 구분하며, stock enforcing 및 물리 16 KiB 기기 지원을 주장하지 않는다.
