# The tool as an image. Build the jar first:
#
#   mvn package -DskipTests
#   docker build -t bdd-project .
#
# Run it on a workspace mounted at /workspace. Sources that declare a container image start it
# through Testcontainers, which needs the Docker socket of the host:
#
#   docker run --rm -v "$PWD/examples/postgresql:/workspace" -v /var/run/docker.sock:/var/run/docker.sock \
#       bdd-project run /workspace --profile local
FROM eclipse-temurin:21-jre

COPY cli/target/bdd-project.jar /opt/bdd-project/bdd-project.jar

WORKDIR /workspace
ENTRYPOINT ["java", "-jar", "/opt/bdd-project/bdd-project.jar"]
CMD ["run", "/workspace"]
