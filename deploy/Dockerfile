# 运行时镜像：只跑 jar，不在镜像里编译。
# 好处是服务器不用装 JDK 和 Maven，构建也快；jar 由本地打好后一起传上去。
#
# 基础镜像走参数传入：国内服务器直连 Docker Hub 基本不通，
# 实际部署时通过 docker-compose 的 build args 换成可用的镜像源。
ARG BASE_IMAGE=eclipse-temurin:17-jre
FROM ${BASE_IMAGE}

WORKDIR /app

COPY target/ai-kb-agent-0.1.0-SNAPSHOT.jar /app/app.jar

# 把知识库语料和评测集也放进镜像。
# 这两样不在 jar 里，但运行时要用：
#   knowledge/ ——「一键导入知识库目录」读它（对外公开的实验室资料）
#   eval/      ——「跑消融实验」读评测集
# 不复制的话，本地正常、线上会报"找不到文件"。
COPY knowledge /app/knowledge
COPY eval /app/eval

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx1g"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
