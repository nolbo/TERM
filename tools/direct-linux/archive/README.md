# Direct Linux instrumentation archive

Direct Linux was retired by user direction on 2026-09-30. The unchanged Kotlin
files in [androidTest](androidTest) retain the instrumentation that produced the
historical experiment records. They are outside every Gradle source set and are
not compiled, packaged, discovered or executed by the current Android test APK.
The current executable policy rejects both `direct-linux-probe` and
`direct-linux-loader`, even if files bearing those names are present in an APK.

The pre-retirement source snapshot is commit
`4658d1678545f7a787efafbb00126377f0643e20`. Older result documents retain the exact
payload hashes and policy limits of their own runs. Preserving this source does
not make its recorded commands runnable against the current APK, or convert
unfinished C01–C45 work into completed compatibility.

Rootless regression work may reuse the generic PTY interaction helper in
[DirectLinuxPtyScenario.kt](androidTest/DirectLinuxPtyScenario.kt), the fixture
manifest checking/extraction in
[DirectLinuxLoaderTest.kt](androidTest/DirectLinuxLoaderTest.kt), and Linux guest
programs retained one directory above. Such checks need an independent Rootless
runner and evidence; they must not revive the Direct loader or relabel old Direct
timings as new Rootless measurements.

The retained build and APK packaging scripts require `--archive-reproduction`.
That opt-in permits historical artifact preparation only. It does not restore
the retired instrumentation to active source sets or change executable policy.
