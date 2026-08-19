# ---- 构建阶段：安装依赖并编译 TypeScript ----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# ---- 运行阶段：仅保留生产依赖与编译产物 ----
FROM node:20-alpine
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/dist ./dist
# stdio 传输：由 MCP 客户端通过 docker run -i 拉起，无需暴露端口
ENTRYPOINT ["node", "dist/index.js"]
