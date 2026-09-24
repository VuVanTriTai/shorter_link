import { useState, useRef, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../api/axios';

export default function Login() {
    const [form, setForm] = useState({ username: '', password: '' });
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);
    const [showPassword, setShowPassword] = useState(false);
    const navigate = useNavigate();
    const usernameRef = useRef(null);

    // Tự động focus vào ô username khi trang vừa load
    useEffect(() => {
        usernameRef.current?.focus();
    }, []);

    const handleChange = (field) => (e) => {
        setForm((prev) => ({ ...prev, [field]: e.target.value }));
        if (error) setError(''); // xoá lỗi cũ khi người dùng bắt đầu gõ lại
    };

    const handleSubmit = async (e) => {
        e.preventDefault();

        const username = form.username.trim();
        const password = form.password;

        // Validate cơ bản phía client trước khi gọi API
        if (!username || !password) {
            setError('Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu.');
            return;
        }

        setError('');
        setLoading(true);

        try {
            const res = await api.post('/api/auth/login', { username, password });

            const token = res.data.accessToken || res.data.token;
            if (token) {
                localStorage.setItem('token', token);
            }
            localStorage.setItem('username', username);

            navigate('/dashboard');
        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            setError(message || 'Tài khoản hoặc mật khẩu không chính xác!');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen bg-gray-100 flex flex-col justify-center py-12 sm:px-6 lg:px-8">
            <div className="sm:mx-auto sm:w-full sm:max-w-md">
                <h2 className="mt-6 text-center text-3xl font-extrabold text-gray-900">
                    Đăng nhập
                </h2>
                <p className="mt-2 text-center text-sm text-gray-600">
                    Smart Link Shortener System
                </p>
            </div>

            <div className="mt-8 sm:mx-auto sm:w-full sm:max-w-md">
                <div className="bg-white py-8 px-4 shadow sm:rounded-lg sm:px-10">
                    {error && (
                        <div
                            role="alert"
                            className="mb-4 bg-red-50 border-l-4 border-red-400 p-4 text-red-700 text-sm"
                        >
                            {error}
                        </div>
                    )}

                    <form className="space-y-6" onSubmit={handleSubmit} noValidate>
                        <div>
                            <label htmlFor="username" className="block text-sm font-medium text-gray-700">
                                Tên đăng nhập
                            </label>
                            <div className="mt-1">
                                <input
                                    id="username"
                                    ref={usernameRef}
                                    type="text"
                                    autoComplete="username"
                                    className="appearance-none block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm placeholder-gray-400 focus:outline-none focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
                                    value={form.username}
                                    onChange={handleChange('username')}
                                />
                            </div>
                        </div>

                        <div>
                            <label htmlFor="password" className="block text-sm font-medium text-gray-700">
                                Mật khẩu
                            </label>
                            <div className="mt-1 relative">
                                <input
                                    id="password"
                                    type={showPassword ? 'text' : 'password'}
                                    autoComplete="current-password"
                                    className="appearance-none block w-full px-3 py-2 pr-16 border border-gray-300 rounded-md shadow-sm placeholder-gray-400 focus:outline-none focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
                                    value={form.password}
                                    onChange={handleChange('password')}
                                />
                                <button
                                    type="button"
                                    onClick={() => setShowPassword((prev) => !prev)}
                                    className="absolute inset-y-0 right-0 px-3 flex items-center text-sm text-gray-500 hover:text-gray-700"
                                >
                                    {showPassword ? 'Ẩn' : 'Hiện'}
                                </button>
                            </div>
                        </div>

                        <div>
                            <button
                                type="submit"
                                disabled={loading}
                                className="w-full flex justify-center py-2 px-4 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {loading ? 'Đang đăng nhập...' : 'Đăng Nhập'}
                            </button>
                        </div>
                    </form>

                    <div className="mt-6 text-center">
                        <span className="text-sm text-gray-600">Chưa có tài khoản? </span>
                        <Link to="/register" className="text-sm font-medium text-blue-600 hover:text-blue-500">
                            Đăng ký ngay
                        </Link>
                    </div>
                </div>
            </div>
        </div>
    );
}