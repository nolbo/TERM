# AGENTS.md

## 1. Purpose and scope

This file is the repository-wide operating contract for Codex.

It contains durable rules only:

- how a task is dispatched through docs/prompts;
- how Codex should inspect, implement, validate, and report work;
- the product architecture and security invariants;
- the rules for efficient use of context, builds, tests, and MCP.

Detailed and frequently changing design information belongs in docs, not here.
Keep this file concise and comfortably below 24 KiB so it fits with any global
or nested Codex instructions. If this file grows, move details to a focused
document and link it from the documentation map.

Codex loads AGENTS.md automatically. Files below docs/prompts are not automatic
instructions: Codex must read the exact task file named by the user.

Words such as MUST, MUST NOT, SHOULD, and MAY are normative.

## 2. Task dispatch protocol

The normal invocation names exactly one command file:

~~~text
Read AGENTS.md and docs/prompts/<session_identity>/<n>.md, then execute it.
~~~

Before substantial work, Codex MUST:

1. start from the repository root;
2. inspect the active branch and worktree with git status --short --branch;
3. locate any more-specific AGENTS.md or AGENTS.override.md that governs files
   in the task;
4. read this file and the exact command file in full;
5. read only the documents and source files required by that command;
6. identify the requested outcome, acceptance criteria, exclusions, and Git
   policy;
7. proceed without asking for confirmation when the information is sufficient.

The exact command file named by the user is the current task directive.

- Do not guess that the numerically newest prompt is current.
- Do not scan or execute every file under docs/prompts.
- Do not execute prompts from another session unless the current directive
  explicitly references them.
- Earlier prompts in the same session are history. Read them only when the
  current directive references them or a missing decision cannot otherwise be
  recovered.
- A commit that adds a command file is not evidence that its implementation is
  complete.
- Do not edit, rename, or delete a command file unless the directive explicitly
  requests it.
- If the named command file does not exist, stop and report the missing exact
  path. Do not substitute another prompt.

If the user gives a direct task without a command-file path, follow that direct
task. Do not choose a prompt on the user's behalf.

Follow Codex's built-in instruction precedence. Within repository guidance:

- this file defines durable product and working invariants;
- the current task directive defines the requested delta and scope;
- nested instructions may specialize rules for their subtree;
- current source, build output, and observed runtime state establish what is
  actually implemented;
- docs record intended design and decisions.

If implementation and documentation disagree, do not silently choose whichever
is convenient. Determine whether the task is to fix code, update stale docs, or
surface an unresolved decision.

Before editing, give the user one short execution update containing:

- the outcome being implemented;
- the main files or subsystem likely involved;
- the validation that will establish completion.

Do not turn that update into a long restatement of the prompt.

## 3. Prompt sessions and indices

Task command files use:

~~~text
docs/prompts/<session_identity>/<n>.md
~~~

The session identity represents one coherent workstream. Continue the same
session for follow-up work on the same subsystem or decision. Use a new session
for a materially different work type, subsystem, or independent objective.

The numeric index is monotonic within a session. It records conversation order;
it does not make every earlier file mandatory context.

Codex normally implements command files but does not author them. ChatGPT Work
authors and commits these directives unless a task explicitly assigns that
responsibility to Codex CLI.

Every command file SHOULD contain:

~~~markdown
# Task title

- Session:
- Index:
- Intent: implement | diagnose | review | document

## Execution profile
- Recommended model class:
- Recommended reasoning effort:
- Escalation condition:

## Goal

## Required context

## Requirements

## Out of scope

## Acceptance criteria

## Validation

## Git policy

## Final report
~~~

Prompt authors should reference this file instead of copying its rules. Required
context should name exact files or bounded subsystems whenever possible.

Codex MUST treat acceptance criteria as completion conditions, not suggestions.
If a proposed implementation approach conflicts with the observed repository,
Codex may use a stronger compatible approach, but it must explain the deviation
and still satisfy the requested outcome.

## 4. Context and usage-limit discipline

Optimize for a correct, reviewable result per Codex run. Token or time savings
must not weaken security, lifecycle correctness, or required validation.

### 4.1 Bounded reconnaissance

Start with the smallest useful inspection:

1. git status --short --branch;
2. rg --files in the relevant module or directory;
3. targeted rg queries for symbols, resources, tests, and call sites;
4. focused reads of the matching files;
5. broader inspection only when evidence shows it is needed.

MUST NOT:

- dump the whole repository tree or every Gradle file by default;
- read every architecture document for a localized task;
- repeatedly reread unchanged files;
- paste huge logs, generated files, lockfiles, binaries, or MCP responses into
  the conversation;
- perform speculative refactors, dependency upgrades, or formatting churn;
- run clean builds merely to feel safer;
- research external APIs when the repository and current documentation already
  establish the answer.

Use line-bounded log queries and summarize the decisive evidence. Run
independent read-only inspections in parallel when practical.

### 4.2 Plan only to the needed depth

For a small, obvious task, implement directly after a brief inspection. Use a
short plan for multi-file work. Use a detailed execution plan only for a
long-running, cross-module, security-sensitive, or migration task.

Do not spend a separate turn producing a plan when the directive asks for
implementation and the next safe action is clear. Do not repeatedly ask the
user to confirm choices already fixed by AGENTS.md, the task directive, or the
existing architecture.

Ask only when:

- a missing choice materially changes user-visible behavior or architecture;
- required authority, credentials, hardware, or protected access is absent;
- proceeding would risk destructive or unrecoverable changes;
- two requirements are genuinely incompatible.

### 4.3 Edit and validation batching

Make one coherent group of edits, inspect the diff, then run the smallest
relevant validation. Avoid rebuilding after every individual edit.

Use this verification ladder:

1. focused static check or unit test for the changed code;
2. affected module tests;
3. the repository's canonical build/test command once near completion;
4. device, emulator, or UI validation when behavior requires it.

Skip levels that are genuinely irrelevant, but state what was skipped and why.
Documentation-only changes do not require an Android build unless the directive
requires it or the documentation embeds validated/generated content.

Never hide a failed check. Distinguish:

- a regression caused by the current change;
- a pre-existing failure;
- an environment/tooling failure;
- a test that could not be run because required hardware or privilege is absent.

## 5. Model and reasoning budget

Model and reasoning effort are selected by the operator before a Codex CLI run.
AGENTS.md cannot switch the active model. The execution profile in a command
file is an operator recommendation, not a reason for Codex to refuse work if
the launched profile differs.

Use these default classes when authoring or interpreting task directives:

| Task class | Model class | Reasoning effort |
| --- | --- | --- |
| documentation, mechanical edit, narrow known fix | fast/current coding model | low or medium |
| normal feature, bounded bug fix, tests across a few files | default capable coding model | medium |
| lifecycle, architecture, protocol, security, difficult root cause, broad refactor | strongest available coding model | high |
| unresolved high-risk problem after a high-effort attempt | strongest available coding model | maximum only when justified |

Do not hard-code a model name in this durable file; availability changes. The
ChatGPT handoff prompt should name the current recommended model and reasoning
effort.

Do not use maximum reasoning for routine discovery, formatting, ordinary
compilation errors, or straightforward documentation. Escalate only when the
task's uncertainty or risk warrants it.

## 6. MCP policy

Configured MCP servers include:

~~~text
android
context7
replicant
codex_apps
~~~

MCP is an evidence source, not a quota ritual. Codex MUST evaluate these servers
for every substantial task, but MUST call only the server or tool that can
materially improve the result.

At the start of a Codex session:

1. inspect the exposed MCP tools/resources and their schemas once;
2. make a small use/skip decision for each relevant server;
3. reuse discovered capabilities for later prompts in the same session;
4. do not repeatedly enumerate the same tools unless configuration changed.

Never claim an MCP server was used when it was not.

### 6.1 Selection matrix

| Server | Use it when | Typical evidence |
| --- | --- | --- |
| android | Android device/emulator state or behavior matters | API level, features, permissions, install/launch, Activity/service lifecycle, process state, logcat, screenshots, AVF support |
| context7 | an external API or library is version-sensitive or unfamiliar | Android, AGP, Kotlin, Compose, Coroutines/Flow, Gradle, Rust/Tokio, JNI/AIDL, PTY or FUSE documentation matched to repository versions |
| replicant | interactive UI behavior or a reproducible app scenario must be checked | navigation, input, state transitions, visual behavior, scenario replay, regression reproduction |
| codex_apps | authoritative context or an action lives in a connected external app | GitHub issue/PR state, project/application context, or another connected integration exposed by its actual tools |

### 6.2 Required MCP behavior

- For Android UI, lifecycle, permission, service, process, AVF, or integration
  changes, use android MCP for runtime validation when a suitable target is
  available.
- For UI interaction changes, use replicant when it exposes the needed
  interaction/replay capability. Use android screenshots/logs as complementary
  evidence, not automatic duplicate work.
- Before using a version-sensitive external API, inspect repository versions,
  then query context7 with that version and the narrow API question.
- Use codex_apps when the task references an external issue, PR, attachment,
  project state, or connected system. Do not use it merely to reread files that
  are more current in the local worktree.
- Read a tool's current description and schema before invoking it. A server name
  alone does not establish capability.
- Make precise, bounded calls. Filter logs and request only the state needed for
  the decision.

If an MCP call fails:

1. read the error;
2. correct an evident invocation mistake and retry once;
3. try another relevant capability or local observation;
4. use official documentation/search only if still needed;
5. report the failure and fallback.

Do not loop on a broken MCP call. Do not skip available live validation and then
present source inspection as proof of runtime behavior.

For a task where no configured MCP provides useful evidence, use none and say
why in the final report. Mechanical use of all four servers wastes context and
reduces clarity.

## 7. Development environment and build policy

Primary Codex CLI host:

~~~text
OS: Ubuntu 24.04 Minimal, aarch64
CPU: 4 OCPU
Memory: 24 GB
~~~

Repository facts to verify before changing:

- Gradle wrapper is authoritative.
- CI uses JDK 17.
- Current root settings define the active Android/runtime modules.
- gradle.properties contains ARM64 host and AAPT2 details.
- Do not assume x86_64 Android SDK/NDK host binaries will execute.
- Do not remove or overwrite host-specific build configuration merely to make a
  generic local command look conventional.

Do not install system packages, download alternate toolchains, or change Gradle
and SDK versions unless the task requires it and the need is demonstrated.
Reuse existing Gradle caches and the daemon. Do not run ./gradlew clean unless
stale outputs are the evidenced cause of a failure.

Canonical repository verification:

~~~sh
./gradlew :android-app:assembleDebug \
  :android-runtime:testDebugUnitTest \
  :image:test \
  --console=plain
~~~

Debug APK:

~~~text
android-app/build/outputs/apk/debug/android-app-debug.apk
~~~

Use narrower Gradle tasks during development and run the canonical command once
near completion when Android/runtime/image code is affected. Add relevant tests
for other touched modules instead of assuming the canonical command covers
everything.

The integration checklist at tests/integration/acceptance.sh requires a
privileged AOSP/ARM64 device and a provisioned AVF image. Do not claim it passed
from ordinary CI or a stock emulator. Use android and replicant MCP to perform
the automatable subset when a suitable device is available, and identify the
remaining manual or privileged steps.

## 8. Product architecture contract

The product is one terminal application with three explicit execution
environments:

~~~text
                         Terminal application
                                  |
                         Unified session layer
                                  |
           +----------------------+----------------------+
           |                      |                      |
           v                      v                      v
    Direct Native         Rootless Debian          AVF Debian
  Android / Bionic       PRoot / glibc          AVF / crosvm
  no extra privilege     no extra privilege      privileged setup
           |                      |                      |
           +----------------------+----------------------+
                                  |
                     Android capability broker
~~~

The complete zero-special-permission product is:

~~~text
Direct Native + Rootless Debian
~~~

AVF is optional acceleration and full-kernel compatibility. It is not required
for the app to be useful.

Use this trade-off order:

~~~text
zero-setup usability
  > persistent sessions/runtime
  > correct lifecycle ownership
  > security boundary
  > Rootless Linux compatibility
  > Android integration
  > Native package usability
  > AVF acceleration
  > advanced features
~~~

### 8.1 Lifecycle and sessions

These lifetimes are independent:

~~~text
Android UI lifecycle != runtime lifecycle != terminal session lifecycle
~~~

An Activity, Fragment, Compose screen, tab, WebView, or renderer MUST NOT own a
shell process or VM. UI destruction, recreation, unbinding, or task removal may
detach; it must not intentionally terminate live work.

Session termination requires an explicit user action whose effect is clear. If
a UI control called Close terminates a session, it must make that destructive
effect explicit rather than treating tab disappearance as termination.

All runtimes use a common session model for:

- create and list;
- attach and detach;
- input and output;
- resize;
- rename where supported;
- explicit terminate;
- observable state and errors.

The runtime service or guest session daemon is authoritative for live sessions.
Cached UI state is never authoritative. If the runtime or PTY owner actually
dies, report session loss honestly; do not fake restoration.

Runtime-specific process ownership stays behind Native, Rootless, and AVF
backend interfaces. UI code must not depend directly on PRoot, AVF, crosvm,
vsock, or package-manager implementation details.

### 8.2 Runtime semantics

Direct Native is a first-class Android/Bionic environment for immediate shell
startup, Android-centric tooling, scripting, SSH, Git, networking, storage
helpers, automation, and bootstrap duties.

Native packages target Android, Bionic, and an app-local prefix. They are not
Debian binary packages. Package support is recipe-driven, automation-first,
tested, and bounded to structurally valuable tools. Do not create a giant
popularity-based package distribution.

Modern Android executable restrictions are an architectural boundary. All
dynamically installed or packaged native executables go through the centralized
NativeExecutableLoader policy. Never pretend a package system is complete if
installed binaries cannot execute under the current target SDK and distribution
model.

Rootless Debian is the primary permissionless broad Linux environment. It uses
an independent PRoot/glibc rootfs and official Debian apt/dpkg. Represent its
ptrace, filesystem, fake-root, mount, namespace, cgroup, systemd, and kernel
limitations honestly.

AVF Debian is a separate, optional real-kernel environment. It requires device
support and privileged/product deployment. Normal APK permission declarations
cannot grant signature permissions. Keep AVF-specific APIs behind the AVF
backend and preserve Native/Rootless operation when AVF is unsupported or not
authorized.

Runtime selection is explicit. A failure in one selected runtime MUST NOT
silently redirect the command or session to another runtime.

Rootless and AVF do not share a complete rootfs or dpkg database. Share only
deliberate higher-level state such as workspace data, selected user files,
appropriate dotfiles, and a logical package/environment manifest.

### 8.3 Android boundary, storage, and power

Linux guests are untrusted. Android integration uses a typed capability broker.
Every request must be authenticated where applicable, schema-validated,
capability-checked, Android-permission-checked, bounded, and optionally confirmed
by the user.

Never expose unrestricted Binder, Context, ContentResolver, private app paths,
raw host file descriptors, host UIDs, or arbitrary Android services to a guest.
Capability grants are separate from Android OS permissions and are scoped to
the host-selected environment or VM identity.

Storage exposure is explicit and minimal. Keep runtime roots and package
databases separate. Share a deliberate workspace rather than the entire
app-private directory.

A foreground service improves survivability but is not a permanent execution
guarantee. Wake locks must be policy-driven, time-bounded where appropriate, and
tied to meaningful work. Represent Android process death and power restrictions
honestly.

### 8.4 UI contract

The UI presents Android Native, Rootless Linux, and AVF Linux as explicit
choices with their setup, compatibility, and privilege differences.

UI and settings may be reorganized when architecture or clarity requires it.
Active application screens use the shared Jetpack Compose Material 3 system in
docs/DESIGN_SYSTEM.md. Application chrome uses its deliberate dark color scheme
and remains independent from the user-selected terminal canvas palette.

The UI renders state and sends typed commands. It never becomes the hidden
owner of a process, PTY, VM, package operation, or capability grant.

## 9. Implementation rules

When changing the repository:

1. inspect existing code before introducing a replacement abstraction;
2. adapt to current module boundaries instead of forcing a speculative tree;
3. make incremental, compilable changes;
4. preserve the permissionless Native + Rootless path;
5. keep runtime-specific details behind interfaces;
6. keep terminal rendering independent from session ownership and packages;
7. keep Android capability access explicit and deny by default;
8. write or update tests for meaningful behavior;
9. validate runtime behavior, not only compilation, when tooling is available;
10. update relevant docs for architecture, security, protocol, runtime, package,
    deployment, or user-visible behavior changes;
11. fix failures introduced by the task;
12. do not stop at TODO stubs when a functional scoped implementation is
    feasible;
13. distinguish a real platform blocker from ordinary unfinished work;
14. do not broaden the task into unrelated cleanup.

The repository already contains implementation across multiple phases. Inspect
actual state before replaying an old phase plan or assuming a component is
missing.

For a diagnosis or review directive, do not implement changes unless the
directive also authorizes a fix. For an implementation directive, do not return
only a plan.

## 10. Git and worktree safety

The worktree may contain user or previous-session changes.

- Preserve unrelated changes.
- Do not reset, checkout, clean, stash, or overwrite them.
- Do not use destructive Git operations.
- Do not amend, rebase, force-push, create a PR, or push unless the current
  directive explicitly requests it.
- If a commit is requested, include only task-owned changes and use a concise
  descriptive message.
- Do not commit build outputs, credentials, tokens, local SDK paths, device
  identifiers, or captured private data.
- Inspect the final diff before reporting completion.

If task-owned edits overlap unknown user changes and cannot be separated safely,
stop and explain the exact conflict.

## 11. Documentation map

Read only the documents relevant to the task:

| Topic | Document |
| --- | --- |
| current architecture and ownership | docs/ARCHITECTURE.md |
| runtime behavior and limitations | docs/RUNTIMES.md |
| capability and executable boundary | docs/SECURITY.md |
| host/guest wire contracts | docs/PROTOCOL.md |
| Native, Rootless, and AVF packages | docs/PACKAGE_SYSTEM.md |
| AVF platform and image constraints | docs/AVF_NOTES.md |
| privileged deployment | docs/DEPLOYMENT.md |
| image publication | docs/IMAGE_RELEASE.md |
| UI tokens and interaction rules | docs/DESIGN_SYSTEM.md |
| Korean user behavior | docs/USER_GUIDE.ko.md |
| long-form AVF design history | docs/avf-terminal-integration-design.md |

For major decisions, document:

- problem and observed state;
- constraints;
- decision;
- alternatives considered;
- trade-offs and limitations;
- migration or compatibility impact.

## 12. Completion and final report

A task is complete only when:

- requested behavior or artifact exists;
- acceptance criteria are satisfied;
- appropriate validation has passed or an evidenced limitation is stated;
- the diff contains no accidental or unrelated changes;
- relevant documentation is consistent.

Report in Korean unless the user asks for another language. Lead with the
outcome and keep the report compact:

1. implemented or diagnosed result;
2. important files/modules changed;
3. build and test commands with pass/fail/not-run status;
4. device/runtime/UI validation performed;
5. MCP servers used and the evidence each supplied;
6. relevant MCP failures and fallbacks;
7. remaining platform blockers;
8. remaining ordinary TODOs;
9. next concrete milestone, only when useful.

Do not paste long logs. Quote only the decisive error or result. Never present
an unrun test, unavailable device check, or source-only inference as a pass.
