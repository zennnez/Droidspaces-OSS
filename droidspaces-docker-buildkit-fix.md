# Droidspaces Docker Build Fix: buildx, compose plugin, daemon.json

## Symptoms
- `docker compose build` failed on `apt-get update` inside the Dockerfile with:
  `E: List directory /var/lib/apt/lists/partial is missing. - Acquire (1: Operation not permitted)`
- Later, build failed with: `the classic builder doesn't support secrets, set DOCKER_BUILDKIT=1 to use BuildKit`
- `docker compose` also warned: `Docker Compose requires buildx plugin to be installed`

## Root causes
1. **Seccomp restriction** - root inside plain containers couldn't `mkdir` (`Operation not permitted`), confirmed via:
   ```bash
   docker run --rm --security-opt seccomp=unconfined python:3.11-slim-bookworm \
     bash -c "mkdir -p /var/lib/apt/lists/partial && echo OK"
   ```
   This succeeded, proving the default seccomp profile under Droidspaces (Android host kernel) blocks syscalls a normal Linux host allows.

2. **Missing `docker-buildx-plugin`** - Compose silently fell back to the classic builder regardless of `DOCKER_BUILDKIT` env var or `daemon.json` settings, because the `buildx` CLI plugin wasn't installed. This is required for Compose v2/v5 to invoke BuildKit at all.

## Fix steps

### 1. Install Docker's official apt repo + compose/buildx plugins
```bash
sudo apt update
sudo apt install ca-certificates curl

sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
$(. /etc/os-release && echo ${UBUNTU_CODENAME:-$VERSION_CODENAME}) stable" | \
sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install docker-compose-plugin
sudo apt install docker-buildx-plugin

docker buildx version   # verify
```

### 2. Edit `/etc/docker/daemon.json`
Added networking/storage tuning for the Tailscale/Droidspaces environment and enabled BuildKit as the daemon default:
```json
{
  "exec-opts": ["native.cgroupdriver=cgroupfs"],
  "storage-driver": "overlay2",
  "dns": ["8.8.8.8", "1.1.1.1"],
  "mtu": 1420,
  "ipv6": false,
  "features": {
    "buildkit": true
  }
}
```
> Note: `"seccomp-profile": "unconfined"` was tried as a daemon-wide key but is **invalid** - dockerd expects a file *path* there, not the literal string `unconfined`, and adding it broke `docker.service` (`Start request repeated too quickly`, exit code 1). Removed it from daemon.json.

Applied with:
```bash
sudo systemctl restart docker
sudo systemctl status docker.service --no-pager
```

### 3. Rebuild
```bash
docker compose up -d --build evedev
```

## Still open / next step
The seccomp `Operation not permitted` issue is only fixed **per-container**, via `--security-opt seccomp=unconfined` on `docker run`. It cannot be set daemon-wide. If it resurfaces during `compose build`, the correct place to apply it is in `docker-compose.yml` under the specific service:
```yaml
services:
  evedev:
    security_opt:
      - seccomp:unconfined
```
