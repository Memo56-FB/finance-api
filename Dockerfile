FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/main/ src/main/
RUN ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /build/target/*.jar /app/finance-api.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/finance-api.jar", "--server.port=${PORT:8080}"]
