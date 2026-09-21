# syntax=docker/dockerfile:1
#
# Coobi Logistics - dashboard image (MVP-10.1).
#
# Two stages, so the runtime image carries neither Node nor the dependency tree: a Node stage
# type-checks the project and builds the bundle with Vite, and the runtime stage is nginx
# serving that bundle. The build context is this directory, and `npm ci` installs from the
# lockfile that is versioned next to package.json, so the image builds the dependencies the
# repository pins rather than the newest ones.
#
# Build it through the Compose stack of MVP-10.2, from the root of the repository:
#
#   docker compose up --build
#
# The image runs as an unprivileged user (the default of its base image) on port 8080, and it
# answers the health check of MVP-10.3 on the page it serves.

# The Node line of the image is the one the lockfile is maintained with, and it satisfies the
# `engines` of package.json (`>=20.19.0`).
FROM node:24-alpine AS build

WORKDIR /workspace

# The manifest and the lockfile first, so a change to a source file reuses the layer that
# installed the dependencies.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# `build` is `vue-tsc --noEmit && vite build`: the image does not ship a bundle the type checker
# of the repository would reject.
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.5-alpine AS runtime

COPY --from=build /workspace/dist /usr/share/nginx/html

# The entry point of the image substitutes the environment of the container into every template
# below /etc/nginx/templates before nginx starts, which is what makes the proxy target of the
# dashboard a run-time value of the stack instead of something baked into the bundle.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 8080

# Real readiness check: the served page is the bundle. The check deliberately says nothing about
# `/api`: the dashboard is expected to answer - and to draw its disconnected state - while the
# API is down, so an API restart must not turn this container unhealthy.
# `wget` is the one the BusyBox of the base image provides.
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=6 \
  CMD wget -q -O /dev/null "http://127.0.0.1:8080/" || exit 1

# The base image already runs as the unprivileged nginx user; the line states the contract of
# this image rather than relying on the default of a base image.
USER nginx
