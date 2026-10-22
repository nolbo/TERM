# TERM AVF Arch Linux ARM 컨테이너 테스트 이미지

- 이미지 ID: `avf-archlinux-containers-20261022-r1`
- 배포일: 2026-10-22
- 대상: AVF Arch Linux ARM 실행환경
- 사용자 기기 검증용 프리릴리즈이며 정식·Preview 앱 채널과 기본 카탈로그 승격은 포함하지 않습니다.

## 변경

- Ubuntu에서 검증한 Android Kleaf 기반 컨테이너 커널 빌드 경로를 Arch Linux에도 적용합니다. 커널과 일치하는 모듈로 초기 부팅 이미지를 만들고 최종 커널 설정과 빌드 정보를 시스템 이미지에 기록합니다.
- PID·IPC namespace, System V IPC, POSIX Message Queue, PIDs cgroup, Docker 브리지용 netfilter/NAT, DMA-BUF System Heap을 지원합니다. BinderFS, memfd, seccomp, IPv6, OverlayFS, ARM64 4KB 페이지와 기존 AVF 보안 경계를 유지합니다.
- 서명된 Arch Linux ARM 저장소의 iproute2·iptables를 포함하여 legacy/nft 양쪽 네트워크 검사를 수행합니다. pacman과 Arch 사용자 공간을 유지하며 Docker·LXC·ReDroid는 기본 설치하지 않습니다.
- 선택 설치하는 ReDroid에는 공식 Android 15 ARM64 64비트 전용 이미지를 사용합니다. 32비트 앱·네이티브 라이브러리는 지원하지 않습니다.

## 설치와 검증 범위

- [Debian·Arch 테스트 안내](https://github.com/nolbo/TERM/blob/dev/docs/REDROID_DISTRIBUTIONS_TEST.ko.md)를 사용하세요. 카탈로그를 포함한 검토용 APK 또는 검증된 이미지 디렉터리가 필요합니다. 기존 환경은 보존하고 별도 테스트 환경을 만드세요.
- 커널 변경을 받으려면 전체 이미지 전환이 필요합니다. 기존 환경의 구성요소 업데이트만으로 커널이 바뀌지 않습니다.
- 생산 과정에서 커널의 최종 설정·해시, 두 차례 컨테이너 기능 검사, 일반 systemd/vsock 부팅, pacman 패키지·sudo·영속성 및 공유 폴더 검사를 필수로 수행합니다. 성공한 이미지와 검토용 APK만 공개합니다.
- 실제 Arch AVF 기기의 Docker/LXC·ReDroid 실행, 외부 네트워크 전달, 장시간 안정성과 재부팅은 사용자 기기에서 확인해야 합니다. Ubuntu 실기기 결과를 Arch의 성공으로 간주하지 않습니다.
