import axios from 'axios';

const api = axios.create({
    baseURL: 'http://localhost:8080',
});

api.interceptors.request.use(
    (config) => {
        const token = localStorage.getItem('token');

        // Kiểm tra kỹ token tồn tại và KHÔNG PHẢI là chuỗi "undefined" / "null"
        if (token && token !== 'undefined' && token !== 'null') {
            config.headers.Authorization = `Bearer ${token}`;
        } else {
            delete config.headers.Authorization; // Đảm bảo xóa header nếu không có token
        }

        return config;
    },
    (error) => {
        return Promise.reject(error);
    }
);

export default api;