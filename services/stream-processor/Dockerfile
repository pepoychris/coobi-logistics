# syntax=docker/dockerfile:1
#
# Coobi Logistics - stream-processor image (MVP-10.1).
#
# Two stages, so the runtime image carries no build tooling: a Maven stage compiles and
# repackages the executable jar, and the runtime stage copies only that jar onto a JRE. The
# build context is this service directory, which is a self-contained Maven project with its
# own wrapper, so the context holds no other service and no other service is rebuilt.
#
# Build it through the Compose stack of MVP-10.2, from the root of the repository:
#
#   docker compose up --build
#
# The image runs as an unprivileged user, and it answers the health check of MVP-10.3 on the
# Actuator health endpoint of the service.

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

# The dependencies are resolved before the sources are copied, so a change to a source file
# reuses the layers below instead of downloading the world again. The version of Maven is the
# one the wrapper of the service pins in .mvn/wrapper/maven-wrapper.properties.
COPY pom.xml ./
RUN mvn -B -ntp -Dmaven.test.skip=true dependency:go-offline

COPY src ./src

# `package` repackages the jar into an executable Spring Boot jar. `cp` fails the build when the
# package phase produced no single jar instead of copying an unexpected one.
RUN mvn -B -ntp -Dmaven.test.skip=true package \
 && cp target/*.jar /workspace/application.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

# Kafka Streams keeps the state of its two detections in a RocksDB state store, and the JNI
# library that store loads is a native dependency of the application rather than of the JVM: it
# needs the C++ runtime and the compatibility layer that lets a library built against glibc load
# into the musl-based base image. Without them the topology fails at its first state-store access
# while the container stays up - the service's health covers the broker and the database, not the
# state store - which is exactly the failure this line exists to prevent.
RUN apk add --no-cache libstdc++ gcompat

# Unprivileged user: nothing in this image is written at run time.
RUN addgroup -g 10001 -S app \
 && adduser -u 10001 -S -G app -h /app app

# The server port of the service, the one its application.yml declares. It is stated here as
# well so the health check below and the published port of Compose cannot drift from it.
ENV SERVER_PORT=8081

WORKDIR /app
COPY --from=build --chown=app:app /workspace/application.jar ./application.jar

USER app
EXPOSE 8081

# Real readiness check: the endpoint answers only once the context is started - which is after
# Flyway has applied the migrations - and it reports DOWN, with a 503, while the Kafka broker or
# the database of the service is unreachable. An unhealthy container therefore means a service
# that cannot do its job rather than one that is still warming up.
# `wget` is the one the BusyBox of the base image provides.
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

# Extra JVM options are added with the standard `JDK_JAVA_OPTIONS`, which the launcher reads
# without a shell in front of it: the JVM stays PID 1, so the SIGTERM of `docker stop` reaches
# its shutdown hook directly and the graceful shutdown of the service runs.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/application.jar"]
