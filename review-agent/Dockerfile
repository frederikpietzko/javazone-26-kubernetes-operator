FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY . .
RUN ./kotlin package --module review-agent --platform jvm --format executable-jar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --no-install-recommends --yes \
        bash \
        ca-certificates \
        curl \
        findutils \
        gawk \
        git \
        gh \
        grep \
        jq \
        openssh-client \
        sed \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 --shell /bin/bash app
WORKDIR /app
COPY --from=build --chown=app:app \
    /workspace/build/tasks/_review-agent_executableJarJvm/review-agent-jvm-executable.jar \
    /app/review-agent.jar
USER app

ENTRYPOINT ["java", "-jar", "/app/review-agent.jar"]
