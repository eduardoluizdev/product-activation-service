# ---- build ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Dependências primeiro, para aproveitar cache de camadas quando só o código muda.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon -q > /dev/null

COPY src src
# Os testes rodam no CI (./gradlew build); aqui só empacotamos.
RUN ./gradlew bootJar --no-daemon -q

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app

# Heap proporcional ao limite de memória do contêiner; encerra em OOM para o orquestrador reiniciar.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness > /dev/null || exit 1

# exec form: o java é o PID 1 e recebe o SIGTERM (desligamento gracioso).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
