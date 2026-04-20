# Lectern Sync

Open-source client-side tooling for [Lectern](https://thelectern.app) — a self-hosted Minecraft Java Edition server management platform.

This repository contains two small Java programs that ship inside every Lectern modpack:

| Component | Purpose | Size |
|-----------|---------|------|
| **`lectern-sync-updater.jar`** | Pre-launch bootstrap. Checks the relay for a newer sync jar and downloads it before handing off. | ~5 KB |
| **`lectern-sync.jar`** | Fetches the server's mod manifest, syncs local mods, updates `servers.dat`, then exits so the game can launch. | ~35 KB |

Both are zero-dependency, Java 8 compatible, and run as pre-launch commands in Minecraft launchers (Prism Launcher, MultiMC, etc.).

## How it fits together

```
Player launches instance
  → Pre-launch: java -jar lectern-sync-updater.jar
    → Check relay for newer lectern-sync.jar (compare SHA-256)
    → Download if different
    → Exec: java -jar lectern-sync.jar
      → Fetch manifest from relay (or direct fallback)
      → Sync mods (download new, remove old)
      → Update servers.dat
      → Exit
  → Minecraft launches
```

The updater is intentionally minimal so it rarely needs to change. When the sync tool gets a bug fix or feature, the updater pulls the new jar automatically on next launch — existing player instances never go stale.

## Building

Each component is a standalone Gradle project:

```bash
cd sync-tool && gradle build
cd sync-updater && gradle build
```

Outputs:
- `sync-tool/build/libs/lectern-sync-1.0.0.jar`
- `sync-updater/build/libs/lectern-sync-updater.jar`

## CI/CD

GitHub Actions auto-builds and uploads jars to the Lectern relay on every push to `main`:

- `sync-tool/**` changes → builds and uploads `lectern-sync.jar`
- `sync-updater/**` changes → builds and uploads `lectern-sync-updater.jar`

Set `STATIC_UPLOAD_SECRET` in repo secrets (the same value configured as the `STATIC_UPLOAD_SECRET` worker secret on the Lectern relay).

## License

MIT — see [LICENSE](LICENSE).
