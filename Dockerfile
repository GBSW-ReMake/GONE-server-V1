FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --no-create-home appuser
COPY build/libs/*.jar app.jar
USER appuser
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
