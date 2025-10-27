# ==== Build ====
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY . .
RUN mvn -q -DskipTests package

# ==== Runtime ====
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN useradd -r -u 10001 -g root appuser
USER appuser
COPY --from=build /src/target/*-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8" \
    SPRING_PROFILES_ACTIVE="docker" \
    SERVER_PORT="8080"
ENTRYPOINT ["java","-jar","/app/app.jar"]
