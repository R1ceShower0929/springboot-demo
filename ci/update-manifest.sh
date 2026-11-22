#!/usr/bin/env bash
# 把新镜像写进部署仓库的 Kustomize overlay。
#
# 用法（由 Jenkinsfile 调用，也可以手工跑）：
#   TAG=abc12345 DEPLOY_ENV=dev  ./ci/update-manifest.sh <overlay 目录>
#   TAG=abc12345 DEPLOY_ENV=prod ./ci/update-manifest.sh <overlay 目录>
#
# 规则：
#   dev  -> 写 newTag（可读性好，方便排查"这次是哪个提交"）
#   prod -> 写 digest（不可变，镜像 tag 被人覆盖也换不掉线上版本）
# 依赖：仓库根目录下的 backend.digest / frontend.digest（CI 推送后生成）
set -euo pipefail

OVERLAY="${1:?需要 overlay 目录，例如 overlays/dev}"
TAG="${TAG:?需要 TAG}"
DEPLOY_ENV="${DEPLOY_ENV:?需要 DEPLOY_ENV}"
KUST="${OVERLAY}/kustomization.yaml"

[ -f "$KUST" ] || { echo "找不到 $KUST"; exit 1; }

read_digest() {
  # 输入形如 harbor.local/demo/backend@sha256:xxx，只取 @ 之后的部分
  sed -n 's/.*@//p' "$1" | head -1
}

if [ "$DEPLOY_ENV" = "prod" ]; then
  BD="$(read_digest backend.digest)"
  FD="$(read_digest frontend.digest)"
  [ -n "$BD" ] && [ -n "$FD" ] || { echo "缺少 digest 文件内容"; exit 1; }

  # digest 两行紧跟各自的 name 行，用地址范围精确替换，避免串行
  sed -i -E "/name: harbor\.local\/demo\/backend$/{n;s|digest: .*|digest: \"$BD\"|}"  "$KUST"
  sed -i -E "/name: harbor\.local\/demo\/frontend$/{n;s|digest: .*|digest: \"$FD\"|}" "$KUST"
  echo "prod overlay 已固定到 digest：backend=$BD frontend=$FD"
else
  sed -i -E "s/newTag: \".*\"/newTag: \"$TAG\"/g" "$KUST"
  echo "dev overlay 已更新为 tag：$TAG"
fi

echo "--- 当前 images 段 ---"
sed -n '/^images:/,/^[a-z]/p' "$KUST"
