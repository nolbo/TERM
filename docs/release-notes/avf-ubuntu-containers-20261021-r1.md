# TERM AVF Ubuntu 컨테이너 테스트 이미지

- 이미지 ID: `avf-ubuntu-containers-20261021-r1`
- 배포일: 2026-10-21
- 대상: AVF Ubuntu ARM64 실행환경
- 최초 공개는 사용자 기기 검증용 프리릴리즈였습니다. 이후 기본 이미지 제공 범위는 아래 2026-10-22 TERM 1.3.0 항목을 참고하세요.

## 변경

- PID·IPC namespace, System V IPC, POSIX Message Queue, PIDs cgroup을 지원하는 커널로 교체하여 컨테이너 생성과 `/dev/mqueue` 사용에 필요한 기능을 추가합니다.
- Docker 브리지 네트워크에 필요한 addrtype, conntrack, IPv4·IPv6 NAT와 iptables/nftables 지원을 보완합니다.
- ReDroid에 필요한 DMA-BUF System Heap을 활성화하고 BinderFS, memfd, seccomp, IPv6, OverlayFS, ARM64 4KB 페이지와 기존 AVF 보안 경계를 유지합니다.
- 선택적으로 내려받는 ReDroid 실행 도구와 [기기 테스트 안내](https://github.com/nolbo/TERM/blob/dev/docs/REDROID_TEST.ko.md)를 제공합니다. 도구는 게스트의 ext4 저장공간을 사용하고 해당 컨테이너의 사전 셰이더 캐시만 조정합니다. Docker·ReDroid 및 실행 도구는 이미지에 기본 설치하지 않습니다.

## 설치와 제한

- 이미지 공개만으로 기존 APK의 승인 카탈로그나 설치된 Ubuntu 커널이 바뀌지 않습니다. 이 이미지를 포함한 검토용 APK 또는 카탈로그를 포함한 빌드가 필요합니다. 기존 APK를 제거하거나 기존 실행환경에 파일을 덮어쓰지 말고 별도 Ubuntu 테스트 환경을 사용하세요.
- 기존 Ubuntu와 커널이 달라 구성요소 업데이트만으로 적용할 수 없습니다. 기존 환경의 전환에는 전체 이미지 전환과 데이터 보관이 필요합니다. Debian·Arch Linux 이미지는 변경하지 않습니다.
- Docker `hello-world`와 ReDroid의 부팅·Settings 화면은 Linux 소프트웨어 가상화 환경에서 확인했습니다. 이후 실제 AVF 기기에서 확인한 부팅·서비스·Settings 실행 결과는 아래 2026-10-22 항목에 기록합니다. LXC 컨테이너, 외부 네트워크 전달, ReDroid 장시간 안정성과 재부팅은 추가 확인해야 합니다. 이전 비교 실행에서는 watchdog·ANR과 검은 화면이 발생했습니다.
- Docker 데이터와 ReDroid `/data`는 게스트의 ext4 저장공간에 두어야 합니다. 기존 OverlayFS 루트의 `/var/lib/docker`에 `overlay2`를 그대로 구성하면 실패할 수 있습니다. 기기·메모리·가상 CPU에 따라 소프트웨어 렌더링 성능이 달라집니다.

## 2026-10-21 테스트 APK 설치 호환성 보완

- 기존 `1.1.0+dev.214718078.36bfa1021b9a-debug`와 같은 Debug 서명을 사용하는 `TERM-Debug-AVF-214751243-6da6868.apk`를 추가합니다. 기존 앱 위에 설치할 수 있으며 이미지 카탈로그를 포함한 새 버전은 `1.2.0+dev.214751243.6da68682aa79-debug`입니다.
- 이전에 안내한 검토용 APK와 기존 Debug 앱의 서명이 달라 업데이트 설치가 거부되던 문제를 보완했습니다. [호환 APK](https://github.com/nolbo/TERM/releases/download/avf-ubuntu-containers-20261021-r1/TERM-Debug-AVF-214751243-6da6868.apk)를 사용하고 기존 앱은 제거하지 마세요. 다른 키로 서명된 Debug 설치본에는 별도 서명 확인이 필요합니다.
- 설치와 ReDroid 테스트에는 [수정된 테스트 안내](https://github.com/nolbo/TERM/releases/download/avf-ubuntu-containers-20261021-r1/REDROID_TEST-64only.ko.md)를 사용하세요.
- Ubuntu 이미지와 ReDroid 실행 도구의 내용은 그대로 유지합니다. APK 설치 전에 진행 중인 작업을 저장하세요. 정식·Preview 앱 채널에는 배포하지 않습니다.

## 2026-10-21 Docker daemon 실행 안내 수정

- 사용자 기기 로그에서 확인한 `bridge device with non default name termtest0 must be created manually` 오류를 수정합니다. 이전 안내에 빠진 전용 브리지 생성 단계를 추가하고 daemon 준비 완료를 확인한 뒤 `hello-world`를 실행합니다.
- 이미 설치한 Docker와 테스트 데이터는 그대로 사용합니다. 실패한 사용자는 수정 안내의 **daemon 시작·재시작** 블록부터 실행하면 됩니다. VM 재시작 시 필요한 브리지와 `/run` 디렉터리도 이 블록에서 복구합니다.
- 배포한 커널·Ubuntu 이미지를 사용하는 별도 QEMU 게스트에서 같은 실패를 재현하고, 브리지 생성 후 같은 daemon 명령으로 `docker info`와 PIDs·메모리 제한을 둔 `hello-world` 실행을 확인했습니다. 이 안내 작성 당시 대기 중이던 실제 AVF 결과는 아래 후속 검증에 기록합니다.
- 이 daemon 수정은 커널·Ubuntu 이미지·APK·ReDroid 실행 도구를 교체하지 않습니다. 해당 안내는 `REDROID_TEST-daemon-fix.ko.md`로 기록을 유지합니다.

## 2026-10-22 AArch64 전용 AVF의 ReDroid 실행 수정

- 32비트 실행을 제공하지 않는 AVF 가상 CPU에서 혼합 ABI 이미지의 BoringSSL 32비트 자체 검사가 `Exec format error`로 종료되어 Android가 재부팅하는 문제에 대응합니다. 커널의 `CONFIG_COMPAT=y`만으로 CPU의 실행 모드가 추가되지는 않습니다.
- 기본 ReDroid 이미지를 공식 Android 15 `64only` ARM64 manifest `sha256:dc2024a999dd0acb1112a23cddc9537ec8cb60a0dbe5a87ce9fbd75a2f5e94d2`로 고정한 `launch-redroid-64only.py`와 새 안내 `REDROID_TEST-64only.ko.md`를 추가합니다. 공식 이미지의 64비트 보안 자체 검사를 유지하며 32비트 앱·네이티브 라이브러리는 지원하지 않습니다.
- 기존 `term-redroid-test`와 `android-data`를 보존하고 `term-redroid-test64`와 새 `android-data64`를 사용합니다. 이전 실행 도구도 새 이미지 digest를 `--image`로 명시하면 사용할 수 있습니다.
- 사용자 제공 실제 AVF 게스트 결과에서 새 컨테이너의 `Running=true`와 `sys.boot_completed=1`을 확인했습니다. 사용자는 activity·package·display 서비스 검사와 Settings 실행도 정상임을 확인했습니다. 기기 화면을 직접 열람하지 않았으며 장시간 안정성과 재시작은 별도 검증 항목입니다.
- 이미지 관련 Python 테스트 102개와 저장소 표준 Debug 빌드·runtime/image 테스트가 통과했습니다. 배포 커널을 사용한 별도 QEMU 준비 검사에서는 Docker `hello-world`와 64비트 이미지 가져오기·내용 식별을 확인했습니다. 이 준비 검사는 Android를 부팅하지 않았습니다.
- Ubuntu 커널·디스크·APK·승인 카탈로그는 그대로 사용합니다. 기존 공개 파일을 덮어쓰거나 AVF 보안 설정을 변경하지 않습니다.

## 2026-10-22 TERM 1.3.0 기본 이미지 제공

- 이 이미지의 동일한 공개 파일을 TERM 1.3.0 정식판과 TERM Preview 1.3.0의 기본 이미지로 제공합니다. 기존 이미지 파일과 태그를 교체하지 않습니다.
- 새 앱에서 별도 실행환경을 설치하거나 지원되는 전체 이미지 전환을 선택하면 새 커널을 사용할 수 있습니다. 기존 환경과 데이터는 자동 교체하지 않으며, 구성요소 업데이트만으로 커널이 바뀌지 않습니다.
- [1.3.0 안내](https://github.com/nolbo/TERM/blob/dev/docs/release-notes/1.3.0.md)의 지원 범위와 제한을 확인하세요. 앞선 테스트 APK와 설치 안내는 기존 테스트 사용자를 위해 유지합니다.
