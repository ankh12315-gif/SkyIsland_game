# 后备弹药开关的两点取证：同一套参数，只差 `-Dskyisland.infiniteReserve=true`。
#
# 为什么要跑**两次**而不是跑一次看日志：开关的全部意义就是"翻面"。
# 只跑打开的那一次，无法排除"这一局本来就打印无限"这种可能；
# 只跑关闭的那一次，无法排除"日志这行是写死的"。两次并排放在一份日志里，
# 差的那一行就只可能是那个属性。
#
# 参数传递沿用 tmp/verify_m3_play.ps1 里已验证的写法：先建数组
# （`-Dk=` + $var 必须加括号，否则会被拆成两个元素），再 `& java $数组`；
# 不要内联 `-Dskyisland.xxx=yyy`（本机会被拆碎成 ClassNotFoundException）。
#
# 两次都用**各自全新的空存档目录 + 各自的世界名**：开局装备只在"新世界"发放，
# 复用目录会让第二次读到第一次的存档，装备不再补发（症状是"第二次没有枪"）。
# 世界名与目录名都带运行时间戳，因此**每次执行都是新世界**，不必手工清理旧目录
# （本机的 safe-delete 守卫会拦截 Remove-Item，所以这里刻意不做任何删除）。
# 两次都不写回存档（noSave=true），因此不会污染交付用的 m3-play-saves。
#
# 另一个本机坑（本次实测踩到）：**PowerShell 5.1 把无 BOM 的 .ps1 按 GBK 读**。
# 用 Write 工具（输出 UTF-8）写出的中文**注释**无碍（注释不输出），
# 但中文**字符串字面量**一旦 Out-File 进日志就会变成乱码
# （实测 '后备弹药口径' 写成日志时成了 '鍚庡寮硅嵂鍙ｅ緞'）。
# 因此本脚本把**写进日志的标签串全部用 ASCII**；判读时只认 ASCII 标签，
# 游戏自己输出（java，UTF-8）的那一行不受影响。

$java = 'D:/software/jdk-25/bin/java.exe'
$jar  = 'F:/minecraftspace/target/skyisland-0.3.2-M2_2-UI-INVENTORY.jar'
$base = 'F:/minecraftspace/tmp'
$log  = $base + '/m3_infinite_reserve_verify.log'
$stamp = Get-Date -Format 'HHmmss'

'A: NO SWITCH  -> expect CALIBER = SURVIVAL (finite)' | Out-File $log -Encoding utf8

$saveA = $base + '/m3-ir-verify-A-' + $stamp + '-saves'
New-Item -ItemType Directory -Path $saveA -Force | Out-Null
$argsA = @('--enable-native-access=ALL-UNNAMED',
           ('-Dskyisland.worldName=m3-ir-A-' + $stamp),
           ('-Dskyisland.saveDir=' + $saveA),
           '-Dskyisland.settingsFile=tmp/m3-ir-verify-settings.json',
           '-Dskyisland.warmupSeconds=1',
           '-Dskyisland.measureSeconds=6',
           '-Dskyisland.noSave=true',
           '-jar', $jar)
'argCountA=' + $argsA.Count | Out-File $log -Encoding utf8 -Append
for ($i = 0; $i -lt $argsA.Count; $i++) { ('  [' + $i + '] <' + $argsA[$i] + '>') | Out-File $log -Encoding utf8 -Append }
& $java $argsA *>&1 | Out-File $log -Encoding utf8 -Append
('EXIT_A=' + $LASTEXITCODE) | Out-File $log -Encoding utf8 -Append

'' | Out-File $log -Encoding utf8 -Append
'B: -Dskyisland.infiniteReserve=true  -> expect CALIBER = PROTOTYPE (infinite)' | Out-File $log -Encoding utf8 -Append

$saveB = $base + '/m3-ir-verify-B-' + $stamp + '-saves'
New-Item -ItemType Directory -Path $saveB -Force | Out-Null
$argsB = @('--enable-native-access=ALL-UNNAMED',
           ('-Dskyisland.worldName=m3-ir-B-' + $stamp),
           ('-Dskyisland.saveDir=' + $saveB),
           '-Dskyisland.settingsFile=tmp/m3-ir-verify-settings.json',
           '-Dskyisland.warmupSeconds=1',
           '-Dskyisland.measureSeconds=6',
           '-Dskyisland.noSave=true',
           '-Dskyisland.infiniteReserve=true',
           '-jar', $jar)
'argCountB=' + $argsB.Count | Out-File $log -Encoding utf8 -Append
for ($i = 0; $i -lt $argsB.Count; $i++) { ('  [' + $i + '] <' + $argsB[$i] + '>') | Out-File $log -Encoding utf8 -Append }
& $java $argsB *>&1 | Out-File $log -Encoding utf8 -Append
('EXIT_B=' + $LASTEXITCODE) | Out-File $log -Encoding utf8 -Append

'' | Out-File $log -Encoding utf8 -Append
'C: -Dskyisland.infiniteReserve=1  (typo) -> expect CALIBER = SURVIVAL (safe side)' | Out-File $log -Encoding utf8 -Append

$saveC = $base + '/m3-ir-verify-C-' + $stamp + '-saves'
New-Item -ItemType Directory -Path $saveC -Force | Out-Null
$argsC = @('--enable-native-access=ALL-UNNAMED',
           ('-Dskyisland.worldName=m3-ir-C-' + $stamp),
           ('-Dskyisland.saveDir=' + $saveC),
           '-Dskyisland.settingsFile=tmp/m3-ir-verify-settings.json',
           '-Dskyisland.warmupSeconds=1',
           '-Dskyisland.measureSeconds=6',
           '-Dskyisland.noSave=true',
           '-Dskyisland.infiniteReserve=1',
           '-jar', $jar)
'argCountC=' + $argsC.Count | Out-File $log -Encoding utf8 -Append
for ($i = 0; $i -lt $argsC.Count; $i++) { ('  [' + $i + '] <' + $argsC[$i] + '>') | Out-File $log -Encoding utf8 -Append }
& $java $argsC *>&1 | Out-File $log -Encoding utf8 -Append
('EXIT_C=' + $LASTEXITCODE) | Out-File $log -Encoding utf8 -Append
