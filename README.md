# AIR Studio

**AI-augmented R analysis environment** — a fork of [Posit RStudio Server](https://github.com/rstudio/rstudio) (AGPLv3) with a built-in AI coding agent and per-user conda R-environment switching.

AIR Studio installs **standalone** next to any existing RStudio Server on the same host: its own port (`7878`), paths (`/usr/lib/air-studio`, `/etc/air-studio`, `~/.local/share/air-studio`), service (`air-studio-server.service`), PAM profile, and auth cookies. Nothing collides.

## Features over stock RStudio Server

- **Console AI agent** — press `Shift+Tab` in the console to toggle agent mode. The prompt and input turn green; agent replies render with a light-blue background; R commands the agent executes use the normal console styling. Ships as a self-contained Node bundle (no npm install at runtime).
- **Conda R-environment switcher** — toolbar dropdown lists public environments (e.g. `/opt/miniforge3/envs/*`) and each user's personal environments (`~/{miniconda3,miniforge3,anaconda3,.conda}/envs/*`, filtered per user). Switching restarts the session in the selected R; the choice persists per user. A systemd timer rescans every 5 minutes.
- **AIR Studio branding** — product title, tab title, and toolbar logo.

## Install

```bash
sudo dpkg -i air-studio-server_<ver>_amd64.deb air-studio-extras_<ver>_amd64.deb
```

`air-studio-extras` depends on the exact `air-studio-server` version (the chat protocol is version-matched); install both from the same release.

### Configure the LLM gateway (operator default, optional)

The admin can set a server-wide default provider via any OpenAI-compatible endpoint:

```bash
sudo systemctl edit air-studio-server
```

```ini
[Service]
Environment=BIOAGENT_BASE_URL=http://your-gateway:8000/v1
Environment=BIOAGENT_API_KEY=your-key
Environment=BIOAGENT_MODEL=your-model
```

```bash
sudo systemctl restart air-studio-server
```

Optional tuning: `BIOAGENT_CONTEXT_TOKENS` (default 131072), `BIOAGENT_OUTPUT_TOKENS` (default 32768). Works with vLLM, OneAPI, OpenRouter, DeepSeek, and any `/v1/chat/completions` endpoint.

### Per-user providers (console commands)

Each user can manage their own providers in agent mode (`Shift+Tab`) — no admin needed, takes precedence over the server default:

```
addprovider("http://your-gateway:8000/v1", "api-key")   # add; auto-lists & activates first model
showmodel("your-gateway")                               # list models ([x] = active)
setmodel("your-gateway", "model-id")                    # pick model & activate
```

Bare `addprovider()` / `showmodel()` print the current state. Registry: `~/.config/air-studio/llm-providers.json` (0600). Without any provider configured, the agent answers with setup guidance instead of failing.

### Context management

The agent tracks context usage per conversation and compacts automatically:

- **≥ 60%** of the context window: a one-line notice suggests `/compact`
- **≥ 80%**: history is folded into an LLM-generated summary *before* your next message, automatically
- **manual**: type `/compact` in agent mode at any time; the summary replaces the history and is shown in the console

Thresholds are tunable via `BIOAGENT_SUGGEST_PCT` / `BIOAGENT_AUTO_COMPACT_PCT` (fractions, default 0.6 / 0.8); window size via `BIOAGENT_CONTEXT_TOKENS` (default 131072).

## Building from source

```bash
git clone git@github.com:yuumeHuang/AIRstudio.git
cd AIRstudio
RSTUDIO_VERSION_MAJOR=2026 RSTUDIO_VERSION_MINOR=05 RSTUDIO_VERSION_PATCH=1 \
RSTUDIO_VERSION_SUFFIX='+225' RSTUDIO_BOOST_REQUESTED_VERSION=1.91.0 \
RSTUDIO_NODE_VERSION=22.22.2 \
./package/linux/make-package Server DEB
```

The extras package (agent backend + conda machinery) builds from the same tree: `conda-r-versions/build-extras.sh <version>` after building the bioagent bundle (`cd bioagent && bun build src/main.ts --target=node --outdir=dist/server`).

## Layout

| Component | Path |
|---|---|
| Binaries | `/usr/lib/air-studio/bin/` |
| Config | `/etc/air-studio/` |
| Agent backend | `/opt/air-studio/bioagent/` (symlinked into `/etc/air-studio/pai/bin`) |
| Env scanner | `/usr/local/sbin/air-studio-conda-r-versions` + systemd timer |
| User data | `~/.local/share/air-studio/` |
| Listen port | `7878` (configurable via `www-port` in `/etc/air-studio/rserver.conf`) |

## License

The RStudio Server code this project forks is AGPLv3 (see `COPYING`); modifications carry the same license. The toolbar logo is a placeholder — replace `rstudio_2x_huaji.png` if you redistribute.
