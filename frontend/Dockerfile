# syntax=docker/dockerfile:1.7
FROM node:20-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

# nginx-unprivileged:非 root 用户(uid 101)运行,pid/cache 预配置在 /tmp,监听 8080 无需特权端口
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY docker/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O - http://127.0.0.1:8080/ >/dev/null || exit 1
