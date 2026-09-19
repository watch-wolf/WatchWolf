# Multi-stage: the host never needs Maven, a JDK, git, wget, curl, jq or dos2unix.
# Stage 1 -- build the executable jar.
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
# dependencies first, so a source-only change reuses the cached layer
COPY pom.xml .
RUN mvn -B dependency:go-offline -Dmaven.test.skip=true
COPY src ./src
RUN mvn -B package -Dmaven.test.skip=true

# Stage 2 -- the runtime.
FROM eclipse-temurin:17-jre

# git: the clone steps. docker CLI + the compose plugin: `docker compose` for the ServersManager
# (docker-java covers everything else, so this is the only Docker tooling we need on the CLI's own
# PATH). ca-certificates: HTTPS to spigotmc/papermc/watchwolf.dev.
#
# The CLI and compose plugin are fetched as Docker's own STATIC binaries rather than through a
# distro apt repo. eclipse-temurin's base image tracks whatever Ubuntu release is current --
# already 26.04 "resolute" as of this writing -- and download.docker.com's apt repos lag behind a
# brand-new codename by months (and were never going to have a *Debian* codename to begin with,
# which the previous version of this file asked for on an Ubuntu base -- two independent ways for
# a codename-pinned repo to 404). The static tarballs are versioned by Docker's own release, not
# tied to any distro codename, so a base-image bump can never break this step again.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git \
 && arch="$(dpkg --print-architecture)" \
 && case "$arch" in amd64) docker_arch=x86_64 ;; arm64) docker_arch=aarch64 ;; \
      *) echo "unsupported architecture: $arch" >&2; exit 1 ;; esac \
 && docker_version="$(curl -fsSL https://download.docker.com/linux/static/stable/${docker_arch}/ \
      | grep -oE "docker-[0-9]+\.[0-9]+\.[0-9]+\.tgz" | sort -V | tail -1)" \
 && curl -fsSL "https://download.docker.com/linux/static/stable/${docker_arch}/${docker_version}" \
      -o /tmp/docker.tgz \
 && tar -xzf /tmp/docker.tgz -C /tmp \
 && install -m 0755 /tmp/docker/docker /usr/local/bin/docker \
 && rm -rf /tmp/docker.tgz /tmp/docker \
 && mkdir -p /usr/local/lib/docker/cli-plugins \
 && curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${docker_arch}" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose \
 && chmod 0755 /usr/local/lib/docker/cli-plugins/docker-compose \
 && apt-get purge -y --auto-remove curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/watchwolf-cli.jar /opt/watchwolf/watchwolf-cli.jar

# The launcher runs us with --user "$(id -u):$(id -g)", so there is no fixed user here; anything
# we write goes to the identity-mounted install base, which that uid already owns.
# HOME is supplied by the launcher so ci/tests.sh's "$HOME/.m2" resolves to the host's.
ENV WW_IN_CONTAINER=1
ENTRYPOINT ["java", "-jar", "/opt/watchwolf/watchwolf-cli.jar"]
