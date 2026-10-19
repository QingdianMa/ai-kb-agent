# 一键构建：前端打包 → 后端打包
# 用法： powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# 用切换目录而不是 npm --prefix：
# npm 10 的 --prefix 在 install 子命令上行为不一致，会去当前目录找 package.json
Push-Location frontend
try {
    Write-Host '[1/3] 安装前端依赖（首次或依赖有变化时需要联网）' -ForegroundColor Cyan
    npm install --no-audit --no-fund

    Write-Host '[2/3] 构建前端，产物输出到 src/main/resources/static' -ForegroundColor Cyan
    npm run build
} finally {
    Pop-Location
}

Write-Host '[3/3] 打包后端 jar' -ForegroundColor Cyan
mvn -DskipTests package

Write-Host ''
Write-Host '构建完成： target\ai-kb-agent-0.1.0-SNAPSHOT.jar' -ForegroundColor Green
Write-Host '部署：把 jar 传到服务器 target 目录，然后在服务器上执行 deploy\deploy.sh'
