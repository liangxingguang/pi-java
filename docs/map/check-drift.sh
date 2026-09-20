#!/usr/bin/env bash
# pi 漂移检测器 —— 见同目录 ANCHOR.md
#
# 用法：  bash docs/map/check-drift.sh [pi 仓库路径]
# 默认 pi 路径：D:/workplaceForai/pi
#
# 退出码：0 = 无漂移；1 = 有漂移（可接 CI）；2 = 用法/环境错
#
# ⚠️ 地图里的 pi 侧引用写的是 **basename**（如 `session-manager.ts:1589`），不是完整路径。
#    所以本脚本按 basename 匹配 —— 而 `index.ts`/`types.ts`/`api.ts` 这类名字在多个包里都有。
#    因此输出分两栏：
#      【确定】该 basename 在 pi 里唯一 ⇒ 被改就是被改
#      【可能】该 basename 有多个同名文件 ⇒ 可能改的是另一个包的同名文件（上界）
#    2026-09-20 实测教训：01-infra 那层检测器报 49 条，逐条复核后**真漂移 0 条** ——
#    误报全来自同名 basename。别把本脚本的输出直接当结论。

set -u

PI_DIR="${1:-D:/workplaceForai/pi}"
MAP_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ ! -d "$PI_DIR/.git" ]; then
  echo "✗ 找不到 pi 仓库：$PI_DIR" >&2
  exit 2
fi

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

# pi 里全部源文件（用于判定 basename 是否唯一）
ALL=$(mktemp)
find . -name '*.ts' -not -path '*/node_modules/*' -not -path './.git/*' \
  | grep -v '/test/' | grep -v '\.test\.ts' > "$ALL"

TOTAL=0; SURE=0; MAYBE=0
SURE_LIST=$(mktemp); MAYBE_LIST=$(mktemp)
while read -r b; do
  TOTAL=$((TOTAL + 1))
  # 该 basename 在 pi 里的同名文件数
  names=$(grep -c "/$b$" "$ALL" || true)
  if grep -q "/$b$" "$CHANGED"; then
    if [ "$names" -le 1 ]; then
      SURE=$((SURE + 1)); echo "$b" >> "$SURE_LIST"
    else
      MAYBE=$((MAYBE + 1)); echo "$b  （pi 里 $names 个同名文件）" >> "$MAYBE_LIST"
    fi
  fi
done < "$CITED"

echo "地图引用的 pi 文件 : $TOTAL 个"
echo "【确定】basename 唯一且已被改 : $SURE 个"
echo "【可能】basename 有同名且已被改: $MAYBE 个（上界，含误报）"
echo

if [ "$SURE" -gt 0 ]; then
  echo "⚠ 【确定】需重新取证 —— 这些 basename 在 pi 里唯一，被改就是被改："
  sed 's/^/    /' "$SURE_LIST"
  echo
fi
if [ "$MAYBE" -gt 0 ]; then
  echo "⚠ 【可能】需人工判 —— 同名文件被改，未必是被引的那个："
  sed 's/^/    /' "$MAYBE_LIST"
  echo
fi

if [ "$SURE" -gt 0 ] || [ "$MAYBE" -gt 0 ]; then
  echo "⇒ 地图的 pi 侧行号一律按「对 $PIN 有效」读；重测后更新 ANCHOR.md。"
fi

rm -f "$CHANGED" "$CITED" "$ALL" "$SURE_LIST" "$MAYBE_LIST"
[ "$SURE" -gt 0 ] || [ "$MAYBE" -gt 0 ] && exit 1
exit 0
