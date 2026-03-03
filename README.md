# mujrozhlas-dl

CLI tool and web UI for downloading audio content from mujrozhlas.cz.

## Requirements

- Java 21
- ffmpeg in PATH

## Build

```bash
./gradlew shadowJar
```

Produces `build/libs/mujrozhlas-dl-all.jar`.

## Usage

### CLI — download by URL

```bash
java -jar build/libs/mujrozhlas-dl-all.jar download "https://mujrozhlas.cz/..." -o ./downloads

# Preview without downloading
java -jar build/libs/mujrozhlas-dl-all.jar download --dry-run "https://mujrozhlas.cz/..."
```

### Web UI

```bash
java -jar build/libs/mujrozhlas-dl-all.jar serve -p 8080 -o ./downloads --db data/mujrozhlas.db
```

Open http://localhost:8080. The server periodically scans for new serial episodes. You can also trigger a scan manually or add serials by URL from the dashboard.

### Docker

```bash
docker build -t mujrozhlas-dl .
docker run -p 8080:8080 -v ./data:/data -v ./downloads:/downloads mujrozhlas-dl
```
