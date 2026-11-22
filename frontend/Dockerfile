# 构建阶段：Node 编译 Vue
FROM node:20-alpine AS build
WORKDIR /app
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry $NPM_REGISTRY
COPY package*.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

# 运行阶段：非 root 的 Nginx 托管静态文件并反代后端
# unprivileged 镜像以 uid 101 运行、默认监听 8080，容器不必以 root 启动
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
  CMD wget -q -O- http://127.0.0.1:8080/healthz || exit 1
