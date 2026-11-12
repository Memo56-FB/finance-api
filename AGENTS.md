# financeApi

- Base package: `dev.memofb.finance`; Java 25, Spring Boot 4.1.2, Maven Wrapper.
- Keep code grouped by feature. The current feature is `auth`.
- Authentication uses Spring Security sessions and JDBC. Keep CSRF enabled and credentials out of source and logs.
- PostgreSQL schema changes go in new Flyway migrations under `src/main/resources/db/migration`.
- Run `./mvnw verify` for unit and PostgreSQL integration tests. Integration tests require Docker.
- Project skills live in `.agents/skills`: `create-spring-boot-java-project`, `java-springboot`, `spring-boot-testing`, `spring-security`. Read the relevant `SKILL.md` before changing its area.
