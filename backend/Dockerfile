# 构建阶段：Maven + JDK 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行阶段：JRE 21
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/campusclaw-backend-*.jar app.jar

# 材料文件持久化目录，由 Compose 挂载 volume
VOLUME /app/data/materials

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
