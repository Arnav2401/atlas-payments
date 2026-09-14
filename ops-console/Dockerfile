# Build-time only: VITE_API_BASE_URL is baked into the static JS bundle by
# `vite build` (browser code cannot read compose's runtime environment the
# way a server process can), so it must be a build arg here, not a runtime
# `environment:` entry in docker-compose.yml like the other services get.
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# nginx, not `vite preview` - preview is documented by Vite itself as unfit
# for anything beyond local smoke-testing a build.
FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
# A single-page app needs every unmatched path to fall back to index.html so
# React Router-style client-side routes (none exist yet, but a real ops
# console would grow some) don't 404 on refresh - this repo has no routing
# yet, so today it only matters for a literal 404, but it costs nothing to
# have correct from the start.
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
