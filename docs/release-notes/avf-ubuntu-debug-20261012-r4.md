# TERM AVF Ubuntu ARM64 디버그 이미지

- 릴리즈 ID: avf-ubuntu-debug-20261012-r4
- 배포일: 2026-10-13
- 대상: 새 AVF Ubuntu 24.04 LTS ARM64 환경

## 변경 및 개선

- Userspace Linux와 동일한 체크섬 고정 Ubuntu rootfs를 기반으로 AVF 이미지를 제작했습니다. 공통 AVF 커널·초기 부팅층과 Ubuntu의 패키지 데이터베이스를 사용합니다.
- Debian과 같은 설치·환경 관리 흐름, 게스트 터미널·파일 전송·포트 보고 서비스, UID/GID 1000의 `droid` 계정과 sudo를 제공합니다.
- 설치 시 APT 패키지 목록을 초기화하며 실패 후 재시도를 지원합니다. 영속 쓰기 계층, 직접 공유 디렉토리, Android 공유 저장소 연결과 DHCP·DNS 설정을 포함합니다.

## 설치 및 호환성

- 온라인 설치에는 해당 배포판을 포함한 앱의 이미지 카탈로그와 인터넷 연결이 필요합니다.
- Ubuntu 이미지는 Ubuntu 환경에만 설치합니다. 기존 Debian 환경이나 사용자 파일을 자동 교체하거나 이전하지 않습니다.
- 실제 AVF 기기의 부팅·vsock·공유 파일 I/O·DHCP·DNS·인터넷 패키지 갱신은 확인하지 못했습니다. AVF 지원 ARM64 기기와 호환 플랫폼 API, 실제 VM 권한이 필요합니다.
