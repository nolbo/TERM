# AGENTS.md

## 1. Purpose and scope

This file is the repository-wide operating contract for Codex.

It contains durable rules only:

- how direct user requests define task scope;
- how Codex should inspect, implement, validate, and report work;
- where authoritative architecture and security contracts live and when to consult them;
- the rules for efficient use of context, builds, tests, and MCP.

Detailed and frequently changing design information belongs in docs, not here.
Keep this file concise and comfortably below 24 KiB so it fits with any global
or nested Codex instructions. If this file grows, move details to a focused
document and link it from the documentation map.

Codex loads AGENTS.md automatically. Users deliver tasks directly in the
conversation; no repository prompt file is required.

Words such as MUST, MUST NOT, SHOULD, and MAY are normative.

## 2. Task dispatch protocol

The user's direct request defines the current task. Follow-up messages refine
the ongoing task unless the user explicitly replaces or cancels it.

Before substantial work, Codex MUST:

1. start from the repository root;
2. inspect the active branch and worktree with git status --short --branch,
   and ensure work proceeds on `dev` under the Git policy below;
3. locate any more-specific AGENTS.md or AGENTS.override.md that governs files
   in the task;
4. read this file and the user's current request in full;
5. read only the documents and source files required by that request;
6. identify the requested outcome, acceptance criteria, exclusions, and Git
   policy;
7. proceed without asking for confirmation when the information is sufficient.

Do not require, create, or select a task prompt file to begin work. Repository
documents provide context when relevant; they do not independently dispatch
tasks. Preserve historical task records unless the user requests changes.

Follow Codex's built-in instruction precedence. Within repository guidance:

- this file defines durable product and working invariants;
- the current user request defines the requested delta and scope;
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

Codex MUST treat acceptance criteria as completion conditions, not suggestions.
If a proposed implementation approach conflicts with the observed repository,
Codex may use a stronger compatible approach, but it must explain the deviation
and still satisfy the requested outcome.

## 3. Release patch notes

App base-version decisions MUST follow `docs/VERSIONING.md`. Assess the complete
unreleased scope, use the highest applicable increment, and record the reason.
Build numbers and commits alone do not trigger a base-version increase.

Every release, including prereleases, MUST have Korean patch notes in
`docs/release-notes/<release-id>.md`. Use the actual version or release tag as the
release ID, including any prerelease suffix. Create the directory when preparing
the first release under this policy.

When preparing a release, Codex MUST:

1. review the changes since the preceding release of the same artifact, or the
   included scope for an initial release;
2. write the patch notes before completing release preparation or publishing;
3. include the release ID, release date, and affected artifacts or runtimes;
4. focus on user-visible additions, changes, removals, and fixed bugs, including
   security changes when they affect users;
5. document required upgrade or migration steps, compatibility changes, and
   known limitations when applicable;
6. keep build commands, CI results, commit selection, implementation details,
   and other developer-facing information out of user-facing patch notes;
   report validation separately in the task report or an internal release record;
7. commit the patch notes with the task-owned release changes and include their
   path in the final report.

Patch notes MUST describe changes actually included in the release, not planned
work or an unfiltered commit log. Preserve notes for previous releases; correct
them only when needed for accuracy. A release task is incomplete without its
patch notes. This requirement does not itself authorize tagging, pushing, or
publishing; the Git policy and user-requested scope still apply.

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
user to confirm choices already fixed by AGENTS.md, the user request, or the
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

### 4.4 Verification budget and mandatory stop rule

Validation MUST be proportional to the changed behavior and its risk. The purpose
of validation is to establish the acceptance criteria, not to maximize confidence
after the criteria are already established.

Use the smallest sufficient verification budget:

- documentation or mechanical edits: inspect the final diff and run only a
  directly relevant syntax, link, or formatting check when one exists;
- narrow code changes: run the most focused relevant test or compile check, then
  inspect the diff;
- multi-file behavior changes: run affected-module checks and, when Section 7
  makes it applicable, the canonical verification once near completion;
- lifecycle, security, protocol, migration, or release changes: broaden
  validation only for the concrete risks introduced by the task.

After a relevant check passes, MUST NOT rerun an equivalent or broader check
unless a subsequent edit could invalidate that result. Before every additional
test, build, repository search, MCP call, device check, or documentation lookup,
Codex MUST be able to name the unresolved acceptance criterion or plausible
task-caused failure that the action can decide. If it cannot, skip the action.

MUST NOT:

- run additional builds or tests merely to gain more confidence;
- inspect neighboring modules, call sites, docs, or external sources after the
  requested behavior is established unless evidence points to a specific risk;
- duplicate runtime validation across tools when the second tool would provide
  materially equivalent evidence;
- keep searching for alternative implementations after the chosen implementation
  satisfies the request;
- expand an unrelated defect discovered during validation into the current task
  unless it blocks the requested result or creates an immediate correctness,
  security, or data-loss risk.

Passing acceptance criteria are a stop condition. Once the requested change is
implemented, the relevant validation has passed or a concrete limitation is
documented, and the final diff is clean, proceed to the final report. Do not use
remaining context, tool access, or reasoning budget for optional reassurance.

## 5. Model and reasoning budget

Model and reasoning effort are selected by the operator before a Codex CLI run.
AGENTS.md cannot switch the active model. Any execution profile supplied by the
operator is a recommendation, not a reason for Codex to refuse work if
the launched profile differs.

Use these default classes when interpreting tasks:

| Task class | Model class | Reasoning effort |
| --- | --- | --- |
| documentation, mechanical edit, narrow known fix | fast/current coding model | low or medium |
| normal feature, bounded bug fix, tests across a few files | default capable coding model | medium |
| lifecycle, architecture, protocol, security, difficult root cause, broad refactor | strongest available coding model | high |
| unresolved high-risk problem after a high-effort attempt | strongest available coding model | maximum only when justified |

Do not hard-code a model name in this durable file; availability changes.

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
3. reuse discovered capabilities for later tasks in the same session;
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

## 8. Architecture source of truth

The authoritative product architecture is `docs/ARCHITECTURE.md`. Do not keep
a second architecture description in this file.

Before designing or changing runtime ownership, session lifecycle, execution
environments, package semantics, Android capability boundaries, storage, power
management, or UI/runtime separation, Codex MUST read the relevant sections of
`docs/ARCHITECTURE.md`. Read the more specialized security, protocol, runtime,
package, deployment, and design-system documents from the documentation map only
when the task touches those contracts.

If implementation and architecture documentation disagree, follow the conflict
handling rule in Section 2 rather than silently treating either source as
disposable.

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

- All repository work MUST be performed on the `dev` branch. Before editing,
  safely switch to `dev`, preserving existing commits and uncommitted changes.
  If switching would overwrite or discard work, resolve that conflict before
  proceeding; never reset or discard changes merely to reach `dev`.
- Commit task-owned changes on `dev`. Do not develop or commit directly on
  `main`, or create another working branch, unless the user explicitly changes
  this policy. Merging or publishing `dev` still requires explicit authorization.
- Preserve unrelated changes.
- Do not reset, checkout, clean, stash, or overwrite them.
- Do not use destructive Git operations.
- Do not amend, rebase, force-push, create a PR, or push unless the current
  directive explicitly requests it.
- On completing an implementation, documentation, or configuration task, MUST
  commit its task-owned changes with a concise descriptive message. Push only
  when the current directive explicitly requests it.
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
| app version promotion criteria | docs/VERSIONING.md |
| per-release patch notes | `docs/release-notes/` (one `<release-id>.md` per release) |
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
- relevant documentation is consistent;
- for a release task, patch notes exist and match the released scope.

These bullets are also a mandatory stop condition. When they are satisfied, stop
inspecting, testing, researching, or improving unrelated code and write the final
report. Continue only if new evidence shows that a completion condition is not
actually satisfied.

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
