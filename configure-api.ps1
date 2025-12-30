# AI Copilot Plugin - API 配置脚本
Write-Host "AI Copilot Plugin - API 配置" -ForegroundColor Green
Write-Host "==========================" -ForegroundColor Green
Write-Host ""

# 设置 API 基础 URL
$env:ANTHROPIC_BASE_URL = "https://code.usezyla.com"
Write-Host "✅ ANTHROPIC_BASE_URL 已设置: $env:ANTHROPIC_BASE_URL" -ForegroundColor Green

# 提示输入 API Key
Write-Host ""
Write-Host "请输入您的 ANTHROPIC_API_KEY:" -ForegroundColor Yellow
$apiKey = Read-Host "API Key"

if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Write-Host "⚠️  警告: API Key 未设置，插件将无法正常工作" -ForegroundColor Red
    Write-Host "请稍后手动设置: `$env:ANTHROPIC_API_KEY='your-key'" -ForegroundColor Yellow
} else {
    $env:ANTHROPIC_API_KEY = $apiKey
    Write-Host "✅ ANTHROPIC_API_KEY 已设置" -ForegroundColor Green
}

Write-Host ""
Write-Host "当前配置:" -ForegroundColor Cyan
Write-Host "ANTHROPIC_BASE_URL = $env:ANTHROPIC_BASE_URL" -ForegroundColor White
if ($env:ANTHROPIC_API_KEY) {
    Write-Host "ANTHROPIC_API_KEY = [已设置]" -ForegroundColor Green
} else {
    Write-Host "ANTHROPIC_API_KEY = [未设置]" -ForegroundColor Red
}

Write-Host ""
Write-Host "现在可以在 IntelliJ IDEA 中运行插件了！" -ForegroundColor Green
Write-Host "插件将显示在右上角工具栏（蓝色 AI 按钮）" -ForegroundColor Cyan
Write-Host ""

Read-Host "按 Enter 键退出"