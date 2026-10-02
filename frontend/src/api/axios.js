import axios from 'axios';

const api = axios.create({
    baseURL: 'http://localhost:8080',
    withCredentials: true, // QUAN TRỌNG: Tự động gửi và nhận HttpOnly Cookie (chứa refreshToken)
});

// Request Interceptor: Tự động gắn accessToken từ localStorage vào header Authorization
api.interceptors.request.use(
    (config) => {
        const token = localStorage.getItem('token');
        if (token && token !== 'undefined' && token !== 'null') {
            config.headers.Authorization = `Bearer ${token}`;
        } else {
            delete config.headers.Authorization;
        }
        return config;
    },
    (error) => Promise.reject(error)
);

// Response Interceptor: Tự động bắt lỗi 401 Unauthorized và refresh token
let isRefreshing = false;
let failedQueue = [];

const processQueue = (error, token = null) => {
    failedQueue.forEach((prom) => {
        if (error) {
            prom.reject(error);
        } else {
            prom.resolve(token);
        }
    });
    failedQueue = [];
};

api.interceptors.response.use(
    (response) => response,
    async (error) => {
        const originalRequest = error.config;

        // Nếu lỗi xảy ra ở chính các API auth (/login, /register, /refresh) thì không retry để tránh lặp vô tận
        if (originalRequest?.url?.includes('/api/auth/')) {
            return Promise.reject(error);
        }

        // Bắt lỗi 401 khi Access Token hết hạn
        if (error.response?.status === 401 && !originalRequest._retry) {
            if (isRefreshing) {
                return new Promise((resolve, reject) => {
                    failedQueue.push({ resolve, reject });
                })
                    .then((token) => {
                        originalRequest.headers.Authorization = `Bearer ${token}`;
                        return api(originalRequest);
                    })
                    .catch((err) => Promise.reject(err));
            }

            originalRequest._retry = true;
            isRefreshing = true;

            try {
                // Gọi API refresh token (HttpOnly Cookie được đính kèm tự động nhờ withCredentials)
                const res = await axios.post('http://localhost:8080/api/auth/refresh', {}, {
                    withCredentials: true,
                });

                const newToken = res.data.accessToken || res.data.token;
                if (newToken) {
                    localStorage.setItem('token', newToken);
                    api.defaults.headers.common.Authorization = `Bearer ${newToken}`;
                    processQueue(null, newToken);
                    originalRequest.headers.Authorization = `Bearer ${newToken}`;
                    return api(originalRequest);
                }
            } catch (refreshError) {
                processQueue(refreshError, null);
                localStorage.removeItem('token');
                localStorage.removeItem('username');
                if (window.location.pathname !== '/login') {
                    window.location.href = '/login';
                }
                return Promise.reject(refreshError);
            } finally {
                isRefreshing = false;
            }
        }

        return Promise.reject(error);
    }
);

export default api;