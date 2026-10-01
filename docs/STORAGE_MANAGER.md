# Storage Manager

저장소는 프로필이 사용하는 영구 실행 환경이며, 파일 탐색기나 별도의 저장소 생성 기능이 아닙니다. 식별자는 기존의 `(runtime, storageId)`를 유지합니다. Rootless의 Debian·Ubuntu·Arch는 서로 다른 runtime 값이므로 배포판 사이에 rootfs를 연결할 수 없습니다.

설정의 **저장소 / Storage**에서 사용 중인 저장소와 미사용 저장소를 확인합니다. 프로필 참조 또는 활성 세션이 있으면 사용 중으로 분류합니다. 이미 종료된 세션은 활성 수에서 제외합니다. 각 항목은 저장소 이름(미설정 시 실행 환경 이름), 실행 환경, 저장소 ID, 대략적인 용량, 프로필 수, 활성 세션 수를 표시하며 저장소 아이콘은 표시하지 않습니다. 상세 화면에서는 연결된 프로필 설정을 열고 실행 중인 세션 목록을 확인할 수 있습니다. 상세 화면의 저장소 이름 행에서 최대 120자의 표시용 이름을 수정할 수 있습니다. 이름을 비우면 기본 표시로 돌아갑니다. 이름은 프로필과 독립적으로 저장되고 기존 저장소 선택 목록에도 표시되며, 저장소 ID와 경로·실행 중 세션은 바뀌지 않습니다.

프로필 생성·복제는 기본적으로 독립 저장소 ID를 할당합니다. 생성 화면에서 새 독립 저장공간을 선택하면 이름 입력 행을 표시하고, 기존 저장공간을 선택하면 호환되는 저장소를 설정 행 목록으로 표시합니다. 편집 화면에서는 현재 저장소를 먼저 표시하고 호환되는 기존 저장소 및 새 독립 저장소를 선택할 수 있습니다. 같은 runtime·Rootless 배포판에 속한 미사용 저장소도 다시 연결할 수 있습니다. 선택과 이름 입력은 초안에만 반영되고 프로필 전체 저장으로 적용됩니다. 새 저장소의 기본 이름은 `Storage for [같이 생성한 프로필 이름]`이며, 이름 입력을 비우면 이 기본값을 사용합니다. 다른 runtime이나 미사용 저장소에 같은 이름이 있어도 새 이름에 ` (2)`, ` (3)` 등 사용 가능한 숫자를 붙입니다. 직접 입력한 새 이름에도 같은 중복 처리를 적용하며, 긴 기본 이름은 최대 120자 안에서 접미사가 들어가도록 줄입니다. 기본 이름은 초안의 프로필 이름을 따르지만 저장 후 프로필 이름 변경은 기존 저장소 이름을 바꾸지 않습니다. 새 저장소 이름과 프로필은 같은 manifest 교체로 함께 저장하며, 취소한 초안은 저장소를 생성하거나 기존 이름을 변경하지 않습니다. 새 독립 저장소는 데이터를 복제하지 않는 fresh environment이며 최초 실행에서 해당 runtime의 초기 환경을 준비합니다. 복제도 기존 환경의 데이터를 복사하지 않습니다.

storage 변경은 이후 생성되는 Session에만 적용됩니다. 기존 `SessionProfile → SessionLaunchSpec → Session`의 불변 launch snapshot과 `(runtime, storageId)`별 backend 소유권은 유지됩니다. 프로필이 이동하거나 삭제되어도 기존 저장소와 실행 중인 Session은 유지됩니다. 프로필 참조가 없어도 실행 중인 Session이 있으면 삭제할 수 없습니다.

**저장소 삭제**는 확인 대화상자를 거친 명시적 동작입니다. 활성 Session, 프로필 참조, `legacy` 중 하나라도 있으면 삭제할 수 없습니다. UI의 비활성화 외에도 runtime 서비스가 삭제 시점의 참조와 controller 목록을 다시 검증합니다. Session 목록 확인 실패를 빈 목록으로 취급하지 않습니다. 자동 garbage collection은 없습니다.

용량 계산은 화면에 표시되는 항목부터 IO dispatcher에서 진행합니다. 계산 결과는 서비스에서 1분간 캐시하며 UI는 계산 중·확인 불가 상태를 표시합니다. 표시는 논리적인 파일 크기 합계이므로 sparse disk, hardlink, 압축, 실제 할당 블록 때문에 기기의 실제 사용량과 다를 수 있습니다. 심볼릭 링크는 따라가지 않습니다. AVF 이미지와 해당 공유 폴더를 포함하며 플랫폼 관리 bookkeeping과 다운로드 cache는 이 수치에 포함하지 않습니다.

## 저장과 호환성

저장소 목록의 사용 중·미사용 항목을 누르면 별도의 `StorageDetailActivity`에서 해당 `(runtime, storageId)`의 설정을 엽니다. 목록과 상세는 각자의 Activity·스크롤 상태를 가지며, 뒤로 가면 원래 목록 위치로 돌아옵니다. 상세에서 저장소를 삭제하면 목록으로 돌아가고 목록은 서비스의 최신 정보를 다시 읽습니다. 연결된 프로필을 누르면 기존 `ProfileEditActivity`로 이동합니다.

`SessionStorage`는 경로 resolver로 유지합니다. `StorageRegistry`는 app-private `storage-registry.json`에 Profile override·삭제 표시와 저장소 ID 및 `(runtime, storageId)`별 표시용 이름을 기록합니다. 이름이 없는 기존 manifest는 그대로 읽으며 저장소 삭제 시 해당 이름도 제거합니다. 기존 `session-profiles` SharedPreferences는 처음 변경할 때 manifest로 옮기며 원본을 삭제하지 않습니다. 기본 프로필 설정은 기존 runtime preferences에서 계속 해석합니다. UI와 `:vmhost`는 같은 process-shared file lock과 원자적 파일 교체를 사용합니다. 파일 경로는 호스트가 runtime과 검증된 storage ID로 결정하며 Binder에서 임의 경로를 받지 않습니다.

manifest는 프로필 이동 전의 저장소도 기록하므로 한 번도 실행하지 않은 미사용 환경까지 조회·삭제할 수 있습니다. 삭제한 저장소 ID는 재사용을 막아 오래된 편집 화면이나 launch 요청이 환경을 되살리지 못하게 합니다. 이전 APK로 다운그레이드하면 원본 SharedPreferences를 읽으므로 새 manifest의 프로필 변경이 반영되지 않습니다. 업그레이드 후 manifest가 기준이며 원본 preferences를 다시 가져오지 않습니다.

AVF는 privileged provider가 정지된 persistent VM instance를 확인하고 플랫폼 API로 삭제한 뒤 이미지·공유 폴더를 제거합니다. controller의 활성 세션은 먼저 차단하고, host 재시작으로 등록되지 않은 VM은 플랫폼의 정지 상태가 확인되어야 삭제할 수 있습니다. 이 기능을 제공하지 않는 provider는 삭제를 거부합니다. AOSP driver의 `VirtualMachineManager.get/delete` 계약은 [AOSP source](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/aml_art_350913340/java/framework/src/android/system/virtualmachine/VirtualMachineManager.java)를 참고하며 대상 제품의 정확한 system SDK로 검증해야 합니다. 이미지 worker도 설치 직전 manifest 잠금과 삭제 표시를 확인하므로 진행 중인 다운로드가 삭제된 저장소를 다시 만들지 않습니다.
