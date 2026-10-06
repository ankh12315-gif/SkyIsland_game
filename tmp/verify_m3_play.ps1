# 正式玩法路径短跑取证：验证"新世界 → 开局装备发两把枪"。
#
# ★ 本文件必须带 UTF-8 BOM（第 1 行开头那 3 个字节）。Windows PowerShell 5.1 读
#   **无 BOM** 的 .ps1 时按系统 ANSI 代码页解码（本机 GBK），UTF-8 中文会变乱码，
#   且 GBK 双字节序列可能吞掉引号把脚本解析打断。2026-10-03 实测：只是加了一段
#   中文注释，脚本就报一屏语法错误（"字符串缺少终止符: '。"）。
#   守卫：PowerShellScriptEncodingTest#everyTrackedNonAsciiPowerShellScriptCarriesAUtf8Bom
#
# 为什么要专门写这个脚本：PowerShell 在本机有两个已知的参数传递坑（见
# tmp/run_gate_ps.ps1 文件头），直接内联写 `-Dskyisland.worldName=xxx`
# 会让 java 收到被拆碎的参数（实测报 ClassNotFoundException: /worldName=xxx）。
# 唯一被验证可用的写法是：**先建数组（`-Dk=` + $var 必须加括号），再用 `& java $数组`**。
# 所以本脚本沿用该写法，并先把数组逐元素打印出来对账，跑完再判读。

$java    = 'D:/software/jdk-25/bin/java.exe'
$saveDir = 'F:/minecraftspace/tmp/m3-verify-saves'
$log     = 'F:/minecraftspace/tmp/m3_play_verify.log'

# ★ 2026-10-03：jar 不再写死版本号。
#   写死会留下"升了版本却跑着上一版 jar"的静默陷阱 —— 报告里引用的证据会指向
#   一个不存在的构建，而脚本照样打印"通过"（与 tmp/run_frozen_gate.js 的理由相同）。
#   解析规则与 play-m3.bat / run_frozen_gate.js **完全一致**：
#   排除 maven-shade-plugin 留下的字节相同别名 `-shaded.jar`（以及不匹配 glob 的
#   `original-*.jar` thin 备份），再要求"恰好 1 个真 jar"。
#   这三处曾各有一个版本用"文件数 == 1"当判据，而 shade 别名会在任何一次
#   "不 clean 就重新构建"之后出现 —— 于是它们在最常见的迭代路径上一起坏掉。
$jarDir  = 'F:/minecraftspace/target'
$jars    = @(Get-ChildItem -Path $jarDir -Filter 'skyisland-*.jar' -File |
             Where-Object { $_.Name -notlike '*-shaded.jar' })
if ($jars.Count -ne 1) {
  ('[FATAL] target/ 下应恰好有 1 个可运行的 skyisland-*.jar，实际 ' + $jars.Count +
   ' 个（已忽略 *-shaded.jar 别名）。target/ 实际内容：' +
   ((Get-ChildItem -Path $jarDir -Filter '*.jar' -File | ForEach-Object { $_.Name }) -join ', ')) |
    Out-File $log -Encoding utf8
  exit 2
}
$jar = $jars[0].FullName

New-Item -ItemType Directory -Path $saveDir -Force | Out-Null

$args1 = @('--enable-native-access=ALL-UNNAMED',
  '-Dskyisland.worldName=m3-verify',
           ('-Dskyisland.saveDir=' + $saveDir),
  '-Dskyisland.settingsFile=tmp/m3-verify-settings.json',
           '-Dskyisland.warmupSeconds=1',
           '-Dskyisland.measureSeconds=6',
           '-Dskyisland.noSave=true',
  # 与 play-m3.bat 同口径：不带这两个开关就跑不到玩家真正会看到的局面
  # （有限后备看不到"换弹扣背包"、SURVIVAL 看不到步枪与材料包）。
  '-Dskyisland.infiniteReserve=true',
  '-Dskyisland.loadout=dev',
           '-jar',
           $jar)

$head = @()
$head += 'argCount=' + $args1.Count
for ($i = 0; $i -lt $args1.Count; $i++) { $head += ('  [' + $i + '] <' + $args1[$i] + '>') }
$head += ''
$head | Out-File $log -Encoding utf8

& $java $args1 *>&1 | Out-File $log -Encoding utf8 -Append
('EXIT=' + $LASTEXITCODE) | Out-File $log -Encoding utf8 -Append
