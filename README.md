# mujrozhlas-dl

CLI tool and web UI for downloading audio content from mujrozhlas.cz. Downloads episodes as M4A (AAC stream copy) and combines full serials into M4B audiobooks with chapters and cover art — ideal for apps like [BookPlayer](https://github.com/TortugaPower/BookPlayer).

## Requirements

- Java 25
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

When downloading a full serial, episodes are saved as individual M4A files and automatically combined into a single M4B audiobook with chapter markers and cover art.

### Web UI

```bash
java -jar build/libs/mujrozhlas-dl-all.jar serve -p 8080 -o ./downloads --db data/mujrozhlas.db
```

Open http://localhost:8080. Features:

- **Dashboard** with serial discovery, search/filter, and manual URL import
- **"Download Series"** subscribes to a serial — downloads all available episodes automatically and creates an M4B audiobook
- **Automatic updates** — the scanner runs periodically and picks up newly available episodes for subscribed serials, recreating the M4B each time
- **Presigned download URLs** for M4B and individual episode files, accessible without authentication (for use with audiobook apps)

### Authentication

Set `AUTH_USER` and `AUTH_PASS` environment variables to enable basic auth. Download links (`/dl/...`) remain publicly accessible via presigned URLs.

### Docker Compose (recommended)

The easiest way to run the web UI. Edit `docker-compose.yml` to set your port, username, and password, then:

```bash
docker compose up -d
```

The service restarts automatically on machine reboot. Data and downloads are stored in `./data` and `./downloads` next to the compose file.

To update to a newer version:

```bash
docker compose pull && docker compose up -d
```

### Docker (manual)

```bash
docker build -t mujrozhlas-dl .
docker run -p 8080:8080 \
  -e AUTH_USER=admin -e AUTH_PASS=secret \
  -v ./data:/data -v ./downloads:/downloads \
  mujrozhlas-dl
```
