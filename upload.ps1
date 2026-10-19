# 把构建产物和运行需要的文件传到服务器
# 用法： powershell -ExecutionPolicy Bypass -File upload.ps1 -Server user@1.2.3.4
#
# 注意：不会覆盖服务器上的 deploy/.env（那里存着密钥），也不需要传 docs/（开发文档，不进镜像）
param(
    # 服务器地址必须由调用方传入，仓库里不写死任何真实主机
    [Parameter(Mandatory = $true, HelpMessage = "服务器地址，形如 user@1.2.3.4")]
    [string]$Server,
    [string]$RemoteDir = "~/ai-kb-agent"
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path 'target\ai-kb-agent-0.1.0-SNAPSHOT.jar')) {
    throw '找不到 jar，请先运行 build.ps1'
}

Write-Host "[1/5] 上传 jar" -ForegroundColor Cyan
scp target\ai-kb-agent-0.1.0-SNAPSHOT.jar "${Server}:${RemoteDir}/target/"

Write-Host "[2/5] 上传知识库语料" -ForegroundColor Cyan
scp -r knowledge "${Server}:${RemoteDir}/"

Write-Host "[3/5] 上传评测集" -ForegroundColor Cyan
scp -r eval "${Server}:${RemoteDir}/"

# .env.server.example 是服务器上 .env 的模板，必须跟着传，
# 否则新机器上第一步 `cp .env.server.example .env` 就会失败。
# update-lab-content.sql 是内容更新脚本，空库用不到，但首次部署后补数据要用。
# deploy.sh 是服务器上的部署入口，少了它就没法启动。
# 这里刻意不传整个 deploy 目录：那样会把服务器上的 .env（存着密钥）覆盖掉。
Write-Host "[4/5] 上传部署配置" -ForegroundColor Cyan
scp deploy\Dockerfile deploy\docker-compose.prod.yml deploy\deploy.sh deploy\.env.server.example deploy\update-lab-content.sql "${Server}:${RemoteDir}/deploy/"

Write-Host "[5/5] 完成" -ForegroundColor Green
Write-Host ''
Write-Host "接下来在服务器上执行："
Write-Host "  cd ~/ai-kb-agent/deploy" -ForegroundColor Yellow
Write-Host "  nano .env     # 如果还没填 KB_ADMIN_TOKEN 和 KB_REDIS_PASSWORD，现在填上" -ForegroundColor Yellow
Write-Host "  bash deploy.sh" -ForegroundColor Yellow
