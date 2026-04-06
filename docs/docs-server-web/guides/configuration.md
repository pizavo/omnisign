---
sidebar_position: 1
---

# Configuration

The server module shares the same configuration system as the CLI and Desktop apps.
Configuration is stored as JSON at the platform-specific location:

| OS      | Path                                          |
|---------|-----------------------------------------------|
| Linux   | `~/.config/omnisign/config.json`              |
| macOS   | `~/Library/Application Support/omnisign/config.json` |
| Windows | `%APPDATA%\omnisign\config.json`              |

## Environment variables

For containerized deployments, all configuration options can be set via environment
variables using the `OMNISIGN_` prefix:

| Variable                      | Description                     |
|-------------------------------|---------------------------------|
| `OMNISIGN_TIMESTAMP_URL`      | Default TSA URL                 |
| `OMNISIGN_TIMESTAMP_USERNAME` | TSA HTTP Basic username         |
| `OMNISIGN_TIMESTAMP_PASSWORD` | TSA HTTP Basic password         |
| `OMNISIGN_HASH_ALGORITHM`     | Default hash algorithm          |
| `OMNISIGN_SIGNATURE_LEVEL`    | Default signature level         |

## Docker configuration

Mount a configuration file into the container:

```bash
docker run -d -p 8080:8080 \
  -v /path/to/config.json:/root/.config/omnisign/config.json:ro \
  omnisign-server
```

Or use environment variables:

```bash
docker run -d -p 8080:8080 \
  -e OMNISIGN_TIMESTAMP_URL=https://tsa.example.com/tsr \
  -e OMNISIGN_SIGNATURE_LEVEL=PADES_BASELINE_LTA \
  omnisign-server
```

## Server port

The server listens on port **8080** by default. This is defined in the shared `Constants.kt`
and can be changed by modifying the server configuration or using Docker port mapping:

```bash
docker run -d -p 443:8080 omnisign-server
```

