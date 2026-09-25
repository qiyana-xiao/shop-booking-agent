#Requires -Version 5.1
<#
店小约端到端验证脚本（真实服务，真实 DeepSeek）

验证链路：
  1. 第一层应答：问营业时间 -> AI 直接回答，不转人工
  2. 投诉 -> AI 调 escalate_to_human 转人工（escalated=true）
  3. 人工接管中 -> 顾客再发消息 -> humanMode=true，AI 不抢答
  4. 店员回复 -> human 消息直达顾客会话，工单进入处理中
  5. 工单解决 -> AI 恢复应答（humanMode=false）
  6. 同一会话不重复建单

前置：服务已启动（scripts\toggle.ps1 或 启动-店小约.bat）
#>

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = 'http://localhost:8080'
$sessionKey = 'e2e-' + (Get-Date -Format 'yyyyMMddHHmmss')

function Post-Json($path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    Invoke-RestMethod -Method Post -Uri ($base + $path) `
        -ContentType 'application/json; charset=utf-8' -Headers $headers `
        -Body ($body | ConvertTo-Json -Depth 5)
}

function Get-Api($path, $token) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    Invoke-RestMethod -Uri ($base + $path) -Headers $headers
}

# ---------- 0. 店员账号 ----------
try {
    $reg = Post-Json '/api/auth/register' @{ username = 'e2e_staff'; password = 'e2ePass123'; role = 'staff' } $null
    $token = $reg.token
    Write-Host '[0] 已注册店员账号 e2e_staff'
} catch {
    $login = Post-Json '/api/auth/login' @{ username = 'e2e_staff'; password = 'e2ePass123' } $null
    $token = $login.token
    Write-Host '[0] 店员账号 e2e_staff 已存在，直接登录'
}

# ---------- 1. 第一层应答：营业时间 ----------
$r1 = Post-Json '/api/chat' @{ message = '请问你们店几点营业？'; sessionKey = $sessionKey } $null
Write-Host ''
Write-Host ('[1] 直答测试  escalated={0}  回复：{1}' -f $r1.escalated, $r1.reply)

# ---------- 2. 投诉 -> 转人工 ----------
$r2 = Post-Json '/api/chat' @{ message = '我要投诉！上周在你们店里吃坏了肚子，现在还难受，必须给我一个说法！'; sessionKey = $sessionKey } $null
Write-Host ''
Write-Host ('[2] 投诉转人工  escalated={0}  回复：{1}' -f $r2.escalated, $r2.reply)

# ---------- 3. 接管中 AI 不抢答 ----------
$r3 = Post-Json '/api/chat' @{ message = '怎么还没人理我？'; sessionKey = $sessionKey } $null
Write-Host ''
Write-Host ('[3] 接管中应答  humanMode={0}  回复：{1}' -f $r3.humanMode, $r3.reply)

# ---------- 4. 店员回复直达会话 ----------
$list = Get-Api '/api/escalations?status=OPEN' $token
$ticket = @($list.list | Where-Object { $_.conversationId -eq $r1.conversationId }) | Select-Object -First 1
if (-not $ticket) { throw '未找到本会话的 OPEN 工单（转人工失败）' }
$reply = Post-Json ("/api/escalations/{0}/reply" -f $ticket.id) @{
    content = '您好，我是店长。实在抱歉！已为您登记全额退款，并额外赠送一张下次免单券作为补偿，稍后按您留的电话联系您。'
} $token
Write-Host ''
Write-Host ('[4] 店员回复  工单#{0} status={1}' -f $ticket.id, $reply.status)

# ---------- 5. 顾客侧历史：human 消息 + humanMode ----------
$h = Get-Api ("/api/chat/history?sessionKey=" + $sessionKey) $null
$humanMsgs = @($h.messages | Where-Object { $_.role -eq 'human' })
Write-Host ''
Write-Host ('[5] 顾客历史  humanMode={0}  店家消息数={1}  最新店家消息：{2}' -f $h.humanMode, $humanMsgs.Count, $humanMsgs[-1].content)

# ---------- 6. 解决工单 -> AI 恢复 ----------
$res = Post-Json ("/api/escalations/{0}/resolve" -f $ticket.id) @{ note = '已全额退款并赠送免单券' } $token
$r4 = Post-Json '/api/chat' @{ message = '好的，那就这样处理吧，谢谢店长。'; sessionKey = $sessionKey } $null
Write-Host ''
Write-Host ('[6] 解决后 AI 恢复  humanMode={0}  回复：{1}' -f $r4.humanMode, $r4.reply)

# ---------- 7. 防重复建单 ----------
$all = Get-Api '/api/escalations?size=100' $token
$dup = @($all.list | Where-Object { $_.conversationId -eq $r1.conversationId })
Write-Host ''
Write-Host ('[7] 防重  本会话工单数={0}（应为 1）' -f $dup.Count)

Write-Host ''
$pass = ($r1.escalated -eq $false) -and ($r2.escalated -eq $true) -and ($r3.humanMode -eq $true) `
    -and ($reply.status -eq 'PROCESSING') -and ($humanMsgs.Count -ge 1) -and ($h.humanMode -eq $true) `
    -and ($r4.humanMode -eq $false) -and ($dup.Count -eq 1)
if ($pass) {
    Write-Host '========== 端到端验证全部通过 ==========' -ForegroundColor Green
} else {
    Write-Host '========== 存在未通过项，请核对上方各步输出 ==========' -ForegroundColor Red
}
