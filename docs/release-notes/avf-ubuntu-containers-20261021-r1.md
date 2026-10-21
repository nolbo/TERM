# TERM AVF Ubuntu 컨테이너 테스트 이미지

- 이미지 ID: `avf-ubuntu-containers-20261021-r1`
- 배포일: 2026-10-21
- 대상: AVF Ubuntu ARM64 실행환경
- 사용자 기기 검증을 위한 이미지 프리릴리즈입니다. 정식·Preview 앱 채널 배포와 기본 이미지 카탈로그 승격은 포함하지 않습니다.

## 변경

- PID·IPC namespace, System V IPC, POSIX Message Queue, PIDs cgroup을 지원하는 커널로 교체하여 컨테이너 생성과 `/dev/mqueue` 사용에 필요한 기능을 추가합니다.
- Docker 브리지 네트워크에 필요한 addrtype, conntrack, IPv4·IPv6 NAT와 iptables/nftables 지원을 보완합니다.
- ReDroid에 필요한 DMA-BUF System Heap을 활성화하고 BinderFS, memfd, seccomp, IPv6, OverlayFS, ARM64 4KB 페이지와 기존 AVF 보안 경계를 유지합니다.
- 선택적으로 내려받는 ReDroid 실행 도구와 [기기 테스트 안내](https://github.com/nolbo/TERM/blob/dev/docs/REDROID_TEST.ko.md)를 제공합니다. 도구는 게스트의 ext4 저장공간을 사용하고 해당 컨테이너의 사전 셰이더 캐시만 조정합니다. Docker·ReDroid 및 실행 도구는 이미지에 기본 설치하지 않습니다.

## 설치와 제한

- 이미지 공개만으로 기존 APK의 승인 카탈로그나 설치된 Ubuntu 커널이 바뀌지 않습니다. 이 이미지를 포함한 검토용 APK 또는 카탈로그를 포함한 빌드가 필요합니다. 기존 APK를 제거하거나 기존 실행환경에 파일을 덮어쓰지 말고 별도 Ubuntu 테스트 환경을 사용하세요.
- 기존 Ubuntu와 커널이 달라 구성요소 업데이트만으로 적용할 수 없습니다. 기존 환경의 전환에는 전체 이미지 전환과 데이터 보관이 필요합니다. Debian·Arch Linux 이미지는 변경하지 않습니다.
- Docker `hello-world`와 ReDroid의 부팅·Settings 화면은 Linux 소프트웨어 가상화 환경에서 확인했습니다. 실제 Android AVF, LXC 컨테이너, 외부 네트워크 전달, ReDroid 장시간 안정성과 재부팅은 사용자 기기에서 추가 확인해야 합니다. 이전 비교 실행에서는 watchdog·ANR과 검은 화면이 발생했습니다.
- Docker 데이터와 ReDroid `/data`는 게스트의 ext4 저장공간에 두어야 합니다. 기존 OverlayFS 루트의 `/var/lib/docker`에 `overlay2`를 그대로 구성하면 실패할 수 있습니다. 기기·메모리·가상 CPU에 따라 소프트웨어 렌더링 성능이 달라집니다.

## 2026-10-21 테스트 APK 설치 호환성 보완

- 기존 `1.1.0+dev.214718078.36bfa1021b9a-debug`와 같은 Debug 서명을 사용하는 `TERM-Debug-AVF-214751243-6da6868.apk`를 추가합니다. 기존 앱 위에 설치할 수 있으며 이미지 카탈로그를 포함한 새 버전은 `1.2.0+dev.214751243.6da68682aa79-debug`입니다.
- 이전에 안내한 검토용 APK와 기존 Debug 앱의 서명이 달라 업데이트 설치가 거부되던 문제를 보완했습니다. [호환 APK](https://github.com/nolbo/TERM/releases/download/avf-ubuntu-containers-20261021-r1/TERM-Debug-AVF-214751243-6da6868.apk)를 사용하고 기존 앱은 제거하지 마세요. 다른 키로 서명된 Debug 설치본에는 별도 서명 확인이 필요합니다.
- 설치와 ReDroid 테스트에는 [수정된 테스트 안내](https://github.com/nolbo/TERM/releases/download/avf-ubuntu-containers-20261021-r1/REDROID_TEST-debug-update.ko.md)를 사용하세요.
- Ubuntu 이미지와 ReDroid 실행 도구의 내용은 그대로 유지합니다. APK 설치 전에 진행 중인 작업을 저장하세요. 정식·Preview 앱 채널에는 배포하지 않습니다.
