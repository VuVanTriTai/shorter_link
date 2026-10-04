import { useState, useRef, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../api/axios';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';
const MIN_PASSWORD_LENGTH = 6;

export default function Register() {
    const [form, setForm] = useState({ username: '', password: '', confirmPassword: '' });
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);
    const [googleLoading, setGoogleLoading] = useState(false);
    const [showPassword, setShowPassword] = useState(false);
    const navigate = useNavigate();
    const usernameRef = useRef(null);
    const googleBtnRef = useRef(null);

    // Callback khi Google trả về credential (ID token) — đăng ký tự động bằng Google
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
            setError(message || 'Đăng ký bằng Google thất bại. Vui lòng thử lại.');
        } finally {
            setGoogleLoading(false);
        }
    }, [navigate]);

    useEffect(() => {
        usernameRef.current?.focus();

        if (!GOOGLE_CLIENT_ID) return;

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
                        text: 'signup_with',
                        shape: 'rectangular',
                        logo_alignment: 'left',
                    });
                }
            }
        };

        if (window.google?.accounts?.id) {
            initGoogle();
        } else {
            const interval = setInterval(() => {
                if (window.google?.accounts?.id) {
                    clearInterval(interval);
                    initGoogle();
                }
            }, 100);
            return () => clearInterval(interval);
        }
    }, [handleGoogleResponse]);

    const handleChange = (field) => (e) => {
        setForm((prev) => ({ ...prev, [field]: e.target.value }));
        if (error) setError('');
    };

    const handleSubmit = async (e) => {
        e.preventDefault();

        const username = form.username.trim();
        const { password, confirmPassword } = form;

        // Validate phía client — kiểm tra trước khi tốn 1 lượt gọi API
        if (!username || !password || !confirmPassword) {
            setError('Vui lòng nhập đầy đủ thông tin.');
            return;
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            setError(`Mật khẩu phải có ít nhất ${MIN_PASSWORD_LENGTH} ký tự.`);
            return;
        }
        if (password !== confirmPassword) {
            setError('Mật khẩu xác nhận không khớp.');
            return;
        }

        setError('');
        setLoading(true);

        try {
            await api.post('/api/auth/register', { username, password });
            alert('Đăng ký tài khoản thành công! Hãy đăng nhập.');
            navigate('/login');
        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            setError(message || 'Đăng ký thất bại. Vui lòng thử lại!');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen bg-gray-100 flex flex-col justify-center py-12 sm:px-6 lg:px-8">
            <div className="sm:mx-auto sm:w-full sm:max-w-md">
                <h2 className="mt-6 text-center text-3xl font-extrabold text-gray-900">
                    Tạo tài khoản mới
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
                                    autoComplete="new-password"
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
                            <p className="mt-1 text-xs text-gray-500">
                                Tối thiểu {MIN_PASSWORD_LENGTH} ký tự.
                            </p>
                        </div>

                        <div>
                            <label htmlFor="confirmPassword" className="block text-sm font-medium text-gray-700">
                                Xác nhận mật khẩu
                            </label>
                            <div className="mt-1">
                                <input
                                    id="confirmPassword"
                                    type={showPassword ? 'text' : 'password'}
                                    autoComplete="new-password"
                                    className="appearance-none block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm placeholder-gray-400 focus:outline-none focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
                                    value={form.confirmPassword}
                                    onChange={handleChange('confirmPassword')}
                                />
                            </div>
                        </div>

                        <div>
                            <button
                                type="submit"
                                disabled={loading}
                                className="w-full flex justify-center py-2 px-4 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {loading ? 'Đang xử lý...' : 'Đăng Ký'}
                            </button>
                        </div>
                    </form>

                    {/* Divider + Google Sign-Up */}
                    <div className="mt-6">
                        <div className="relative">
                            <div className="absolute inset-0 flex items-center">
                                <div className="w-full border-t border-gray-300" />
                            </div>
                            <div className="relative flex justify-center text-sm">
                                <span className="px-2 bg-white text-gray-500">Hoặc đăng ký bằng</span>
                            </div>
                        </div>

                        <div className="mt-4">
                            {GOOGLE_CLIENT_ID ? (
                                <>
                                    <div
                                        ref={googleBtnRef}
                                        className="flex justify-center"
                                        style={{ minHeight: '44px' }}
                                    />
                                    {googleLoading && (
                                        <div className="mt-2 text-center text-sm text-gray-500">
                                            <span className="inline-block w-4 h-4 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mr-2 align-middle" />
                                            Đang tạo tài khoản với Google...
                                        </div>
                                    )}
                                </>
                            ) : (
                                <p className="text-center text-xs text-gray-400 mt-2">
                                    Đăng ký bằng Google chưa được cấu hình.
                                </p>
                            )}
                        </div>
                    </div>

                    <div className="mt-6 text-center">
                        <span className="text-sm text-gray-600">Đã có tài khoản? </span>
                        <Link to="/login" className="text-sm font-medium text-blue-600 hover:text-blue-500">
                            Đăng nhập ngay
                        </Link>
                    </div>
                </div>
            </div>
        </div>
    );
}