# Verified Rootless work measurements

This harness implements the local-work measurement contract in [ROOTLESS_AGENT_PERFORMANCE.md](../../../docs/ROOTLESS_AGENT_PERFORMANCE.md). It runs real installed Git, rg, Python, dpkg and apt programs against disposable inputs. It does not download tools, inspect authentication, modify a user's working tree, install into the distribution's package DB, or claim that a local tool replay is a completed Codex/Claude task.

`run.py` is the outer orchestrator; `worker.py` runs **inside the selected runtime**. An Android application can launch the bundled worker with its existing `ProcessBuilder`/Rootless command and supply the same job JSON, without running nested PRoot or requiring an Android-native Python orchestrator. The outer Python driver is useful on hosts that can execute both selected runtime launchers directly. It never silently substitutes a native launcher when a Rootless launcher fails.

## Inputs and workloads

`prepare` creates default 10,000/100,000-file repositories and 1,000/10,000-file versioned `.deb` packages. Repositories have fixed shallow/deep paths, tracked source, ignored dependencies, an untracked file, a changed tracked file, a relative symlink and a small Python project. Their archive and Git commit hashes, exact native Git/rg output hashes, and preparation-tool versions are recorded. `rg --no-ignore` intentionally includes dependency/hidden files while excluding `.git` and the measured project's generated `build` directory. Normal rg keeps its normal ignore behavior.

Preparation explicitly packs Git objects only in its newly created disposable fixture before archiving; automatic GC remains disabled. This removes an unnecessary loose-object tree from extraction without changing HEAD or the 10k/100k working files. It is a preparation-cost reduction, not a runtime speed result. The operator's source repository is never repacked or garbage-collected.

A real repository is an **explicit** `--repository PATH --revision FULL_COMMIT` input. `git archive` reads committed content only; no `.git` configuration, untracked files or authentication files are copied. `--repository-path` narrows it to a committed subtree, which is recorded as partial repository evidence. Preparation adds a clearly named `bench_project` and commits the disposable fixture without changing the original repository. No real repository is guessed when this input is missing.

| Case | Measured operations | Independent output checks |
| --- | --- | --- |
| `dpkg-1000`, `dpkg-10000` | unpack/configure v1, unpack/configure v2, remove | Every payload byte, file count, modes, mtimes, hardlink inode/nlink, symlink and private package DB |
| `apt-1000`, `apt-10000` | local install, upgrade, remove with empty sources and `--no-download` | Same checks; isolated APT state/cache/config and dpkg root, normal fsync retained |
| `offline-packages` | Real explicit `.deb` unpack and removal | Prepared payload/mode/time/link/DB oracle; maintainer-script/special-node packages are skipped, dependency configuration is **not** claimed |
| `repository:synthetic-10000`, `repository:synthetic-100000`, `repository:pinned` | Git status/diff; rg list/search with normal and widened ignore settings | Exact outputs from the prepared native oracle, bytes and exit status |
| `python-verify:synthetic-10000` | Atomic small correction, standard-library imports, three selected unit tests, dependency-aware incremental Python compilation, Git diff | Corrected source hash, all three tests, exactly `calc.py` rebuilt among 65 sources, build manifest and diff |
| `local-replay:synthetic-10000` | Investigate → search → read selected files → edit → Git → tests/build | All preceding output/content checks; explicitly **local replay**, no model/network task |
| `two-workspaces:synthetic-10000` | Two independent replays concurrently, cancel/reap a tool tree | Both final sources/tests, waited descendant disappearance, FD count, successful tests after cancellation |

Other prepared sizes can be selected explicitly for small correctness tests. Missing guest tools, missing pinned repository/offline inputs, and unavailable authentication gates are recorded as `skipped`, never `passed`. Package cases require the selected Rootless fake-root identity. The ordinary-UID native profile skips them; an explicit installed `fakeroot` control can validate the harness on a development host, but is not Rootless performance evidence.

The real-package input is optional: repeat `--offline-deb /explicit/cache/package.deb`. The harness inspects only those selected archives. It refuses to execute their maintainer scripts in a host scratch directory; a fully provisioned independent Debian root and scripts/configuration/recovery remain a separate product integration gate. npm offline-cache/pip wheelhouse installation is not implemented by these cases.

## Prepare and run

```sh
python3 tools/rootless/workloads/run.py prepare --output /tmp/rootless-work-bundle
python3 tools/rootless/workloads/run.py run \
  --bundle /tmp/rootless-work-bundle \
  --baseline /absolute/baseline.json --candidate /absolute/candidate.json \
  --pairs 10 --output /tmp/rootless-work-results
```

Copy [rootless-profile.example.json](rootless-profile.example.json), replace its paths and retain the **actual existing** `RootlessAccelerationProfile` options in both profiles. The existing stdio option must match the selected APK; the example does not turn it on. Change only the intended candidate input. Engine/loader and enabled DSO artifacts are hashed. `command` is an argv array, never a shell fragment. Recognized substitutions are `{python}`, `{host_bundle}`, `{host_workspace}`, `{guest_bundle}` and `{guest_workspace}`. The driver appends `--job GUEST_WORKSPACE/job.json` to that command. Each invocation binds a fresh workspace.

The process needs the same host/device context as its runtime executable. Android Bionic APK executables are not interchangeable with host glibc PRoot builds. On Android, launch `/usr/bin/python3 -B /benchmark/worker.py --job /work/job.json` under the existing application-owned Rootless profile and collect its single `rootless_workload` JSON envelope. A job has `{"schema":1,"case":"local-replay","repository":"synthetic-10000","bundle":"/benchmark","work":"/work"}`. Preserve the real process status as well as that envelope.

The default local comparison uses at least **10 alternating pairs** (`baseline,candidate` then `candidate,baseline`). `--case` can repeat to select relevant work. First invocation per case is labeled separately. Preparation and precondition checks warm the cache; no run is labeled cold. The output includes monotonic elapsed times, per-phase first stdout, median/p95, paired absolute savings and absolute median savings. Small-sample p95 is explicitly exploratory. A failed or skipped attempt prevents that case from qualifying as a complete comparison; its failure is retained and never averaged as zero time.

`--diagnostic` is a separate run with `PROOT_PERF=1`; it is not eligible for timing adoption. For an `env -i` guest command, add `PROOT_FASTSTAT_REPORT=1` explicitly to its diagnostic copy if guest DSO reports are needed. Counter parsers retain individual `proot-perf:`/JSON faststat reports and label them as covering whole processes, including setup/verification. They do not attribute whole-process counters to a timed unpack phase. Guest/tracer CPU is not separately available from Python's `wait4`: reported CPU/RSS/context switches cover the launched process and kernel-accounted waited descendants. CLI JSON arrival or the first stdout byte is not claimed to be the exact time of a valid semantic result.

Generated artifacts are `results.json`, each run's `result.json`, individual stdout/stderr logs and `reproduce.txt`. Package installs have disjoint measured command intervals; `measured_ms` sums those intervals and reports setup/verification/whole workflow separately. Concurrent intervals use one enclosing wall time rather than summing overlapping sessions. Source trees/package roots are cleaned outside timing by default; use `--keep-workspaces` to retain them. Actual authenticated task workspaces are retained for review.

Tool metadata/version probes run after the workload, outside both command and worker workflow timing. Forking a version probe before fixture extraction would revoke the candidate parent's temporary-FD eligibility, adding unrelated tracing to preparation. Both engines use the same revised order; archive hashes, extraction safety and actual output checks remain mandatory. The worker workflow interval includes extraction/setup/verification, but excludes runtime launch, metadata probes and the outer Android scratch cleanup. Host `run.py` records cleanup separately; device batches do not measure that cleanup interval.

### Collect an Android device batch

Run [device-batch.sh](device-batch.sh) under the selected app UID using its explicit runtime/bundle/job inputs, then pull its result directory. [collect-device.py](collect-device.py) checks the pulled artifacts without exposing APK, cache, command or device-serial paths in its output:

```sh
python3 tools/rootless/workloads/collect-device.py \
  --input /explicit/pulled-device-results --output /tmp/device-summary.json \
  --pairs 10 --case dpkg-1000=dpkg-1000 \
  --case repo-10000=repository:synthetic-10000 \
  --timing-instrumentation-disabled
```

Declare **every expected job in the batch's case order**, using its JSON filename without `.json` as the left-hand name. The collector never infers the intended number of attempts from successful logs. `--metadata PATH` alternatively accepts `{"schema":1,"pairs":10,"cases":[{"name":"dpkg-1000","case":"dpkg-1000"}],"timing_instrumentation":"disabled","smoke":false}`. For a smaller correctness batch, explicitly use `--smoke`. The instrumentation declaration must describe the actual selected runner; diagnostic reports independently disqualify timing comparisons.

Every attempt must have exactly one worker envelope, a matching real exit in `status.tsv`, valid measured phases and output-verification evidence. Missing/duplicate attempts, fatal guest signals, timeouts, a missing `done` marker, malformed input hashes and incorrect alternating order remain failures. The compact report keeps the engine/loader/DSO and manifest hashes, Android API/SELinux state, first-invocation labels and the same median/p95/absolute-savings calculations as the host driver. It copies only approved fields and log basenames; inspect raw logs separately for detailed failure diagnosis. CLI evidence remains version/help only, and actual authenticated agent execution is explicitly skipped.

Guest facts retain recognized distribution IDs/numeric versions, bounded kernel version components, known tool version formats, executable SHA256/ELF/linkage details and the explicitly allowed `/lib/ld-linux-aarch64.so.1` interpreter. Other interpreter paths, hostnames, UIDs, serials and environment values are omitted. Existing CLI probe envelopes contain the version output digest rather than its text: the collector preserves that digest and binary metadata and marks the CLI version string as unrecorded instead of guessing it.

The collector also preserves bounded output byte counts/digests and numeric per-command `wait4` CPU/RSS/context-switch evidence. Phase resource summaries retain missing sample counts instead of inventing zero usage. These counters do not separate tracer from guest CPU; no whole-workflow CPU or sum of process peak RSS is inferred. Parallel workspace subphases remain separate from their enclosing wall interval.

The [2026-09-30 Git preload diagnostic](results/git-preload-diagnostic-2026-09-30.json) records three warm repetitions on the 10k fixture with the tested pre-restart-fix candidate. A command-only `-c core.preloadIndex=false` reduced candidate status/diff medians from 288.2/265.3 ms to 144.5/124.9 ms; baseline with the same override measured 147.1/123.9 ms. Candidate stat fast-path hits recovered from 11/9 to 9,224/9,221, consistent with Git preloading triggering clone-time `pin_safe` revocation.

Original baseline defaults remained faster at 78.9/55.2 ms. This is evidence for a possible mitigation of the safety change's Git regression, not an improvement over the original default. No user Git configuration or product setting was changed. Both status and diff were warmed before measurement, engine order was not counterbalanced, and three diagnostic samples cannot replace the final ten alternating pairs; default distribution inputs and opt-in candidates retain separate acceptance gates.

## Local validation

The [Debian workload record](results/android-debian-workloads-2026-09-30.json) retains one complete correctness pair across all twelve jobs. The long all-case ten-pair batch was interrupted after user feedback about duration; incomplete attempts are not successful timing samples. The [initial preparation record](results/android-initial-preparation-2026-09-30.json) preserves its separate completed two-pair subset. Different probe orders and manifest hashes are not merged.

The focused [dpkg comparison](results/android-dpkg-1000-2026-09-30.json) completed ten alternating pairs: twenty real exits and content/database/removal checks passed. Measured-command medians were 3,304.50/3,327.32 ms for baseline/candidate, with p95 13,489.50/14,121.46 ms; no clear wall-time improvement was established. The 100k single-pair command interval increased from 33.97 to 54.79 seconds. The [native package control](results/android-native-package-2026-09-30.json) passed with guest preload on/off; tracer flags remained enabled in both.

The [FD timestamp diagnostic](results/android-fd-utimens-diagnostic-2026-09-30.json) used identical final candidate bytes with only the new timestamp flag off/on: ten thousand raw FD calls lost ten thousand entry stops. This isolated diagnostic is not an installation or agent speed claim. The candidate remains opt-in and fails the default-adoption performance gate. Select explicit `--case` values for focused validation; regenerating a 100k workspace for every sample is substantial preparation work even outside measured command intervals.

```sh
python3 -m unittest discover -s tools/rootless/workloads -p 'test_*.py' -v
python3 tools/rootless/workloads/run.py prepare \
  --output /tmp/rootless-work-smoke --repository-count 100 --package-count 10
python3 tools/rootless/workloads/run.py run \
  --bundle /tmp/rootless-work-smoke \
  --baseline tools/rootless/workloads/local-profile.json \
  --candidate tools/rootless/workloads/local-profile.json \
  --case local-replay:synthetic-100 --case two-workspaces:synthetic-100 \
  --pairs 1 --smoke --output /tmp/rootless-work-smoke-results
```

These are real tool/file/cancellation checks with an explicitly labeled native control, not an engine speed comparison. `--smoke` permits fewer than 10 pairs only under that correctness scope. The tests inject an rg that exits zero with incorrect output, a launcher that exits zero without a guest result, an output-closed hanging child and an escaping archive; these must fail. With an already installed working `fakeroot`, `ROOTLESS_WORKLOAD_PACKAGE_TEST=1` enables actual dpkg/apt install-upgrade-remove control tests. A host sandbox that rejects `chown` even under fakeroot is an environment failure, not a package compatibility pass.

## CLI and authenticated tasks

Default support probes run only installed `codex`/`claude` `--version` and `--help` in a disposable HOME, with inherited CLI config-home/API-key variables removed. They record executable hash/ELF/interpreter information and missing CLIs separately. Version/help success is never treated as authenticated support. `--no-cli-probe` skips this stage explicitly.

An actual run is disabled unless both `--enable-authenticated-run` and `--agent-config PATH` are supplied. The operator must already have provisioned credentials and verified current CLI help. The harness does not find/copy authentication, request login or automatically disable an agent sandbox. A task config provides:

```json
{
  "name": "codex",
  "argv": ["codex", "exec", "--json", "Fix bench_project/calc.py so total returns the exact sum. Run the existing selected tests and change only that file."],
  "inherit_environment": ["CODEX_HOME"],
  "expected_files": {"bench_project/calc.py": "SHA256_OF_EXPECTED_FIXED_SOURCE"},
  "allowed_changed_files": ["bench_project/calc.py"],
  "verify_argv": ["python3", "-B", "bench_project/run_tests.py"],
  "verify_stdout_sha256": "SHA256_OF_EXPECTED_TEST_JSON_WITH_NEWLINE",
  "timeout_seconds": 600
}
```

Provide an explicitly provisioned CLI config directory through the selected profile/environment; `inherit_environment` contains **key names only**, so credentials are not copied to jobs/results. Inline `environment` values are rejected before a job is written. The equivalent Claude argv can use its verified `-p --output-format stream-json --verbose` interface. Fix model, reasoning/permissions/plugin/MCP settings in operator-owned configuration before comparing; this harness does not choose them. Both return status and JSON event syntax, an initially unsatisfied task, exact final file hashes, allowed file changes (including ignored/untracked files) and independent test stdout/exit are checked. Output paths must stay inside the fixture. Raw agent logs remain local artifacts; inspect them before sharing. Session resume, interactive PTY and authenticated agent cancellation remain separate gates. This implementation alone does not establish those gates or either CLI's real task success.
