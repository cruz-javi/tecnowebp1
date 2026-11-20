#!/usr/bin/env bash
# Compila (si hace falta) e inicia el MailDaemon en segundo plano.
# Uso: ./scripts/run.sh [init|daemon|stop]
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/unexo.jar
OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"

if [ ! -f "$JAR" ]; then
  mvn -q -DskipTests package
fi

case "${1:-daemon}" in
  init)
    java $OPTS -cp "$JAR" unexo.App init
    ;;
  stop)
    pkill -f "unexo.jar" && echo "Daemon detenido." || echo "No estaba en ejecución."
    ;;
  daemon)
    mkdir -p logs
    nohup java $OPTS -jar "$JAR" >> logs/daemon.out 2>&1 &
    echo "MailDaemon iniciado (PID $!). Logs en logs/access.log y logs/error.log"
    ;;
  *)
    echo "Uso: $0 [init|daemon|stop]"
    exit 1
    ;;
esac
