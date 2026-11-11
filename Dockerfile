# ARM64 배포에서도 Java 21 이미지를 사용합니다. 비밀정보는 빌드 인자로 받지 않습니다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER 10001
EXPOSE 8087
ENTRYPOINT ["java","-jar","app.jar"]
