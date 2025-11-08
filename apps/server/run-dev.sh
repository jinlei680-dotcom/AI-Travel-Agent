#!/usr/bin/env bash
set -euo pipefail

# Always run from script directory
cd "$(dirname "$0")"

# Load environment variables from .env.local if present
set -a
if [ -f ".env.local" ]; then
  echo "Loading .env.local..."
  # shellcheck disable=SC1091
  source .env.local
else
  echo ".env.local not found; proceeding with current env"
fi
set +a

# Defaults (can be overridden by env)
JAR=${JAR:-target/server-0.1.0-SNAPSHOT.jar}
PORT=${SERVER_PORT:-8080}
PROFILE=${SPRING_PROFILES_ACTIVE:-dev}

echo "Starting backend with: JAR=$JAR PORT=$PORT PROFILE=$PROFILE"
echo "Using OPENAI_BASE_URL=${OPENAI_BASE_URL:-unset} OPENAI_MODEL=${OPENAI_MODEL:-unset}"

exec java -jar "$JAR" --server.port="$PORT" --spring.profiles.active="$PROFILE"
