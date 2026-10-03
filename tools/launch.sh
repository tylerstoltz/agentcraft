#!/bin/sh
# macOS/Linux: same as `node tools/unix.mjs launch ...` (see tools/README.md).
command -v node >/dev/null 2>&1 || { echo "AgentCraft: Node 22+ is required (https://nodejs.org)" >&2; exit 1; }
exec node "$(dirname "$0")/unix.mjs" launch "$@"
