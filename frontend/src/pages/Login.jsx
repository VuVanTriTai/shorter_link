import { useState, useRef, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../api/axios';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

export default function Login() {
    const [form, setForm] = useState({ username: '', password: '' });
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);
    const [googleLoading, setGoogleLoading] = useState(false);
    const [showPassword, setShowPassword] = useState(false);
    const navigate = useNavigate();
    const usernameRef = useRef(null);
    const googleBtnRef = useRef(null);

    // Callback khi Google trả về credential (ID token)
    const handleGoogleResponse = useCallback(async (response) => {
        if (!response?.credential) {
            setError('Không nhận được thông tin từ Google. Vui lòng thử lại.');
            return;
        }

        setGoogleLoading(true);
        setError('');

        try {
            const res = await api.post('/api/auth/google', { idToken: response.credential });
            const token = res.data.accessToken || res.data.token;
            if (token) {
                localStorage.setItem('token', token);
            }
            // Giải mã payload từ Google ID token để lấy email làm username hiển thị
            try {
                const payload = JSON.parse(atob(response.credential.split('.')[1]));
                localStorage.setItem('username', payload.email || payload.name || 'Google User');
            } catch {
                localStorage.setItem('username', 'Google User');
            }
            navigate('/dashboard');
        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            setError(message || 'Đăng nhập bằng Google thất bại. Vui lòng thử lại.');
        } finally {
            setGoogleLoading(false);
        }
    }, [navigate]);

    // Khởi tạo Google Identity Services khi component mount
    useEffect(() => {
        usernameRef.current?.focus();

        if (!GOOGLE_CLIENT_ID) {
            console.warn('VITE_GOOGLE_CLIENT_ID chưa được cấu hình');
            return;
        }

        // Đợi Google SDK load xong
        const initGoogle = () => {
            if (window.google?.accounts?.id) {
                window.google.accounts.id.initialize({
                    client_id: GOOGLE_CLIENT_ID,
                    callback: handleGoogleResponse,
                    auto_select: false,
                    cancel_on_tap_outside: true,
                });

                if (googleBtnRef.current) {
                    window.google.accounts.id.renderButton(googleBtnRef.current, {
                        theme: 'outline',
                        size: 'large',
                        width: '100%',
                        text: 'signin_with',
                        shape: 'rectangular',
                        logo_alignment: 'left',
                    });
                }
            }
        };

        // Nếu SDK đã load, init ngay; nếu chưa thì chờ
        if (window.google?.accounts?.id) {
            initGoogle();
        } else {
            const interval = setInterval(() => {
                if (window.google?.accounts?.id) {
                    clearInterval(interval);
                    initGoogle();
                }
            }, 100);
            // Dọn dẹp nếu component unmount trước khi SDK load xong
            return () => clearInterval(interval);
        }
    }, [handleGoogleResponse]);

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

                    {/* Divider */}
                    <div className="mt-6">
                        <div className="relative">
                            <div className="absolute inset-0 flex items-center">
                                <div className="w-full border-t border-gray-300" />
                            </div>
                            <div className="relative flex justify-center text-sm">
                                <span className="px-2 bg-white text-gray-500">Hoặc tiếp tục với</span>
                            </div>
                        </div>

                        {/* Google Sign-In Button */}
                        <div className="mt-4">
                            {GOOGLE_CLIENT_ID ? (
                                <>
                                    {/* Container cho nút Google được render bởi GIS SDK */}
                                    <div
                                        ref={googleBtnRef}
                                        className="flex justify-center"
                                        style={{ minHeight: '44px' }}
                                    />
                                    {googleLoading && (
                                        <div className="mt-2 text-center text-sm text-gray-500">
                                            <span className="inline-block w-4 h-4 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mr-2 align-middle" />
                                            Đang xác thực với Google...
                                        </div>
                                    )}
                                </>
                            ) : (
                                <p className="text-center text-xs text-gray-400 mt-2">
                                    Đăng nhập bằng Google chưa được cấu hình.
                                </p>
                            )}
                        </div>
                    </div>

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