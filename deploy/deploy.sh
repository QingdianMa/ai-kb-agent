#!/usr/bin/env bash
# 在服务器上执行：首次部署或更新都用它。
# 用法： bash deploy.sh
set -euo pipefail

cd "$(dirname "$0")"
COMPOSE="docker compose -f docker-compose.prod.yml"

echo "==> [1/5] 检查配置文件"
if [ ! -f .env ]; then
    echo "缺少 .env。请先执行： cp .env.server.example .env  然后填好里面的值"
    exit 1
fi
if [ ! -f ../target/ai-kb-agent-0.1.0-SNAPSHOT.jar ]; then
    echo "缺少 jar。请在本机执行 mvn -DskipTests package，然后把这个项目目录整体上传到服务器"
    exit 1
fi

echo "==> [2/5] 检查 docker 和 compose"
docker compose version >/dev/null 2>&1 || { echo "缺少 docker compose 插件"; exit 1; }

echo "==> [3/5] 构建应用镜像"
sudo $COMPOSE build

echo "==> [4/5] 启动数据库和应用"
sudo $COMPOSE up -d

echo "==> [5/5] 当前状态"
sudo $COMPOSE ps
echo
echo "看实时日志：  sudo $COMPOSE logs -f app"
echo "看数据库日志：sudo $COMPOSE logs -f postgres"
echo "停止全部：    sudo $COMPOSE down"
