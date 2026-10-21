# AVF Ubuntu 컨테이너 이미지 기기 테스트

테스트 이미지: [avf-ubuntu-containers-20261021-r1](https://github.com/nolbo/TERM/releases/tag/avf-ubuntu-containers-20261021-r1).
정식·Preview 앱 채널 배포와 기본 카탈로그 변경은 포함하지 않습니다. 이미지의 커널·초기 부팅
이미지·디스크는 검증한 후보의 바이트를 그대로 공개합니다. 별도 실행 도구는
릴리즈의 `launch-redroid.py`이며 이미지 안에 기본 설치되어 있지 않습니다.

## 1. 별도 Ubuntu 테스트 환경 준비

현재 APK의 카탈로그에 이 릴리즈가 없으면 **이미지 설치**나 **업데이트 확인**만으로
새 커널을 받을 수 없습니다. 기존
`1.1.0+dev.214718078.36bfa1021b9a-debug`에서 업데이트할 때는
[호환 서명 TERM Debug APK](https://github.com/nolbo/TERM/releases/download/avf-ubuntu-containers-20261021-r1/TERM-Debug-AVF-214751243-6da6868.apk)를 내려받아
기존 앱 위에 설치합니다. 새 버전은 `1.2.0+dev.214751243.6da68682aa79-debug`이며
같은 이미지 카탈로그를 포함합니다. SHA-256은 릴리즈의
`TERM-Debug-AVF-214751243-6da6868.sha256`에서 확인할 수 있습니다.

원래 CI 산출물은 실행마다 생성되는 Debug 키로 서명되어 기존 로컬 Debug 앱의
업데이트에 사용할 수 없었습니다. 새 파일은 검증된 CI APK의 내부 파일을 그대로
두고 기존 Debug 설치본과 같은 보관된 키로 다시 서명한 것입니다. 같은 구버전이
설치된 Android 테스트 대상에서 제거 없이 업데이트하고 데이터 검증 파일의 보존을
확인했습니다. 기존 CI 산출물과 공개 이미지 파일은 교체하지 않았습니다.

TERM Debug는 정식·Preview 앱과 별도 앱입니다. 이 앱 자체에 필요한 AVF 시스템
권한이 있어야 하며 APK 설치만으로 권한이 추가되지 않습니다. 다른 키로 서명한
Debug 앱에는 이 APK도 업데이트로 설치되지 않습니다. 이 경우 기존 앱을 제거하지
말고 설치 오류와 기존 APK 서명을 확인해야 합니다. APK 업데이트 시 실행 중인 앱과
세션이 종료될 수 있으므로 진행 중인 작업을 저장한 뒤 설치합니다.

해당 카탈로그를 포함한 권한 있는 앱에서 **새 Ubuntu AVF 실행환경과 프로필**을
만들고 이미지 설치를 선택합니다. 기존 Ubuntu는 그대로 보관합니다. 세션을 연 뒤
아래 명령은 전부 **그 Ubuntu 게스트 안에서** 실행합니다. Native·Userspace Linux
터미널에서는 실행하지 마세요. 인터넷, 컨테이너용 여유 저장공간, 충분한 VM 메모리가
필요합니다. 비교 검증은 게스트 메모리 4 GiB와 가상 CPU 4개로 수행했습니다.

기존 환경을 갱신하려면 [전체 이미지 전환 안내](AVF_UPDATES.md)를 따르세요.
커널 해시가 다르므로 구성요소 업데이트만으로는 적용되지 않습니다. 설치된 이미지의
체크섬을 고치거나 커널 파일만 덮어쓰는 방법은 사용하지 않습니다.

## 2. 실제 실행 커널 확인

```sh
uname -r
getconf PAGESIZE
sudo /usr/local/lib/droid/image-container-selftest
```

예상 커널은 `6.12.60-android16-6-maybe-dirty-term-containers`, 페이지 크기는 `4096`,
마지막 성공 표시는 `TERM_AVF_CONTAINERS_OK`입니다. 이 검사는 실행 중인 커널의
설정 해시와 이미지 메타데이터를 비교하고 PID/IPC namespace, SysV IPC, mqueue,
cgroup controller, BinderFS, DMA heap, bridge/veth와 두 iptables backend의 NAT
규칙 생성을 검사합니다. 다른 커널이면 다음 단계로 진행하지 말고 결과를 보내주세요.

```sh
zcat /proc/config.gz | grep -E '^CONFIG_(PID_NS|IPC_NS|SYSVIPC|POSIX_MQUEUE|CGROUP_PIDS|NETFILTER_XT_MATCH_ADDRTYPE|DMABUF_HEAPS_SYSTEM)='
cat /sys/fs/cgroup/cgroup.controllers
ls -l /dev/dma_heap/system
findmnt -T /.droid/state
ip -br address
ip route
resolvectl status
```

일곱 설정은 모두 `=y`, 쓰기 상태 파일시스템은 ext4여야 합니다. AVF 터미널이
열려도 인터넷 연결을 보장하지 않습니다. 아래 다운로드가 실패하면 IP·기본 경로·DNS와
앱의 AVF 네트워크 설정을 먼저 확인합니다.

## 3. 독립 테스트용 Docker 준비

검증에 사용한 ARM64 Docker 29.1.3을 새 테스트 데이터 디렉터리와 전용 소켓으로
실행합니다. 기존 Docker 설정과 데이터를 수정하지 않습니다. Ubuntu 세션에서 Bash를
사용하세요. 패키지 설치는 Ubuntu의 서명된 저장소를 사용합니다.

```sh
sudo apt-get update
sudo apt-get install -y curl ca-certificates python3 iptables
mkdir -p "$HOME/term-redroid-downloads"
cd "$HOME/term-redroid-downloads"
curl -fL --proto '=https' -o docker-29.1.3.tgz \
  https://download.docker.com/linux/static/stable/aarch64/docker-29.1.3.tgz
echo '2219f3ac48727d8c5546b13d5d271a58856188ecb8ff1f71f9e2f9aefde3ea76  docker-29.1.3.tgz' | sha256sum -c -
```

체크섬이 `OK`일 때만 다음을 실행합니다. 같은 테스트 경로에 이미 데이터를 두었다면
새 경로·소켓·브리지 이름을 정하거나 이전 테스트를 명시적으로 관리하세요.

```sh
sudo test ! -e /.droid/state/term-redroid-test
# 위 명령이 실패하면 다음 설치를 중단합니다.
sudo install -d -m 0755 /.droid/state/term-redroid-test/bin
sudo tar -xzf docker-29.1.3.tgz --strip-components=1 \
  -C /.droid/state/term-redroid-test/bin
sudo install -d -m 0700 /run/term-redroid-test
printf '%s\n' '{"features":{"containerd-snapshotter":false}}' | \
  sudo tee /.droid/state/term-redroid-test/daemon.json >/dev/null
sudo sh -c 'PATH=/.droid/state/term-redroid-test/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  nohup dockerd --config-file=/.droid/state/term-redroid-test/daemon.json \
    --host=unix:///run/term-redroid-test/docker.sock \
    --exec-root=/run/term-redroid-test/exec --pidfile=/run/term-redroid-test/dockerd.pid \
    --data-root=/.droid/state/term-redroid-test/docker --storage-driver=overlay2 \
    --bridge=termtest0 --exec-opt=native.cgroupdriver=cgroupfs \
    > /.droid/state/term-redroid-test/dockerd.log 2>&1 < /dev/null &'
docker_test() {
  sudo /.droid/state/term-redroid-test/bin/docker \
    --host unix:///run/term-redroid-test/docker.sock "$@"
}
# daemon 초기화가 끝나면 실행합니다.
docker_test info
docker_test run --rm --pids-limit=32 --memory=64m hello-world
```

`Hello from Docker!`가 출력되어야 합니다. 이 기기의 이미지 pull 성공은 외부 통신
검증도 추가하지만, 모든 NAT 전달 경로를 증명하지는 않습니다. daemon이 시작하지
않으면 `sudo tail -n 100 /.droid/state/term-redroid-test/dockerd.log`를 확인합니다.
이 테스트 daemon은 자동 시작 서비스로 등록하지 않으며 VM 재시작 후에는 위의
디렉터리 생성·압축 해제를 반복하지 않고 daemon 실행부터 다시 수행합니다.

## 4. ReDroid 실행과 화면 확인

실행 도구는 내려받은 파일의 해시를 확인한 다음 실행합니다. Android 15 컨테이너는
검증에 사용한 이미지 digest로 고정합니다. 게스트 밖 Android 호스트의 권한이나
보안 설정을 바꾸지 않습니다.

```sh
curl -fL --proto '=https' -o launch-redroid.py \
  https://github.com/nolbo/TERM/releases/download/avf-ubuntu-containers-20261021-r1/launch-redroid.py
echo 'c9e1e90e3b826ffc54d31eee1beb8443d661d51efdde9d3a7e5be4c683e2cefe  launch-redroid.py' | sha256sum -c -
docker_test pull --platform linux/arm64 \
  redroid/redroid@sha256:81889852444606824d33eb0232fa5813bce3f0993afe871785120af0d7c991ad
sudo env DOCKER_HOST=unix:///run/term-redroid-test/docker.sock \
  PATH=/.droid/state/term-redroid-test/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  python3 launch-redroid.py --name term-redroid-test \
    --data /.droid/state/term-redroid-test/android-data \
    --image redroid/redroid@sha256:81889852444606824d33eb0232fa5813bce3f0993afe871785120af0d7c991ad \
    --width 240 --height 320 --dpi 120 --fps 10
```

`--privileged`는 이 새 게스트 내부 컨테이너에만 적용합니다. 실행 도구는
`androidboot.use_memfd=1`과 소프트웨어 GPU를 선택하며 기존 `/system/build.prop`의
다른 설정을 보존하고 `service.sf.prime_shader_cache=0`만 읽기 전용 사본으로
적용합니다. 기존 이름의 컨테이너는 덮어쓰지 않습니다. 기본값은 ADB 포트를 외부에
공개하지 않습니다. 비교 실행에는 별도 이름·새 data와 `--prime-shader-cache`를 쓸 수
있지만 watchdog 시간 제한이나 보안 설정은 변경하지 않습니다.

```sh
docker_test exec term-redroid-test getprop sys.boot_completed
docker_test exec term-redroid-test getprop service.sf.prime_shader_cache
docker_test exec term-redroid-test sh -c \
  'service check activity; service check package; service check display'
```

부팅 완료는 `1`, 셰이더 설정은 `0`, 서비스 세 개는 `found`여야 합니다. 첫 부팅은
시간이 걸립니다. 성공한 QEMU 비교에서는 약 4분이었으며 이후 4분을 더 기다려
Settings를 확인했습니다. 실제 기기에서도 완료 후 백그라운드 초기화를 기다리세요.

```sh
docker_test exec term-redroid-test input keyevent WAKEUP
docker_test exec term-redroid-test am start -W -a android.settings.SETTINGS
docker_test exec term-redroid-test screencap -p > "$HOME/redroid-settings.png"
docker_test exec term-redroid-test dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity'
docker_test exec term-redroid-test find /data/system/dropbox -maxdepth 1 -name '*watchdog*'
```

`Status: ok`와 현재 `com.android.settings` activity를 확인하고 PNG를 TERM 파일 전송
등으로 열어 **Settings 항목이 실제 보이는지** 확인합니다. 부팅 값 `1` 또는 홈 화면만
보이는 것은 Settings 성공으로 간주하지 않습니다. `system_server_watchdog@...`가
있으면 실패이며 `system_server_pre_watchdog@...` 경고와 구분합니다.

같은 `/data` 재시작도 확인하려면 `docker_test stop term-redroid-test` 후
`docker_test start term-redroid-test`를 수행하고 부팅·서비스·화면 검사를 반복합니다.
일반 Ubuntu 셸·sudo·네트워크·파일 유지·공유 폴더도 VM 재시작 전후에 확인합니다.
이 문서의 절차는 실제 LXC 컨테이너 실행을 검증하지 않습니다.

## 5. 결과 전달과 테스트 종료

성공 여부, `uname -r`, 커널 selftest 출력, Docker 버전·hello-world 결과, 부팅 값,
Settings 실행 결과·PNG, watchdog 파일 목록을 보내주세요. 실패 시 다음 로그를
추가합니다. 공유 전 개인 정보가 들어 있는 줄은 제거하세요.

```sh
docker_test logs --tail 200 term-redroid-test
docker_test exec term-redroid-test logcat -b all -d -t 300
sudo tail -n 100 /.droid/state/term-redroid-test/dockerd.log
```

종료는 `docker_test stop term-redroid-test`로 수행합니다. daemon은
`sudo kill -TERM "$(sudo cat /run/term-redroid-test/dockerd.pid)"`로 종료할 수 있습니다.
이 명령은 앞에서 만든 전용 daemon에만 사용합니다. 데이터·컨테이너·기존 환경은
자동 삭제하지 않습니다. 실제 AVF의 장시간 안정성·재부팅·네트워크 결과는 아직
검증되지 않았으며 이 테스트로 확인하려는 항목입니다.
