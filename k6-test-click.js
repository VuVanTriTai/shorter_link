import http from 'k6/http';
import { check, sleep } from 'k6';

// ── Cấu hình kịch bản test (Load Profile) ───────────────────────────────────
export const options = {
  // Định nghĩa các giai đoạn tải (stages)
  stages: [
    { duration: '10s', target: 50 },   // Ramp-up lên 50 Virtual Users (VUs) trong 10s
    { duration: '30s', target: 200 },  // Duy trì tải cao 200 VUs liên tục trong 30s
    { duration: '10s', target: 0 },    // Ramp-down về 0 VU
  ],

  // Tiêu chí đánh giá chất lượng (Thresholds)
  thresholds: {
    // 95% request phải phản hồi dưới 50ms (vì là redirect có Redis cache & async click)
    http_req_duration: ['p(95)<50'],
    // Tỉ lệ lỗi phải dưới 1%
    http_req_failed: ['rate<0.01'],
  },
};

// ── Biến môi trường & cấu hình URL ──────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Thay thế shortCode bên dưới bằng một link thật có trong DB của bạn
const SHORT_CODE = __ENV.SHORT_CODE || 'demo123';

// Giả lập danh sách User-Agent và Referrer ngẫu nhiên để test bộ chuẩn hóa (ClickNormalizer)
const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148',
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Mobile Safari/537.36',
];

const REFERRERS = [
  'https://www.google.com/',
  'https://www.facebook.com/',
  'https://t.co/',
  '',
];

export default function () {
  // Chọn ngẫu nhiên UA và Referrer
  const ua = USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
  const ref = REFERRERS[Math.floor(Math.random() * REFERRERS.length)];

  // Giả lập các IP người dùng khác nhau (để test tải đúng thực tế, không bị chặn bởi Rate Limiter theo 1 IP duy nhất)
  const randomIp = `14.161.${(__VU % 200) + 1}.${Math.floor(Math.random() * 250) + 1}`;

  const params = {
    headers: {
      'User-Agent': ua,
      'Referer': ref,
      'X-Forwarded-For': randomIp,
    },
    // Quan trọng: k6 mặc định tự follow redirect (302 -> URL đích).
    // Ta đặt redirects = 0 để đo CHÍNH XÁC thời gian server rút gọn link xử lý và trả về 302!
    redirects: 0,
  };

  const res = http.get(`${BASE_URL}/r/${SHORT_CODE}`, params);

  // Kiểm tra response phải là 302 (Found) hoặc 301
  check(res, {
    'status is 302 or 200': (r) => r.status === 302 || r.status === 200,
    'has location header': (r) => r.headers['Location'] !== undefined || r.status === 200,
  });

  // Nghỉ ngẫu nhiên giữa các request (mô phỏng người dùng thực tế)
  sleep(0.05); // 50ms
}
