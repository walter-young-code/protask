# ---- 构建阶段：Maven 打包 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 先拷贝 pom.xml 单独解析依赖，便于利用 Docker 层缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- 运行阶段：轻量 JRE ----
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /app/target/protask-server.jar app.jar
# 图片上传目录（公开静态映射）与附件私有目录
RUN mkdir -p uploads uploads-attachments
VOLUME ["/app/uploads", "/app/uploads-attachments"]
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
