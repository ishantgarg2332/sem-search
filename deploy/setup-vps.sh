#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# SemSearch - Automated Cloud VPS Production Deployment Script
# Supports: Ubuntu 22.04 / 24.04 LTS, Debian 12
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     SemSearch - Cloud VPS Production Provisioning Script         ║${NC}"
echo -e "${CYAN}║   Alfresco ACS + Vector Search + Automated Let's Encrypt SSL     ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════╝${NC}"

# Check for root privileges
if [ "$EUID" -ne 0 ]; then
  echo -e "${RED}Error: This script must be run as root (use sudo).${NC}"
  exit 1
fi

# 1. Update OS packages
echo -e "\n${BLUE}[1/6] Updating system packages...${NC}"
apt-get update -y && apt-get upgrade -y
apt-get install -y curl wget git ufw apt-transport-https ca-certificates gnupg lsb-release openssl

# 2. Install Docker & Docker Compose plugin if not already installed
echo -e "\n${BLUE}[2/6] Checking Docker installation...${NC}"
if ! command -v docker &> /dev/null; then
  echo -e "Installing Docker engine..."
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc

  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null

  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable docker
  systemctl start docker
  echo -e "${GREEN}Docker installed successfully.${NC}"
else
  echo -e "${GREEN}Docker is already installed.${NC}"
fi

# 3. Configure UFW Firewall
echo -e "\n${BLUE}[3/6] Hardening Firewall (UFW)...${NC}"
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP Let'\''s Encrypt'
ufw allow 443/tcp comment 'HTTPS'
ufw --force enable
echo -e "${GREEN}Firewall enabled: SSH (22), HTTP (80), HTTPS (443) are open.${NC}"

# 4. Prompt for Production Environment Configuration
echo -e "\n${BLUE}[4/6] Production Configuration Setup...${NC}"

# Prompt Domain
read -rp "Enter your public Domain Name (e.g. search.yourcompany.com) [default: localhost]: " DOMAIN_INPUT
DOMAIN=${DOMAIN_INPUT:-localhost}

# Prompt or generate JWT Secret
GENERATED_JWT_SECRET=$(openssl rand -hex 64)
echo -e "Generated high-entropy 512-bit JWT secret."

# Prompt Database Password
GENERATED_DB_PASS=$(openssl rand -hex 16)

# Create .env file
cat <<EOF > .env
DOMAIN=${DOMAIN}
JWT_SECRET=${GENERATED_JWT_SECRET}
APP_DB_PASSWORD=${GENERATED_DB_PASS}
ALFRESCO_ADMIN_USER=admin
ALFRESCO_ADMIN_PASSWORD=admin
EOF

chmod 600 .env
echo -e "${GREEN}.env configuration generated securely.${NC}"

# 5. Build and Launch Stack
echo -e "\n${BLUE}[5/6] Building and starting SemSearch stack via Docker Compose...${NC}"
docker compose -f docker-compose.prod.yml down --remove-orphans || true
docker compose -f docker-compose.prod.yml up -d --build

# 6. Verification
echo -e "\n${BLUE}[6/6] Verifying deployment health...${NC}"
sleep 8

if curl -s http://localhost:8085/actuator/health | grep -q "UP"; then
  echo -e "\n${GREEN}════════════════════════════════════════════════════════════════════${NC}"
  echo -e "${GREEN}✓ SemSearch successfully deployed in production!${NC}"
  if [ "$DOMAIN" != "localhost" ]; then
    echo -e "${GREEN}✓ Public URL: https://${DOMAIN}${NC}"
    echo -e "${GREEN}✓ Automated SSL Certificate: Enabled via Caddy (Let's Encrypt)${NC}"
  else
    echo -e "${GREEN}✓ Local Access: http://localhost:8085 (or http://localhost)${NC}"
  fi
  echo -e "${GREEN}════════════════════════════════════════════════════════════════════${NC}"
else
  echo -e "\n${YELLOW}Stack is initializing. Check live logs with:${NC}"
  echo -e "  docker compose -f docker-compose.prod.yml logs -f"
fi
