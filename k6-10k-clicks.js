import http from 'k6/http';
import { check } from 'k6';

// ── Kịch bản: Đúng 10,000 clicks chạy trên 20 Virtual Users (giống hệt Unit Test) ──
export const options = {
  scenarios: {
    ten_thousand_clicks: {
      executor: 'shared-iterations',
      vus: 20,             // 20 luồng đồng thời
      iterations: 10000,   // Đúng 10,000 lượt click
      maxDuration: '1m',   // Hết 10,000 click là k6 tự dừng ngay lập tức
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<50'], // 95% request phản hồi < 50ms
    http_req_failed: ['rate<0.01'],  // Tỉ lệ lỗi < 1%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SHORT_CODE = __ENV.SHORT_CODE || 'mytest513';

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
  const ua = USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
  const ref = REFERRERS[Math.floor(Math.random() * REFERRERS.length)];

  // Giả lập các IP khác nhau để không kích hoạt Rate Limit của 1 IP duy nhất
  const randomIp = `14.161.${(__VU % 200) + 1}.${Math.floor(Math.random() * 250) + 1}`;

  const params = {
    headers: {
      'User-Agent': ua,
      'Referer': ref,
      'X-Forwarded-For': randomIp,
    },
    // redirects: 0 để đo đúng latency server xử lý và trả về 302 Redirect
    redirects: 0,
  };

  const res = http.get(`${BASE_URL}/r/${SHORT_CODE}`, params);

  check(res, {
    'status is 302 or 200': (r) => r.status === 302 || r.status === 200,
    'has location header': (r) => r.headers['Location'] !== undefined || r.status === 200,
  });
}
