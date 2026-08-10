###################
# STAGE 1: builder
###################

FROM node:22-bullseye AS builder

ARG MB_EDITION=oss
ARG VERSION
# Set to "true" to skip building the Embedding SDK bundle. Its rspack config shells out to
# `git rev-parse` for build info, but .dockerignore excludes .git from the build context — so an
# `MB_EDITION=ee` build fails here unless the SDK is skipped or .git is shipped into the image.
# Empty (the default) keeps the SDK in the build.
ARG SKIP_EMBEDDING_SDK

WORKDIR /home/node

RUN apt-get update && apt-get upgrade -y && apt-get install wget apt-transport-https gpg curl git -y \
    && wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null \
    && echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | tee /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt install temurin-25-jdk -y \
    && curl -fsSL --retry 5 --retry-all-errors -O https://download.clojure.org/install/linux-install-1.12.0.1488.sh \
    && chmod +x linux-install-1.12.0.1488.sh \
    && ./linux-install-1.12.0.1488.sh \
    && curl -fsSL --retry 5 --retry-all-errors https://astral.sh/uv/install.sh | sh

ENV PATH="/root/.local/bin:$PATH"

COPY . .

# version is pulled from git, but git doesn't trust the directory due to different owners
RUN git config --global --add safe.directory /home/node

# install bun for frontend dependencies
RUN npm install -g bun

# install frontend dependencies
# Cypress is an E2E-test-only dep; its postinstall pulls a ~250MB binary that
# regularly truncates mid-download and fails the build. The image never runs Cypress.
RUN CYPRESS_INSTALL_BINARY=0 bun install --frozen-lockfile

# The Maven/gitlibs caches live on a BuildKit cache mount so a build killed mid-download (flaky
# links sever these multi-MB transfers) resumes from what it already fetched instead of re-pulling
# ~1GB of deps on every retry. Nothing from the mount ends up in the image.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    INTERACTIVE=false CI=true SKIP_EMBEDDING_SDK=$SKIP_EMBEDDING_SDK MB_EDITION=$MB_EDITION bin/build.sh :version ${VERSION}

# ###################
# # STAGE 2: runner
# ###################

## Remember that this runner image needs to be the same as bin/docker/Dockerfile with the exception that this one grabs the
## jar from the previous stage rather than the local build

FROM eclipse-temurin:25-jre-alpine AS runner

ENV FC_LANG=en-US LC_CTYPE=en_US.UTF-8

# copy certs before the RUN so keytool can import them
COPY bin/docker/DigiCertGlobalRootG2.crt.pem /app/certs/DigiCertGlobalRootG2.crt.pem

# dependencies
RUN apk add -U bash fontconfig curl font-noto font-noto-arabic font-noto-hebrew font-noto-cjk java-cacerts && \
    apk upgrade && \
    rm -rf /var/cache/apk/* && \
    mkdir -p /app/certs && \
    curl https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o /app/certs/rds-combined-ca-bundle.pem  && \
    /opt/java/openjdk/bin/keytool -noprompt -import -trustcacerts -alias aws-rds -file /app/certs/rds-combined-ca-bundle.pem -keystore /etc/ssl/certs/java/cacerts -keypass changeit -storepass changeit && \
    /opt/java/openjdk/bin/keytool -noprompt -import -trustcacerts -alias azure-cert -file /app/certs/DigiCertGlobalRootG2.crt.pem -keystore /etc/ssl/certs/java/cacerts -keypass changeit -storepass changeit && \
    mkdir -p /plugins && chmod a+rwx /plugins

# add Metabase script and uberjar
COPY --from=builder /home/node/target/uberjar/metabase.jar /app/
COPY bin/docker/run_metabase.sh /app/

# expose our default runtime port
EXPOSE 3000

# run it
ENTRYPOINT ["/app/run_metabase.sh"]
