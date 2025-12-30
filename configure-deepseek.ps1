# DeepSeek API 配置脚本
# 运行方式: .\configure-deepseek.ps1

Write-Host "=== DeepSeek API 配置 ===" -ForegroundColor Cyan

$apiKey = Read-Host "请输入你的 DeepSeek API Key"

if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Write-Host "API Key 不能为空" -ForegroundColor Red
    exit 1
}

# 设置用户环境变量
[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", $apiKey, "User")

# 同时设置当前会话
$env:DEEPSEEK_API_KEY = $apiKey

Write-Host ""
Write-Host "配置完成！" -ForegroundColor Green
Write-Host "DEEPSEEK_API_KEY 已设置为用户环境变量" -ForegroundColor Green
Write-Host ""
Write-Host "注意：需要重启 IDE 才能生效" -ForegroundColor Yellow
Write-Host ""
Write-Host "可选：如需使用自定义 API 地址，请设置 DEEPSEEK_BASE_URL 环境变量" -ForegroundColor Gray
