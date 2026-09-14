# VITE_API_BASE_URL is baked into the bundle at build time - browser code can't
# read compose's runtime env - so it is a build arg, not an environment entry.
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# nginx, not `vite preview`, which Vite documents as smoke-test-only.
FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
# Falls unmatched paths back to index.html, so client-side routes survive a refresh.
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
