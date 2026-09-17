FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B verify
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /data/documents && chown -R 10001:10001 /data /app
COPY --from=build /build/target/scrawler-0.1.0.jar /app/app.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0 DOCUMENT_DIR=/data/documents
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","/app/app.jar"]
