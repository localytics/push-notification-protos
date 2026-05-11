# Java example

A minimal Java client for `PushService.StreamPush`, built with Gradle 9.x and the [`protobuf-gradle-plugin`](https://github.com/google/protobuf-gradle-plugin).

## Layout

```
java/
  gradlew, gradlew.bat            # Gradle wrapper (use these, not system `gradle`)
  gradle/wrapper/                 # pinned to Gradle 9.5.0
  build.gradle
  settings.gradle                 # applies the Foojay JDK-resolver plugin
  src/main/proto/push.proto       # symlink → ../../../../push.proto
  src/main/java/com/example/pushclient/PushClient.java
```

`src/main/proto/push.proto` is a relative **symlink** back to the repo-root proto, so there's exactly one source of truth. The protobuf-gradle-plugin picks it up via its default source set; no `srcDir` override needed.

(The symlink keeps `srcDir` to a dedicated directory. Pointing it at the repo root causes the plugin to conflict with its own `build/extracted-include-protos/` cache.)

## Prerequisites

- A JDK on `PATH` capable of launching Gradle 9 (Gradle 9 itself requires JDK 17+ to start). Any of Homebrew `openjdk`, `openjdk@17`, `openjdk@21` will work; the wrapper picks whatever `JAVA_HOME` (or `java` on `PATH`) points at to bootstrap.
- That's the only prereq — the actual build target is JDK 17, which the **Foojay toolchain resolver** (configured in `settings.gradle`) will auto-download from [Adoptium](https://adoptium.net/) on first use and cache under `~/.gradle/jdks/`.

You do **not** need to install Gradle separately; the checked-in wrapper handles it.

## Run

```bash
export PUSH_API_KEY=...
export PUSH_API_SECRET=...
export PUSH_APP_ID=...

./gradlew run
```

First run downloads Gradle (~150 MB) and JDK 17 (~180 MB) into the per-user Gradle cache. Subsequent runs are fast.

## Notes on the generated code

`push.proto` declares `package push;` and does *not* set `option java_package`, `option java_outer_classname`, or `option java_multiple_files`. That means:

- Generated message classes are nested inside a single outer class: `push.Push.Alert`, `push.Push.PushRequest`, etc.
- The gRPC service stub is generated separately at `push.PushServiceGrpc`.

`PushClient.java` imports both.
