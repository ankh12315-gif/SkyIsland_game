# 正式玩法路径短跑取证：验证"新世界 → 开局装备发两把枪"。
#
# 为什么要专门写这个脚本：PowerShell 在本机有两个已知的参数传递坑（见
# tmp/run_gate_ps.ps1 文件头），直接内联写 `-Dskyisland.worldName=xxx`
# 会让 java 收到被拆碎的参数（实测报 ClassNotFoundException: /worldName=xxx）。
# 唯一被验证可用的写法是：**先建数组（`-Dk=` + $var 必须加括号），再用 `& java $数组`**。
# 所以本脚本沿用该写法，并先把数组逐元素打印出来对账，跑完再判读。

$java    = 'D:/software/jdk-25/bin/java.exe'
$jar     = 'F:/minecraftspace/target/skyisland-0.3.2-M2_2-UI-INVENTORY.jar'
$saveDir = 'F:/minecraftspace/tmp/m3-verify-saves'
$log     = 'F:/minecraftspace/tmp/m3_play_verify.log'

New-Item -ItemType Directory -Path $saveDir -Force | Out-Null

$args1 = @('--enable-native-access=ALL-UNNAMED',
           '-Dskyisland.worldName=m3-verify',
           ('-Dskyisland.saveDir=' + $saveDir),
           '-Dskyisland.settingsFile=tmp/m3-verify-settings.json',
           '-Dskyisland.warmupSeconds=1',
           '-Dskyisland.measureSeconds=6',
           '-Dskyisland.noSave=true',
           '-jar',
           $jar)

$head = @()
$head += 'argCount=' + $args1.Count
for ($i = 0; $i -lt $args1.Count; $i++) { $head += ('  [' + $i + '] <' + $args1[$i] + '>') }
$head += ''
$head | Out-File $log -Encoding utf8

& $java $args1 *>&1 | Out-File $log -Encoding utf8 -Append
('EXIT=' + $LASTEXITCODE) | Out-File $log -Encoding utf8 -Append
