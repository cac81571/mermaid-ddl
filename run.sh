#!/bin/sh
# mermaid-ddl 起動スクリプト（Linux / macOS）
cd "$(dirname "$0")"
JAR=target/mermaid-ddl-1.0.0-all.jar
[ -f "$JAR" ] || { echo "JAR がありません。先に mvn package を実行してください。"; exit 1; }
exec java -jar "$JAR" "$@"
