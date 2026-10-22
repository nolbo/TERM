# TERM AVF Debian 컨테이너 테스트 이미지

- 이미지 ID: `avf-debian-containers-20261022-r1`
- 배포일: 2026-10-22
- 대상: AVF Debian ARM64 실행환경
- 사용자 기기 검증용 프리릴리즈이며 정식·Preview 앱 채널과 기본 카탈로그 승격은 포함하지 않습니다.

## 변경

- Ubuntu에서 검증한 Android Kleaf 기반 컨테이너 커널 빌드 경로를 Debian에도 적용합니다. 커널과 일치하는 모듈로 초기 부팅 이미지를 만들고 최종 커널 설정과 빌드 정보를 시스템 이미지에 기록합니다.
- PID·IPC namespace, System V IPC, POSIX Message Queue, PIDs cgroup, Docker 브리지용 netfilter/NAT, DMA-BUF System Heap을 지원합니다. BinderFS, memfd, seccomp, IPv6, OverlayFS, ARM64 4KB 페이지와 기존 AVF 보안 경계를 유지합니다.
- 서명된 Debian 저장소의 iproute2·iptables를 포함하여 legacy/nft 양쪽 네트워크 검사를 수행합니다. Docker·LXC·ReDroid는 기본 설치하지 않습니다.
- 선택 설치하는 ReDroid에는 공식 Android 15 ARM64 64비트 전용 이미지를 사용합니다. 32비트 앱·네이티브 라이브러리는 지원하지 않습니다.

## 설치와 검증 범위

- [Debian·Arch 테스트 안내](https://github.com/nolbo/TERM/blob/dev/docs/REDROID_DISTRIBUTIONS_TEST.ko.md)를 사용하세요. [통합 TERM Debug APK](https://github.com/nolbo/TERM/releases/download/avf-archlinux-containers-20261022-r1/TERM-Debug-AVF-containers-20261022.apk)는 Debian·Ubuntu·Arch 개선 이미지 세 개를 포함한 카탈로그를 제공합니다. 진행 중인 작업을 저장하고 기존 Debug 앱 위에 설치하세요. 기존 환경은 보존하고 별도 테스트 환경을 만드세요.
- 커널 변경을 받으려면 전체 이미지 전환이 필요합니다. 기존 환경의 구성요소 업데이트만으로 커널이 바뀌지 않습니다.
- 생산 과정에서 커널의 최종 설정·해시, 두 차례 컨테이너 기능 검사, 일반 systemd/vsock 부팅, Debian 패키지·sudo·영속성 및 공유 폴더 검사를 필수로 수행합니다. 성공한 이미지와 검토용 APK만 공개합니다.
- 실제 Debian AVF 기기의 Docker/LXC·ReDroid 실행, 외부 네트워크 전달, 장시간 안정성과 재부팅은 사용자 기기에서 확인해야 합니다. Ubuntu 실기기 결과를 Debian의 성공으로 간주하지 않습니다.

## 검토용 APK

- 버전: `1.2.0+dev.214791050.27d5791d6b21-debug`. 기존 호환 Debug APK와 같은 서명을 사용하며 기존 앱을 제거하지 않고 업데이트할 수 있습니다.
- APK SHA-256: `7699478af53a0b91616349495105e0480c8f39f21f89f01f2ff95d586a72ac18`.
- 앱에 Docker·LXC·ReDroid나 게스트 커널을 내장하지 않습니다. 선택한 게스트 이미지는 별도로 내려받습니다.
