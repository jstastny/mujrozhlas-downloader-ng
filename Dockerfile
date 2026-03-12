FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache ffmpeg
COPY --from=build /app/build/libs/*-all.jar /app/app.jar

EXPOSE 8080
VOLUME ["/data", "/downloads"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["serve", "-p", "8080", "-o", "/downloads", "--db", "/data/mujrozhlas.db"]
