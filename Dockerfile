# 演示环境镜像：一个镜像包含整个单仓，docker-compose 里按服务选择启动命令。
FROM node:24-slim

# 后端是 Java（ADR 0006）：从官方 Temurin 镜像复制 JDK 21，用 Maven Wrapper 打包；Node 只用于前端和脚本
COPY --from=eclipse-temurin:21-jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:$PATH

ENV PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH \
    EAB_HOST=0.0.0.0

RUN corepack enable

WORKDIR /app

# pnpm fetch 只依赖锁文件，源码改动时可以复用这一层的依赖下载缓存
COPY pnpm-lock.yaml pnpm-workspace.yaml package.json ./
RUN pnpm fetch

COPY . .
RUN pnpm install --offline --frozen-lockfile
RUN cd backend && ./mvnw -q -B package -DskipTests

EXPOSE 4000 4100 5173
