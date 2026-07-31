#!/usr/bin/env bash
# Starts the visualizer as a service, building the jar first if it is missing or
# out of date.
#
#   scripts/serve.sh                       # :8080, serve profile
#   scripts/serve.sh --server.port=9000    # anything here is passed to Spring
#   PORT=9000 scripts/serve.sh             # or via the environment
#
# Deliberately self-sufficient: `mvn clean` wipes target/, and a start command
# that breaks after a routine clean is not a start command. The jar under
# target/ is treated as a cache to rebuild, never as a prerequisite to protect.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

jar() { ls -t target/*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1; }

# Rebuild when there is no jar, or when anything under src/ or the pom is newer
# than the one we have. Tests are skipped on purpose — this is the start path,
# not the verification path; run ./mvnw test for that.
needs_build=false
current="$(jar)"
if [[ -z "$current" ]]; then
  needs_build=true
elif [[ -n "$(find src pom.xml -newer "$current" -print -quit 2>/dev/null)" ]]; then
  echo "serve.sh: sources changed since $current — rebuilding" >&2
  needs_build=true
fi

if [[ "$needs_build" == true ]]; then
  ./mvnw -q package -DskipTests
  current="$(jar)"
  [[ -n "$current" ]] || { echo "serve.sh: package produced no jar" >&2; exit 1; }
fi

exec java -jar "$current" \
  --spring.profiles.active=serve \
  --server.port="${PORT:-8080}" \
  "$@"
