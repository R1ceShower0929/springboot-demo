# 构建阶段：Node 编译 Vue
FROM node:20-alpine AS build
WORKDIR /app
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry $NPM_REGISTRY
COPY package*.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

# 运行阶段：Nginx 托管静态文件并反代后端
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80