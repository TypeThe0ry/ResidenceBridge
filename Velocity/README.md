# ResidenceBridge-Velocity

中文: [README_ZH.md](README_ZH.md)

This is the Velocity proxy-side subproject in the merged ResidenceBridge repository. See the root [../README.md](../README.md) for the full project documentation.

## Purpose

ResidenceBridge-Velocity only handles proxy-side forwarding. It receives plugin messages from sub-servers on `residencebridge:main`, resolves the target server name, and connects the player to that server.

Supported payload formats:

- `connect|serverId`: the new 1.2.0 format.
- `serverId`: legacy-compatible format.

## Requirements

- Java 17+
- Velocity 3.x
- Matching `ResidenceBridge` installed and configured on every sub-server

## Build

```bash
./gradlew build
```

Artifact:

```text
build/libs/ResidenceBridge-Velocity-1.2.0.jar
```

GitHub Actions builds both [../Server](../Server) and this Velocity subproject when `main` or a `v*` tag is pushed in the merged repository.
