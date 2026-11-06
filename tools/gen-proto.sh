#!/usr/bin/env bash
# 重新生成孩子端的 protobuf 消息类。唯一事实源是后端那份 .proto，孩子端不单独维护协议。
#
# 为什么不接进 gradle 任务：构建机离线，gradle 缓存里没有 protobuf 插件，
# 而 protoc 和 javalite jar 已经在 ~/.m2 里了。所以生成是一次手动动作，产物入库。
#
# 生成后必须确认 jar 版本和这里的 protoc 版本一致（app/libs/protobuf-javalite-<ver>.jar）。
# 版本错了不会编译失败，会在运行时对不上 wire——所以脚本把版本写死在参数里。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROTO_SOURCE="$REPO_ROOT/../anti-addiction/proto/quizgate.proto"
PROTOC_VERSION="4.36.2"
PROTOC="${HOME}/.m2/repository/com/google/protobuf/protoc/${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-windows-x86_64.exe"
RUNTIME_JAR="$REPO_ROOT/app/libs/protobuf-javalite-${PROTOC_VERSION}.jar"
OUT="$REPO_ROOT/app/src/main/java-gen"

[ -f "$PROTO_SOURCE" ] || { echo "找不到协议源：$PROTO_SOURCE"; exit 1; }
[ -x "$PROTOC" ] || { echo "找不到 protoc ${PROTOC_VERSION}：$PROTOC"; exit 1; }
# 缺 jar 时编译才会炸，而且炸得很莫名，所以在这里先拦住。
[ -f "$RUNTIME_JAR" ] || { echo "缺少运行时 jar：$RUNTIME_JAR（从 ~/.m2/repository/com/google/protobuf/protobuf-javalite/${PROTOC_VERSION}/ 复制）"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"
# lite: 前缀是必须的。不带它 protoc 生成的是全量运行时（有 getDescriptor()），
# 而设备上的 jar 只有 lite，编译期直接找不到符号。
"$PROTOC" --java_out="lite:$(cygpath -w "$OUT")" \
  --proto_path="$REPO_ROOT/../anti-addiction/proto" quizgate.proto

echo "生成 $(find "$OUT" -name '*.java' | wc -l) 个文件 -> app/src/main/java-gen"
