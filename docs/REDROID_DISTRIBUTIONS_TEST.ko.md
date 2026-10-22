# Debian·Arch Linux AVF 컨테이너 테스트

세 배포판의 컨테이너 커널과 전체 이미지 업데이트의 계정 병합 수정을 포함한 테스트 배포입니다.
Docker·LXC·ReDroid를 이미지에 기본 설치하지 않으며, 앱의 AVF 권한과 기존 환경은 유지합니다.

| 배포판 | 테스트 이미지 |
| --- | --- |
| Debian | [avf-debian-maintenance-20261022-r1](https://github.com/nolbo/TERM/releases/tag/avf-debian-maintenance-20261022-r1) |
| Arch Linux ARM | [avf-archlinux-maintenance-20261022-r1](https://github.com/nolbo/TERM/releases/tag/avf-archlinux-maintenance-20261022-r1) |
| Ubuntu | [avf-ubuntu-maintenance-20261022-r1](https://github.com/nolbo/TERM/releases/tag/avf-ubuntu-maintenance-20261022-r1) |

[세 배포판을 포함한 TERM Debug APK](https://github.com/nolbo/TERM/releases/download/avf-ubuntu-maintenance-20261022-r1/TERM-Debug-AVF-maintenance-20261022.apk)를
내려받아 진행 중인 작업을 저장한 뒤 기존 TERM Debug 위에 설치합니다.
버전은 `1.3.1+dev.214805092.b0c6e5c1fa6e-debug`이며, 이전에 제공한 호환 Debug APK와
서명이 같습니다. [SHA-256 파일](https://github.com/nolbo/TERM/releases/download/avf-ubuntu-maintenance-20261022-r1/TERM-Debug-AVF-maintenance-20261022.sha256)도 제공합니다.
이 APK는 위 표의 세 이미지 카탈로그를 포함합니다. 기존 Ubuntu 전용 검토 APK는 새
Debian·Arch 카탈로그를 포함하지 않습니다. 검증된 이미지 디렉터리를 가져오는 방법도
사용할 수 있습니다.
설치된 환경을 덮어쓰지 말고 별도 실행환경을 만들거나 [전체 이미지 전환](https://github.com/nolbo/TERM/blob/dev/docs/AVF_UPDATES.md)을
사용하세요. 커널이 바뀌므로 구성요소 업데이트만으로는 적용되지 않습니다.
전체 이미지 전환은 `/.droid/state/term-redroid-test`의 Docker·ReDroid 데이터를 자동으로 이전하지 않습니다. 중요한 데이터는 먼저 보관하세요. 원래 상태 디스크는 이전 환경과 함께 복구용으로 유지됩니다.

연결된 Android 테스트 환경에서 이전 호환 Debug APK 위에 새 APK를 설치하고 앱의
테스트 데이터가 유지되는 것을 확인했습니다. 이 환경은 AVF를 제공하지 않으므로,
Debian·Arch의 실제 AVF 동작은 별도 기기 검증이 필요합니다.

게스트 안에서 다음을 실행합니다. 마지막 표시는 `TERM_AVF_CONTAINERS_OK`여야 하며,
페이지 크기는 `4096`, 상태 디스크의 파일시스템은 `ext4`여야 합니다.

```sh
uname -r
getconf PAGESIZE
sudo /usr/local/lib/droid/image-container-selftest
sudo findmnt -T /.droid/state
```

자체 검사는 실행 커널의 설정·버전과 이미지에 기록된 빌드 결과를 비교하고,
PID/IPC namespace, System V IPC, mqueue, cgroup, BinderFS, DMA heap,
bridge/veth 및 IPv4·IPv6의 legacy/nft NAT 규칙 생성을 확인합니다.

Docker·ReDroid 실행은 [공통 테스트 안내의 3절부터](https://github.com/nolbo/TERM/blob/dev/docs/REDROID_TEST.ko.md#3-독립-테스트용-docker-준비)
따릅니다. Debian에서는 안내의 APT 명령을 그대로 사용합니다. Arch에서는 APT 두 줄을
다음 명령으로 대체한 뒤 Docker 파일 다운로드부터 이어서 실행합니다.

```sh
sudo pacman -Syu --needed curl ca-certificates python iptables iproute2
```

전용 `termtest0` 브리지와 ext4의 Docker 데이터 디렉터리를 사용하며, 기존 daemon이나
데이터를 삭제하지 않습니다. ReDroid는 공통 실행 도구의 공식 Android 15 ARM64 `64only`
digest를 사용합니다. 32비트 앱·네이티브 라이브러리는 지원하지 않습니다. 보안 자체 검사나
Android watchdog을 비활성화하지 않습니다.

ReDroid의 `sys.boot_completed=1`, activity·package·display 서비스, Settings 실행과
화면을 확인하세요. 일반 셸·sudo·패키지 관리·네트워크·공유 폴더도 VM 재시작 전후에
확인해야 합니다. Ubuntu에서 확인한 실제 AVF 성공은 Debian·Arch 실기기 검증을 대신하지
않습니다. LXC 실행, 외부 네트워크 전달, 장시간 안정성과 재시작은 별도 검증 항목입니다.
