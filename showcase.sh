#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "    SemSearch — Alfresco Semantic Search Showcase"
echo "=========================================================="

echo ""
echo "Select showcase mode:"
echo "1) Local Showcase (Open Browser at http://localhost:5173)"
echo "2) Instant Public HTTPS URL via Cloudflare Tunnel (Zero config, free)"
echo "3) Build & Run Production Docker Stack (docker-compose.prod.yml)"
echo "4) Package Single Executable Spring Boot + UI JAR"
echo ""

read -p "Enter choice [1-4]: " CHOICE

case $CHOICE in
  1)
    echo "Starting Vite dev server if not already running..."
    cd "$(dirname "$0")/frontend"
    npm run dev
    ;;
  2)
    if ! command -v cloudflared &> /dev/null; then
        echo "cloudflared is not installed."
        echo "Install it with: brew install cloudflared"
        exit 1
    fi
    echo "Creating public HTTPS tunnel to port 8085..."
    cloudflared tunnel --url http://localhost:8085
    ;;
  3)
    echo "Starting production Docker stack..."
    cd "$(dirname "$0")"
    docker compose -f docker-compose.prod.yml up --build -d
    echo "Stack is running! Check status with: docker compose -f docker-compose.prod.yml ps"
    ;;
  4)
    echo "Building frontend and packaging single executable JAR..."
    cd "$(dirname "$0")/frontend"
    npm run build
    cd ..
    mkdir -p src/main/resources/static
    cp -r frontend/dist/* src/main/resources/static/
    JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw clean package -DskipTests
    echo ""
    echo "Packaging complete! Run with:"
    echo "java -jar target/semantic-search-0.0.1-SNAPSHOT.jar"
    ;;
  *)
    echo "Invalid option."
    exit 1
    ;;
esac
