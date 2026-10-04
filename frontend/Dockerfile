# ============================
# Stage 1: Build React app
# ============================
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files trước để cache npm install
COPY package.json package-lock.json ./
RUN npm ci

# Copy source và build
COPY . .

# Build args cho Vite env variables
ARG VITE_API_URL
ARG VITE_GOOGLE_CLIENT_ID

ENV VITE_API_URL=$VITE_API_URL
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID

RUN npm run build

# ============================
# Stage 2: Serve với Nginx
# ============================
FROM nginx:alpine

# Copy nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built files
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
