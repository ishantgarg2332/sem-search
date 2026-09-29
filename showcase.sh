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
    echo "Checking Spring Boot backend on port 8085..."
    if ! curl -s -f http://localhost:8085/actuator/health > /dev/null; then
        echo "⚠️  Spring Boot backend is not running on port 8085."
        echo "   Starting Spring Boot in the background..."
        (cd "$(dirname "$0")" && ./mvnw spring-boot:run) &
        echo "   Waiting for backend to be ready..."
        until curl -s -f http://localhost:8085/actuator/health > /dev/null; do
            sleep 2
        done
        echo "✅ Backend is healthy and ready!"
    else
        echo "✅ Spring Boot backend is already active on port 8085."
    fi
    echo "Starting Vite dev server at http://localhost:5173..."
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
