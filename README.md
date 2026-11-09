# RapidSkies

Velocity + Paper 26.3 network for Java clients 1.20 → 26.3. See [PLAN.md](PLAN.md) for the full design.

| Path | Contents |
|---|---|
| `rapid-*/` | Custom plugins (Gradle multi-module, Java 25). `rapid-common` is shared code; the rest are plugin jars. |
| `build-logic/` | Gradle convention plugins (`rapid.java-conventions`, `rapid.plugin-conventions`) |
| `infra/` | Server configs, deploy tool, LuckPerms bootstrap, backups. Start with [infra/README.md](infra/README.md). |
| `plugins/` | Third-party plugin jars deployed by `infra/rsdeploy.py` |
| `assets/` | Brand assets |

## Build

```bash
./gradlew build dist     # jars in build/dist/
```

Needs only a JDK 17+ to launch Gradle; the Java 25 toolchain is downloaded automatically if
missing. Integration tests (`*IT`) start MariaDB and Redis with Testcontainers, so they need
Docker; without it they're skipped, and CI always runs them. CI (`.github/workflows/build.yml`) builds every push and pull request, uploads the jars
as an artifact, and validates `infra/`.
