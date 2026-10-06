#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M4方块贴图四道守卫的**统一入口**。

★ 为什么要这个文件（不是"锦上添花"，是修一个真实的坑）
------------------------------------------------------------------
`verify_grids.py` / `audit_spec.py` / `audit_palette.py` 都依赖
`extract_spec.py` 的**派生产物** `spec_data.json`。
而 `spec_data.json` **不在版本库**（它是生成物）。

⇒ 于是"单独跑某一道守卫"会 `FileNotFound`。
⇒ 于是后来人被迫去记"要记得先跑 extract_spec" ——
   **而"要记得两步"这件事本身必然会失效**。
   这与本项目已中过多次的坑同型：
   把流程知识放进人的记忆里，而不是放进命令里。

所以本文件只做一件事：**把四道守卫按正确顺序串起来，让"跑一条命令"成立。**

四道守卫各自负责**不同的**盲区（它们互不替代，正是因为如此才都要有）：

| 脚本 | 查什么 | 单独查为什么抓不到 |
|---|---|---|
| `extract_spec` | 从 `docs/art/BLOCK_TEXTURE_SPEC.md` 抽出网格与调色板 | 它是**提取器**，不做判断 |
| `audit_spec`   | 网格字符 / 档数 / 越界 / 悬空 tone 引用 | 网格与档数都正确时，**色值错它照样绿** |
| `verify_grids` | 24 张网格的字符/ 宽度 / 转录正确性 | 同上|
| `audit_palette`| 逐档比对 **HEX 色值** | ★ 它是唯一抓到 `log_top` 误用树皮色阶的那一道 |

★ 实测留痕（M4-S3）：`log_top` 复用 `OPAQUE_LOG`（树皮色阶）时，
`audit_spec` 报"全部一致"、`verify_grids` 报 "0 problems"，
**只有 `audit_palette` 报出该层缺少调色板常量**。
⇒ 少任何一道都会漏，只是漏的东西不同。

用法：
    python tmp/s3work/check_all.py          # 跑全部四道
    python tmp/s3work/check_all.py --quiet  # 只在失败时输出

退出码：全部通过 = 0；任一失败 = 1（可直接用于门禁串联）。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# ★ 顺序即依赖：extract_spec 必须最先跑（它产出 spec_data.json），
# 后三道都读那个文件。
STEPS = [
    ("extract_spec", "从美术规格抽取网格与调色板（派生产物 spec_data.json）"),
    ("audit_spec", "网格字符 / 档数 / 越界 / 悬空 tone 引用"),
    ("verify_grids", "24 张网格的字符与转录正确性"),
    ("audit_palette", "逐档比对 HEX 色值（★唯一能抓色值错的一道）"),
]


def main() -> int:
    quiet = "--quiet" in sys.argv
    failures: list[tuple[str, str]] = []

    for script, purpose in STEPS:
        path = HERE / f"{script}.py"
        if not path.exists():
            failures.append((script, f"脚本不存在：{path}"))
            print(f"[MISS] {script:<14} {purpose}")
            continue

        proc = subprocess.run(
            [sys.executable, str(path)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        out = (proc.stdout or "") + (proc.stderr or "")

        if proc.returncode != 0:
            failures.append((script, out.strip()[-1500:]))
            print(f"[FAIL] {script:<14} {purpose}")
            if not quiet:
                print("       " + out.strip().replace("\n", "\n       ")[-1500:])
        else:
            # 摘最后一行有信息的，作为"证据留痕"
            tail = ""
            for line in reversed(out.strip().splitlines()):
                s = line.strip()
                if s and not s.startswith("=") and not s.startswith("（"):
                    tail = s
                    break
            print(f"[ OK ] {script:<14} {purpose}")
            if tail and not quiet:
                print(f"       {tail}")

    print("-" * 78)
    if failures:
        print(f"=== 四道守卫中有 {len(failures)} 道失败 ===")
        for script, detail in failures:
            print(f"  - {script}")
        print("★ 修法：改 `docs/art/BLOCK_TEXTURE_SPEC.md` 后重跑本命令，")
        print("  **不要手改 `BlockTextures.java`** —— 规格才是唯一真相源。")
        return 1

    print(f"=== 四道守卫全部通过（{len(STEPS)}/{len(STEPS)}）===")
    print("★ 这只证明**规格与 Java 侧一致**。")
    print("  它不证明**画面好看** —— 画面仍需人眼确认（截图 / 真人试玩）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
