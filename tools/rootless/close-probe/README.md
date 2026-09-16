# close 추적 비용과 identity 비교

**파일 작업 한정 성능 실험이다. 일반 rootless 실행에 적용하지 않는다.**
`proot-close-probe.patch`는 native ARM64 close의 TRACE를 ALLOW로 바꾼다.
auxv/netlink FD bookkeeping과 pipe shadow 처리를 생략하므로 FD 재사용,
네트워크·파이프를 포함한 일반 실행의 정상성을 보장하지 않는다.

## 재현

```sh
PROOT_BUILD_FASTSTAT=1 PROOT_BUILD_CLOSE_PROBE=1 \
  bash tools/rootless/build-proot-arm64.sh /tmp/terminal-close-probe/payload
```

빌드 옵션에 더해 실행 시 tracer 환경의 `PROOT_CLOSE_KERNEL_PROBE=1`이
필요하다. 기본 빌드·앱 실행에는 포함하지 않는다. 별도 성능 APK로 패키징하고
`workloads.py --count 1000 --io-mib 64`에서 metadata, mutations, small-read,
python-import, tar-extract, sequential-read, sequential-write, synced-write를
명시적으로 선택했다. git-status는 이 비교에 포함하지 않았다.

| 조건 | 가상 root | fstat 진입 완료 | close 추적 |
| --- | --- | --- | --- |
| current | `-0` | 켬 | 유지 |
| close | `-0` | 켬 | 생략 |
| identity | `-0` 없음 | 끔 | 유지 |
| identity-close | `-0` 없음 | 끔 | 생략 |

모든 조건에서 stat/path/rename 실험 옵션과 native links를 사용했다.
기존 기준은 10단계 b8ea... 실험판이며 제품 원본이 아니다. 서로 다른 UID를
비교하는 행은 권한 모사 비용의 탐색이다. 사용자 실행 모드 변경을 구현하거나
패키지 관리가 정상 동작한다고 검증한 것이 아니다.

## 결과와 결정

[7회 교대, 224개 원시 결과](results/close-identity-2026-09-16.json)의 중앙값(ms):

| 작업 | current | close | identity | identity-close |
| --- | ---: | ---: | ---: | ---: |
| metadata | 24.923 | 28.292 | 25.009 | 26.455 |
| 파일 변경 | 297.950 | 281.420 | 266.644 | 241.571 |
| 작은 파일 읽기 | 141.536 | 121.492 | 91.806 | 72.529 |
| Python import | 171.172 | 167.731 | 161.157 | 157.052 |
| tar 추출 | 705.887 | 660.795 | 329.355 | 306.600 |
| 순차 읽기 | 13.207 | 13.164 | 13.232 | 12.917 |
| 순차 쓰기 | 13.585 | 13.642 | 13.772 | 13.657 |
| fsync 포함 쓰기 | 281.594 | 280.568 | 282.119 | 281.002 |

같은 가상 root에서 close 생략은 작은 파일 읽기 시간을 약 14.2%, tar는
약 6.4% 줄였다. metadata는 약 13.5% 느려졌으며 수치를 제외하지 않았다.
별도 계측에서 진입 정지는 13009→10848회, 종료 정지는 2179회로 같았다.
계측에는 시작·준비·정리가 포함된다. 순차 I/O는 사실상 변하지 않았다.

기기의 Python `TarFile.chown`은 `os.geteuid() == 0`일 때만 사용자·그룹 조회와
소유권 변경을 한다. 일반 UID의 tar 시간에는 작업량 감소가 포함된다.
내용 검증 통과가 같은 소유권 보존 의미를 입증하지 않으므로 그 배속을
일반 rootless 엔진의 2배 달성으로 보고하지 않는다. 후속 단일 확인에서
가상 UID 1000(`-i 1000:1000`)도 tar 407.655ms였지만 작은 파일 읽기는
140.417ms였다. 권한 표시와 실제 syscall 처리 비용을 분리해야 한다.

라이브러리 기반 권한 모사도 검토할 수 있다. Debian의
[fakeroot 매뉴얼](https://manpages.debian.org/trixie/fakeroot/fakeroot.1.en.html)은
LD_PRELOAD 기반 동작을 설명한다. 이 실험에서는 fakeroot를 설치하거나
패키지 관리 대체 수단으로 검증하지 않았다.

다음 구현은 일반 파일의 close 비용을 줄이면서 특수 FD의 상태 처리를 유지해야
한다. 일반 작업과 관리자 작업을 분리하는 방식도 사용자 계정·패키지 관리·
권한 의미 검증이 선행되어야 한다. 이 패치를 제품에 활성화하는 것은 보류한다.

## 검증 범위

- ARM64 실험 엔진 빌드·별도 APK 서명/설치 및 224개 작업의 내용 검증 통과.
- Android MCP를 사용했다. 증분 설치 미지원 후 streamed 설치가 성공했다.
- 최종 probe SHA-256: `8ca501517c7befef1cc3175f01670403178f65caf12e68aa1c690955a848515d`.
- 기준 SHA-256: `b8ea54de9d0e32c02ad4d3d28d43aee3f2fb99c338cd73a337c87bb38f095b6f`.
- 일반 FD 재사용·네트워크·파이프·새 설치·stock Android 정책 검증은 미실행.
  파일 내용 검사로 이를 대신하지 않는다. 이전 단계의 설치 통과를 이 probe에
  전용하지 않는다.
- 별도 native 실험 패치와 빌드 도구만 변경하여 Gradle 검증은 재실행하지 않았다.
  기본 payload와 사용자 세션 설정은 바꾸지 않았다.
