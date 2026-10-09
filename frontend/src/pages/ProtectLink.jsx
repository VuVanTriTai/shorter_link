import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import api from '../api/axios';

export default function ProtectLink() {
    const { shortCode } = useParams();
    const [password, setPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [linkInfo, setLinkInfo] = useState(null);

    useEffect(() => {
        // Fetch thông tin cơ bản của link (nếu cần)
        const fetchInfo = async () => {
            try {
                const res = await api.get(`/r/${shortCode}/info`);
                setLinkInfo(res.data);
            } catch (err) {
                // Link không tồn tại hoặc đã hết hạn
                if (err.response?.status === 404) {
                    setError('Liên kết này không tồn tại hoặc đã bị xoá.');
                } else {
                    setError('Không thể kết nối đến máy chủ. Vui lòng thử lại sau.');
                }
            }
        };
        if (shortCode) {
            fetchInfo();
        }
    }, [shortCode]);

    const handleUnlock = async (e) => {
        e.preventDefault();
        setError('');

        if (!password.trim()) {
            setError('Vui lòng nhập mật khẩu truy cập.');
            return;
        }

        setLoading(true);
        try {
            const res = await api.post(`/r/${shortCode}/unlock`, {
                password: password.trim(),
            });

            if (res.data?.success && res.data?.redirectUrl) {
                // Chuyển hướng qua server với HMAC unlock token
                // Mật khẩu KHÔNG xuất hiện trên URL (chống leak qua log/history/Referer)
                window.location.replace(res.data.redirectUrl);
            } else if (res.data?.success && res.data?.originalUrl) {
                // Fallback cho backward compatibility
                window.location.replace(res.data.originalUrl);
            } else {
                setError('Có lỗi xảy ra, vui lòng thử lại.');
            }
        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            if (err.response?.status === 401) {
                setError(message || 'Mật khẩu không chính xác. Vui lòng thử lại!');
            } else if (err.response?.status === 429) {
                setError(message || 'Bạn đã thử sai quá nhiều lần. Vui lòng chờ 10 giây!');
            } else {
                setError(message || 'Không thể mở khoá liên kết. Vui lòng thử lại sau.');
            }
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-900 via-indigo-950 to-slate-900 p-4">
            <div className="w-full max-w-md bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl p-8 shadow-2xl text-white">
                {/* Icon khoá bảo vệ */}
                <div className="flex justify-center mb-6">
                    <div className="w-20 h-20 bg-gradient-to-tr from-amber-500 to-amber-300 rounded-2xl flex items-center justify-center shadow-lg shadow-amber-500/30 transform hover:scale-105 transition duration-300">
                        <svg className="w-10 h-10 text-slate-900" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.2">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
                        </svg>
                    </div>
                </div>

                {/* Tiêu đề */}
                <h1 className="text-2xl font-bold text-center tracking-tight mb-2">
                    Liên kết được bảo vệ
                </h1>
                <p className="text-sm text-slate-300 text-center mb-6">
                    Liên kết này yêu cầu mật khẩu để truy cập. Vui lòng nhập mật khẩu bên dưới để tiếp tục.
                </p>

                {/* Thông báo lỗi */}
                {error && (
                    <div className="mb-5 p-3.5 bg-rose-500/20 border border-rose-500/40 rounded-xl text-rose-200 text-sm flex items-start gap-2.5 animate-shake">
                        <svg className="w-5 h-5 flex-shrink-0 mt-0.5 text-rose-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                        </svg>
                        <span>{error}</span>
                    </div>
                )}

                {/* Form nhập password */}
                <form onSubmit={handleUnlock} className="space-y-4">
                    <div>
                        <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider mb-2">
                            Mật khẩu truy cập
                        </label>
                        <div className="relative">
                            <input
                                type={showPassword ? 'text' : 'password'}
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                placeholder="Nhập mật khẩu..."
                                autoFocus
                                disabled={loading}
                                className="w-full px-4 py-3 bg-white/5 border border-white/20 rounded-xl text-white placeholder-slate-400 text-sm focus:outline-none focus:ring-2 focus:ring-amber-400 focus:border-transparent transition pr-11"
                            />
                            <button
                                type="button"
                                onClick={() => setShowPassword(!showPassword)}
                                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-white transition p-1"
                                tabIndex={-1}
                            >
                                {showPassword ? (
                                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l18 18" />
                                    </svg>
                                ) : (
                                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                                    </svg>
                                )}
                            </button>
                        </div>
                    </div>

                    <button
                        type="submit"
                        disabled={loading}
                        className="w-full py-3 px-4 bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-600 hover:to-amber-700 text-slate-950 font-bold rounded-xl shadow-lg shadow-amber-500/25 transition duration-200 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed mt-2"
                    >
                        {loading ? (
                            <>
                                <svg className="animate-spin w-5 h-5 text-slate-950" fill="none" viewBox="0 0 24 24">
                                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path>
                                </svg>
                                <span>Đang xác thực...</span>
                            </>
                        ) : (
                            <>
                                <span>Mở khoá & Tiếp tục</span>
                                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M14 5l7 7m0 0l-7 7m7-7H3" />
                                </svg>
                            </>
                        )}
                    </button>
                </form>

                {/* Footer copyright */}
                <div className="mt-8 text-center text-xs text-slate-500">
                    Smart Link Shortener • Bảo mật và riêng tư
                </div>
            </div>
        </div>
    );
}
