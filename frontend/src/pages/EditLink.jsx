import { useState, useEffect } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import api from '../api/axios';

export default function EditLink() {
    const { shortCode } = useParams();
    const [originalUrl, setOriginalUrl] = useState('');
    const [customCode, setCustomCode] = useState('');
    const [expiresAt, setExpiresAt] = useState('');
    const [active, setActive] = useState(true);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState('');
    const [successMsg, setSuccessMsg] = useState('');
    const navigate = useNavigate();

    useEffect(() => {
        const token = localStorage.getItem('token');
        if (!token || token === 'undefined' || token === 'null') {
            navigate('/login');
            return;
        }
        fetchCurrentLink();
    }, [shortCode, navigate]);

    const fetchCurrentLink = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.get('/api/links');
            const links = Array.isArray(res.data) ? res.data : [];
            const found = links.find((l) => l.shortCode === shortCode);
            if (!found) {
                setError('Không tìm thấy liên kết hoặc bạn không có quyền chỉnh sửa.');
            } else {
                setOriginalUrl(found.originalUrl || '');
                setCustomCode(found.shortCode || '');
                setActive(found.active !== false);
                if (found.expiresAt) {
                    // Backend trả về LocalDateTime dạng "2026-10-01T23:00:00" (không có timezone)
                    // Cắt trực tiếp 16 ký tự đầu để lấy YYYY-MM-DDTHH:mm (KHÔNG qua new Date() để tránh UTC offset)
                    setExpiresAt(found.expiresAt.substring(0, 16));
                } else {
                    setExpiresAt('');
                }
            }
        } catch (err) {
            setError('Không thể tải thông tin liên kết. Vui lòng thử lại!');
        } finally {
            setLoading(false);
        }
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        setSuccessMsg('');

        let url = originalUrl.trim();
        if (!url) {
            setError('Vui lòng nhập đường link gốc.');
            return;
        }

        if (!/^https?:\/\//i.test(url)) {
            url = 'https://' + url;
        }

        try {
            new URL(url);
        } catch {
            setError('Định dạng URL không hợp lệ. Ví dụ: https://example.com');
            return;
        }

        const payload = {
            originalUrl: url,
            customCode: customCode.trim() || null,
            // Gửi thẳng chuỗi datetime-local (YYYY-MM-DDTHH:mm) không chuyển sang ISO UTC
            // để tránh bị lệch múi giờ (VN = UTC+7, new Date().toISOString() sẽ trừ đi 7 tiếng)
            expiresAt: expiresAt || null,
            active: active,
        };

        setSubmitting(true);

        try {
            const res = await api.put(`/api/links/${shortCode}`, payload);
            setSuccessMsg('Cập nhật liên kết thành công!');
            setTimeout(() => {
                navigate('/dashboard');
            }, 1200);
        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            setError(message || 'Cập nhật thất bại. Vui lòng kiểm tra lại thông tin!');
        } finally {
            setSubmitting(false);
        }
    };

    if (loading) {
        return (
            <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
                <div className="text-gray-500 font-medium">Đang tải thông tin liên kết...</div>
            </div>
        );
    }

    return (
        <div className="min-h-screen bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
            <div className="max-w-2xl mx-auto">
                <div className="mb-6">
                    <Link
                        to="/dashboard"
                        className="inline-flex items-center text-sm font-medium text-blue-600 hover:text-blue-500"
                    >
                        ← Quay lại Dashboard
                    </Link>
                </div>

                <div className="bg-white shadow rounded-xl p-6 sm:p-8">
                    <div className="border-b border-gray-200 pb-5 mb-6">
                        <h2 className="text-2xl font-bold text-gray-900">Chỉnh sửa liên kết</h2>
                        <p className="text-sm text-gray-500 mt-1">
                            Cập nhật URL đích, mã rút gọn tùy chỉnh hoặc thời gian hết hạn cho mã: <strong className="text-blue-600 font-mono">/{shortCode}</strong>
                        </p>
                    </div>

                    {error && (
                        <div className="mb-4 bg-red-50 border-l-4 border-red-400 p-4 text-red-700 text-sm rounded">
                            {error}
                        </div>
                    )}

                    {successMsg && (
                        <div className="mb-4 bg-green-50 border-l-4 border-green-400 p-4 text-green-700 text-sm rounded">
                            {successMsg} Đang chuyển hướng về Dashboard...
                        </div>
                    )}

                    <form onSubmit={handleSubmit} className="space-y-6">
                        <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">
                                Đường link gốc (Original URL) <span className="text-red-500">*</span>
                            </label>
                            <input
                                type="text"
                                required
                                value={originalUrl}
                                onChange={(e) => setOriginalUrl(e.target.value)}
                                placeholder="https://example.com/very-long-url"
                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
                            />
                        </div>

                        <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">
                                Mã rút gọn tùy chỉnh (Custom Code)
                            </label>
                            <div className="flex rounded-lg shadow-sm">
                                <span className="inline-flex items-center px-3 rounded-l-lg border border-r-0 border-gray-300 bg-gray-50 text-gray-500 text-sm">
                                    {import.meta.env.VITE_API_URL || 'http://localhost:8080'}/r/
                                </span>
                                <input
                                    type="text"
                                    value={customCode}
                                    onChange={(e) => setCustomCode(e.target.value)}
                                    placeholder="my-custom-code"
                                    className="flex-1 px-4 py-2 border border-gray-300 rounded-r-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none font-mono text-sm"
                                />
                            </div>
                            <p className="mt-1 text-xs text-gray-500">
                                Chỉ cho phép từ 3-30 ký tự chữ và số (a-z, A-Z, 0-9), không chứa ký tự đặc biệt.
                            </p>
                        </div>

                        <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">
                                Thời gian hết hạn (Tùy chọn)
                            </label>
                            <input
                                type="datetime-local"
                                value={expiresAt}
                                onChange={(e) => setExpiresAt(e.target.value)}
                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none text-sm"
                            />
                            <p className="mt-1 text-xs text-gray-500">
                                Để trống nếu muốn liên kết tồn tại vĩnh viễn (không hết hạn).
                            </p>
                        </div>

                        {/* Trạng thái hoạt động (Active Toggle) */}
                        <div className="flex items-center justify-between p-4 bg-gray-50 border border-gray-200 rounded-lg">
                            <div>
                                <label htmlFor="active-toggle" className="text-sm font-medium text-gray-800 block cursor-pointer">
                                    Trạng thái hoạt động (Active)
                                </label>
                                <span className="text-xs text-gray-500">
                                    {active ? '🟢 Liên kết đang bật và sẵn sàng chuyển hướng.' : '⏸️ Liên kết đang tắt, người truy cập sẽ nhận thông báo tạm ngưng.'}
                                </span>
                            </div>
                            <button
                                type="button"
                                id="active-toggle"
                                onClick={() => setActive(!active)}
                                className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none ${
                                    active ? 'bg-blue-600' : 'bg-gray-300'
                                }`}
                            >
                                <span
                                    className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                                        active ? 'translate-x-5' : 'translate-x-0'
                                    }`}
                                />
                            </button>
                        </div>

                        <div className="flex justify-end gap-3 pt-4 border-t border-gray-100">
                            <button
                                type="button"
                                onClick={() => navigate('/dashboard')}
                                className="px-4 py-2 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50"
                            >
                                Hủy bỏ
                            </button>
                            <button
                                type="submit"
                                disabled={submitting}
                                className="px-5 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50 transition shadow"
                            >
                                {submitting ? 'Đang lưu...' : 'Lưu thay đổi'}
                            </button>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    );
}
