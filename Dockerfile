FROM openjdk:17

USER root

# 设置工作目录
WORKDIR /app

# 复制应用程序的 JAR 文件到容器中
COPY zk-migrator.jar .

# 复制自定义脚本到容器中
COPY migrate.sh .
