FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/url-shortener.jar app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
