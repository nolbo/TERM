# TERM Preview 1.3.0 프리릴리즈

- 릴리즈 ID: dev-latest
- 출시일: 2026-10-22
- 대상: Android ARM64 프리릴리즈 앱 TERM Preview

## 추가 및 개선

- AVF Debian·Ubuntu·Arch Linux의 기본 이미지를 컨테이너용 커널로 갱신했습니다. Docker/LXC에 필요한 PID·IPC 격리, System V IPC, 메시지 큐와 프로세스 수 제한 기능을 추가했습니다.
- Docker 브리지 네트워크의 주소 유형 검사와 IPv4·IPv6 NAT 지원을 보완하여 기존 컨테이너 생성·메시지 큐·네트워크 초기화 실패에 대응합니다.
- ReDroid용 DMA-BUF System Heap을 추가하고 기존 BinderFS, memfd, seccomp, OverlayFS와 4KB 페이지 지원을 유지합니다.
- 64비트 실행만 제공하는 AVF에서는 공식 Android 15 ARM64 64비트 전용 ReDroid 이미지를 사용하도록 실행 안내를 정리했습니다. Docker 전용 브리지 생성과 daemon 시작·재시작 방법도 제공합니다.
- Docker·LXC·ReDroid는 필요한 경우 게스트에 별도로 설치합니다. 앱에 게스트 커널이나 컨테이너 이미지를 내장하지 않습니다.

## 적용과 기존 환경

- 새 기본 이미지는 Debian `avf-debian-containers-20261022-r1`, Ubuntu `avf-ubuntu-containers-20261021-r1`, Arch `avf-archlinux-containers-20261022-r1`입니다.
- 앱 업데이트는 기존 프로필·설정·파일이나 Linux 환경을 자동 교체하지 않습니다. 새 커널을 사용하려면 별도 실행환경을 설치하거나 중요한 데이터를 보관한 뒤 지원되는 전체 이미지 전환을 선택하세요. 구성요소 업데이트만으로는 커널이 바뀌지 않습니다.
- Docker 데이터와 ReDroid 데이터는 게스트의 ext4 상태 디스크에 두세요. 자세한 설치·실행 방법은 [배포판별 안내](https://github.com/nolbo/TERM/blob/dev/docs/REDROID_DISTRIBUTIONS_TEST.ko.md)를 참고하세요.
- TERM과 TERM Preview는 기존과 같이 별도 앱과 데이터 영역을 사용합니다. 설치된 채널에 맞춰 업데이트하고 진행 중인 작업은 먼저 저장하세요.

## 지원 범위와 제한

- AVF 실행에는 호환되는 Android 플랫폼과 기존 AVF 권한이 필요합니다. 일반 APK 설치로 해당 권한이 추가되지 않습니다.
- 실제 Ubuntu AVF에서 ReDroid 부팅, 핵심 서비스와 Settings 실행을 사용자 결과로 확인했습니다. Debian·Arch 실제 AVF의 Docker/LXC·ReDroid 실행, 외부 네트워크 전달, 장시간 안정성과 재부팅은 추가 검증이 필요합니다.
- 안내하는 ReDroid 이미지는 32비트 앱·네이티브 라이브러리를 지원하지 않습니다. 기기 메모리와 가상 CPU에 따라 소프트웨어 렌더링 성능이 달라집니다.
- 기존 실제 16 KiB Android·다른 API·SELinux enforcing 환경, 특권 AVF 공유 폴더·관리 부팅·복구와 물리 USB의 미검증 범위는 남아 있습니다.
