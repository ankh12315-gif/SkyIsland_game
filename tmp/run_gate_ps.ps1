# SkyIsland - 冻结 jar 门禁串行跑（PowerShell 版，扁平结构）。
#
# 为什么要有这个 PS 版：本机出现过 `spawnSync java.exe -> EBUSY` 的间歇性环境故障，
# 任何从 node 发起的子进程创建（cmd / node / java 全部）都会失败，node 版运行器
# tmp/run_frozen_gate.js 因此只能产出 0 字节证据。PowerShell 直接 `& java` 仍然可用，
# 所以这里把同一套纪律用 PowerShell 重写一遍，纪律一条都不许少：
#   1) 冻结 jar：不跑 Maven，直接用 tmp/selftest-jar/skyisland-frozen.jar。
#      脚本开头校验"冻结 jar 不比源码旧"，旧了就直接退出（否则证据指向错误构建）。
#   2) 每个门禁一个全新的空存档目录：复用 saveDir 会读进上一轮存档，假红长得像真红。
#
# 本机四个已经踩过的坑，写在最前面免得后人再踩：
#   A) Remove-Item 被 safe-delete 守卫接管，回收站操作 fail-closed 抛异常打断脚本。
#      所以本脚本从不删除旧证据文件，只覆盖写。
#   B) Start-Process 的 -RedirectStandardOutput / -ArgumentList 在本机报参数校验错
#      （实测 Null/Empty，而变量其实有值）。改用 `&` 直接调用 java + PowerShell 重定向。
#   C) 不要用 `$r = Run-Gate ...` 接返回值：那会把函数里所有 Write-Output 吞进变量，
#      进度一行都看不到。故本脚本刻意写成扁平三段，不用函数封装。
#   D) **数组字面量里 `'-Dk=' + $var` 会被拆成两个元素 / 变空**（实测 count=6 或值为空），
#      于是 java 把存档路径当成主类名报 ClassNotFoundException。
#      必须写成括号表达式 `('-Dk=' + $var)`（实测 count=5、值完整）。
#   E) ★ **本文件必须带 UTF-8 BOM**（第 1 行开头那 3 个字节就是，别手删）。
#      Windows PowerShell 5.1 读**无 BOM** 的 .ps1 时按系统 ANSI 代码页解码（本机 GBK），
#      而本文件是 UTF-8：中文注释会变乱码，更糟的是 GBK 双字节序列可能吞掉引号，
#      把脚本解析打断（实测报「字符串缺少终止符: '。」，一屏语法错误）。
#      守卫：`PowerShellScriptEncodingTest#everyTrackedNonAsciiPowerShellScriptCarriesAUtf8Bom`。
#      ★ 本文件<b>曾经无 BOM 却一直能跑</b>（88 行中文恰好没触发吞引号）—— 那是巧合，不是安全。
#      修法永远是**加 BOM / 改编码**，绝不是删中文注释（与 CjkFontTest 同一条项目规矩）。
#
# Usage: powershell -ExecutionPolicy Bypass -File tmp/run_gate_ps.ps1

$java   = 'D:/software/jdk-25/bin/java.exe'
$frozen = 'F:/minecraftspace/tmp/selftest-jar/skyisland-frozen.jar'
$tmp    = 'F:/minecraftspace/tmp'
$proj   = 'F:/minecraftspace'

# ---------- 0. 前置校验：冻结 jar 必须不比源码旧 ----------
if (-not (Test-Path $frozen)) {
    '[FATAL] frozen jar missing: ' + $frozen | Out-File ($tmp + '/gate_ps_out.txt') -Encoding utf8
    exit 1
}
$jarTs  = (Get-Item $frozen).LastWriteTimeUtc
$files  = Get-ChildItem -Path ($proj + '/src'), ($proj + '/tools') -Recurse -File -Include '*.java','*.xml'
$newest = $files | Sort-Object -Property LastWriteTimeUtc -Descending | Select-Object -First 1
$srcTs  = $newest.LastWriteTimeUtc

$head = @()
$head += 'frozen jar mtime(UTC) = ' + $jarTs
$head += 'newest src mtime(UTC) = ' + $srcTs + '  ' + $newest.FullName
if ($jarTs -lt $srcTs) {
    $head += '[FATAL] frozen jar is older than sources. Rebuild first.'
    $head | Out-File ($tmp + '/gate_ps_out.txt') -Encoding utf8
    exit 2
}
$head += 'frozen jar is not older than sources: OK'
$head += ''

# ---------- 1. 本轮运行根目录（带时间戳，全新空目录）----------
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$root  = $tmp + '/gate-runs/' + $stamp
New-Item -ItemType Directory -Path $root -Force | Out-Null
$head += 'run root: ' + $root
$head += ''
$head | Out-File ($tmp + '/gate_ps_out.txt') -Encoding utf8

$report = @()

# ============================ gate-m1 ============================
$saveDir = $root + '/gate-m1-saves'
New-Item -ItemType Directory -Path $saveDir -Force | Out-Null
$out  = $tmp + '/m2_gate-m1.stdout.txt'
$args1 = @('--enable-native-access=ALL-UNNAMED', '-Dskyisland.selfTest=true',
           ('-Dskyisland.saveDir=' + $saveDir), '-Dskyisland.loadout=dev', '-jar', $frozen)
$t0 = Get-Date
& $java $args1 *>&1 | Out-File -FilePath $out -Encoding utf8
$exit = $LASTEXITCODE
$elapsed = ((Get-Date) - $t0).TotalSeconds
$report += 'gate-m1 exit=' + $exit + ' elapsed=' + [math]::Round($elapsed,1) + 's saveDir=' + $saveDir

# ============================ gate-ui ============================
$saveDir = $root + '/gate-ui-saves'
New-Item -ItemType Directory -Path $saveDir -Force | Out-Null
$out  = $tmp + '/m2_gate-ui.stdout.txt'
$args1 = @('--enable-native-access=ALL-UNNAMED', '-Dskyisland.uiSelfTest=true',
           ('-Dskyisland.saveDir=' + $saveDir), '-Dskyisland.loadout=dev', '-jar', $frozen)
$t0 = Get-Date
& $java $args1 *>&1 | Out-File -FilePath $out -Encoding utf8
$exit = $LASTEXITCODE
$elapsed = ((Get-Date) - $t0).TotalSeconds
$report += 'gate-ui exit=' + $exit + ' elapsed=' + [math]::Round($elapsed,1) + 's saveDir=' + $saveDir

# ============================ gate-m2 ============================
$saveDir = $root + '/gate-m2-saves'
New-Item -ItemType Directory -Path $saveDir -Force | Out-Null
$out  = $tmp + '/m2_gate-m2.stdout.txt'
$args1 = @('--enable-native-access=ALL-UNNAMED', '-Dskyisland.combatSelfTest=true',
           ('-Dskyisland.saveDir=' + $saveDir), '-Dskyisland.loadout=dev', '-jar', $frozen)
$t0 = Get-Date
& $java $args1 *>&1 | Out-File -FilePath $out -Encoding utf8
$exit = $LASTEXITCODE
$elapsed = ((Get-Date) - $t0).TotalSeconds
$report += 'gate-m2 exit=' + $exit + ' elapsed=' + [math]::Round($elapsed,1) + 's saveDir=' + $saveDir

$report += 'evidence dir: ' + $root
$report | Out-File ($tmp + '/gate_ps_out.txt') -Encoding utf8 -Append
