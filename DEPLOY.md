# 🚀 Hướng Dẫn Deploy Smart Link Shortener

## Tổng Quan Kiến Trúc

```
┌─────────────┐     ┌─────────────────┐     ┌──────────────┐
│   Browser   │────▶│  Frontend (80)  │     │  PostgreSQL  │
│             │     │  Nginx + React  │     │   (5432)     │
└─────────────┘     └────────┬────────┘     └──────▲───────┘
                             │                      │
                    /api/ và /r/                     │
                             │                      │
                    ┌────────▼────────┐     ┌───────┴───────┐
                    │  Backend (8080) │────▶│    Redis      │
                    │  Spring Boot    │     │   (6379)      │
                    └─────────────────┘     └───────────────┘
```

## Bước 1: Cài Đặt Docker

### Trên WSL (Ubuntu)
```bash
# Cập nhật packages
sudo apt update && sudo apt upgrade -y

# Cài Docker
sudo apt install docker.io docker-compose-v2 -y

# Thêm user vào group docker (không cần sudo mỗi lần)
sudo usermod -aG docker $USER

# Khởi động Docker
sudo service docker start

# Kiểm tra
docker --version
docker compose version
```

### Trên Windows (Docker Desktop)
1. Tải Docker Desktop từ https://www.docker.com/products/docker-desktop
2. Cài đặt và enable WSL 2 integration
3. Khởi động Docker Desktop

## Bước 2: Chuẩn Bị File .env

```bash
# Copy file mẫu
cp .env.example .env

# Chỉnh sửa file .env
nano .env
```

**Các biến BẮT BUỘC phải thay đổi:**

| Biến | Mô tả | Ví dụ |
|------|--------|-------|
| `DB_PASSWORD` | Mật khẩu PostgreSQL | `MyStr0ngP@ss!` |
| `JWT_SECRET` | Secret key cho JWT (≥32 ký tự) | Chạy: `openssl rand -hex 32` |
| `GOOGLE_OAUTH_CLIENT_ID` | Google OAuth Client ID | `xxx.apps.googleusercontent.com` |
| `APP_DOMAINS` | Domain(s) của bạn | `your-domain.com,www.your-domain.com` |

## Bước 3: Chạy Với Docker Compose

### Chạy lần đầu (build + start)
```bash
# Build và khởi động tất cả services
docker compose up -d --build

# Xem logs
docker compose logs -f

# Xem logs của từng service
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f postgres
```

### Kiểm tra services
```bash
# Xem trạng thái
docker compose ps

# Kiểm tra backend
curl http://localhost:8080/api/auth/refresh

# Mở trình duyệt
# Frontend: http://localhost
# Backend API: http://localhost:8080
```

### Dừng / Khởi động lại
```bash
# Dừng
docker compose down

# Dừng và xóa dữ liệu (CẢNH BÁO: mất hết data)
docker compose down -v

# Khởi động lại
docker compose up -d

# Rebuild sau khi thay đổi code
docker compose up -d --build
```

## Bước 4: Deploy Lên VPS (Internet)

### 4.1 Chọn VPS Provider
Các nhà cung cấp phổ biến (có free tier hoặc giá rẻ):

| Provider | Giá | RAM | Ghi chú |
|----------|-----|-----|---------|
| **Oracle Cloud** | **MIỄN PHÍ** | 1-24GB | Free Tier vĩnh viễn, rất tốt |
| DigitalOcean | $4/tháng | 512MB | Đơn giản, dễ dùng |
| Vultr | $3.5/tháng | 512MB | Nhiều datacenter |
| Hetzner | €3.79/tháng | 2GB | Giá tốt nhất |
| AWS Lightsail | $3.5/tháng | 512MB | Quen thuộc AWS |

### 4.2 Kết nối VPS
```bash
# SSH vào VPS
ssh root@YOUR_VPS_IP

# Hoặc với key file
ssh -i ~/.ssh/your_key root@YOUR_VPS_IP
```

### 4.3 Setup trên VPS
```bash
# Cài Docker
sudo apt update && sudo apt install docker.io docker-compose-v2 git -y
sudo systemctl start docker
sudo systemctl enable docker

# Clone project
git clone https://github.com/YOUR_USERNAME/smart-link-shortener.git
cd smart-link-shortener

# Tạo file .env
cp .env.example .env
nano .env
# --> Điền các biến môi trường

# Build và chạy
docker compose up -d --build

# Kiểm tra
docker compose ps
docker compose logs -f
```

### 4.4 Cấu hình Domain + HTTPS (SSL)

#### Trỏ domain về VPS
1. Mua domain (Namecheap, GoDaddy, Tenten.vn...)
2. Vào DNS settings, tạo A record:
   - `@` → `YOUR_VPS_IP`
   - `www` → `YOUR_VPS_IP`

#### Thêm HTTPS với Certbot
Thay `nginx.conf` trong frontend để hỗ trợ SSL:

```bash
# Cài Certbot trên VPS
sudo apt install certbot -y

# Lấy SSL certificate
sudo certbot certonly --standalone -d your-domain.com -d www.your-domain.com

# Certificate sẽ ở:
# /etc/letsencrypt/live/your-domain.com/fullchain.pem
# /etc/letsencrypt/live/your-domain.com/privkey.pem
```

Sau đó thêm vào `docker-compose.yml`:
```yaml
frontend:
  # ... config hiện tại ...
  volumes:
    - /etc/letsencrypt:/etc/letsencrypt:ro
  ports:
    - "80:80"
    - "443:443"
```

### 4.5 Cập nhật Google OAuth
Sau khi có domain, vào Google Cloud Console:
1. APIs & Services → Credentials
2. Chỉnh OAuth 2.0 Client:
   - **Authorized JavaScript origins**: `https://your-domain.com`
   - **Authorized redirect URIs**: `https://your-domain.com`

### 4.6 Cập nhật .env trên VPS
```bash
# Sửa file .env
nano .env

# Thêm/sửa:
APP_DOMAINS=your-domain.com,www.your-domain.com
VITE_API_URL=https://your-domain.com
```

Rồi rebuild:
```bash
docker compose up -d --build
```

## Lệnh Hữu Ích

```bash
# Xem tất cả containers
docker ps -a

# Vào terminal của container
docker exec -it sls-backend sh
docker exec -it sls-postgres psql -U postgres -d smart_link_shortener

# Xem disk usage
docker system df

# Dọn dẹp images cũ
docker system prune -a

# Backup database
docker exec sls-postgres pg_dump -U postgres smart_link_shortener > backup.sql

# Restore database
cat backup.sql | docker exec -i sls-postgres psql -U postgres smart_link_shortener
```

## Troubleshooting

### Backend không start
```bash
docker compose logs backend
# Kiểm tra: DB_PASSWORD, JWT_SECRET đã set chưa?
```

### Frontend 502 Bad Gateway
```bash
# Backend chưa sẵn sàng, đợi 30-60s
docker compose logs -f backend
# Đợi thấy "Started SmartLinkShortenerApplication"
```

### Không kết nối được DB
```bash
# Kiểm tra postgres đang chạy
docker compose ps postgres
docker compose logs postgres
```
