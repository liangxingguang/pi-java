#!/usr/bin/env bash
# pi 漂移检测器 —— 见同目录 ANCHOR.md
#
# 用法：  bash docs/map/check-drift.sh [pi 仓库路径]
# 默认 pi 路径：D:/workplaceForai/pi
#
# 退出码：0 = 无漂移；1 = 有漂移（可接 CI）

set -u

PI_DIR="${1:-D:/workplaceForai/pi}"
MAP_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ ! -d "$PI_DIR/.git" ]; then
  echo "✗ 找不到 pi 仓库：$PI_DIR" >&2
  exit 2
fi

# 从 ANCHOR.md 读锚点
PIN=$(grep -m1 '^PI_PIN=' "$MAP_DIR/ANCHOR.md" | cut -d= -f2 | tr -d '[:space:]')
if [ -z "$PIN" ]; then
  echo "✗ ANCHOR.md 里没找到 PI_PIN=" >&2
  exit 2
fi

cd "$PI_DIR" || exit 2
HEAD_SHA=$(git rev-parse --short HEAD)
BEHIND=$(git rev-list --count "$PIN..HEAD" 2>/dev/null || echo "?")

echo "pi 锚点   : $PIN"
echo "pi 当前   : $HEAD_SHA"
echo "锚点之后  : $BEHIND 个提交"
echo

if [ "$BEHIND" = "0" ]; then
  echo "✓ 无漂移 —— 地图的 pi 侧证据仍然对应当前 HEAD"
  exit 0
fi

# 改动过的 pi 源文件（完整路径，排除测试与 node_modules）
CHANGED=$(mktemp)
git diff --name-only "$PIN..HEAD" -- '*.ts' \
  | grep -v '/test/' | grep -v '\.test\.ts' | grep -v node_modules > "$CHANGED"

# 从 6 份地图里抽被引用的 pi 文件名（形如 `foo-bar.ts:123`）
CITED=$(mktemp)
grep -oh '`[a-z0-9-]*\.ts:[0-9]' "$MAP_DIR"/*.md | sed 's/`//;s/:.*//' | sort -u > "$CITED"

TOTAL=$(wc -l < "$CITED")
STALE=0
STALE_LIST=$(mktemp)
while read -r b; do
  if grep -q "/$b$" "$CHANGED"; then
    echo "$b" >> "$STALE_LIST"
    STALE=$((STALE + 1))
  fi
done < "$CITED"

echo "地图引用的 pi 文件 : $TOTAL 个"
echo "其中已被改动       : $STALE 个"
echo
if [ "$STALE" -gt 0 ]; then
  echo "⚠ 需重新取证的引用（改动未必落在被引的那一行 —— 这是上界）："
  sed 's/^/    /' "$STALE_LIST"
  echo
  echo "⇒ 地图的 pi 侧行号一律按「对 $PIN 有效」读；重测后再更新 ANCHOR.md。"
fi

rm -f "$CHANGED" "$CITED" "$STALE_LIST"
[ "$STALE" -gt 0 ] && exit 1
exit 0
