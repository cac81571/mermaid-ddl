#!/bin/sh
# mermaid-ddl 起動スクリプト（Linux / macOS）
cd "$(dirname "$0")" || exit 1
JAR=target/mermaid-ddl-1.0.0-all.jar
if [ ! -f "$JAR" ]; then
  echo "JAR がありません。mvn package を実行します..."
  mvn package || exit 1
  if [ ! -f "$JAR" ]; then
    echo "JAR が作成されませんでした: $JAR"
    exit 1
  fi
fi
exec java -jar "$JAR" "$@"
