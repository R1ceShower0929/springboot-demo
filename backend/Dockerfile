# ---------- 阶段 1：依赖层（只改源码不会重下依赖）----------
FROM maven:3.9-eclipse-temurin-17 AS deps
WORKDIR /app

# 用国内镜像加速依赖下载（文件随源码一起提交，换台机器也能快速构建）
COPY maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline || true

# ---------- 阶段 2：单元测试（CI 里 --target test 单独跑，测试不过镜像就出不来）----------
FROM deps AS test
COPY src ./src
RUN mvn -B -ntp test

# ---------- 阶段 3：打包 ----------
FROM deps AS build
COPY src ./src
RUN mvn -B -ntp -DskipTests clean package

# ---------- 阶段 4：运行时（非 root、带健康检查、按容器内存自动算堆）----------
FROM eclipse-temurin:17-jre
ARG APP_UID=10001

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -g ${APP_UID} app \
 && useradd -u ${APP_UID} -g app -M -s /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=${APP_UID}:${APP_UID} /app/target/*.jar app.jar

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"

USER ${APP_UID}
EXPOSE 8080

# 容器内自检（K8s 探针另有一套，这里是镜像自身的兜底）
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dfile.encoding=UTF-8 -jar /app/app.jar"]
