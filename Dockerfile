# 演示环境镜像：一个镜像包含整个单仓，docker-compose 里按服务选择启动命令。
FROM node:24-slim

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

EXPOSE 4000 4100 5173
