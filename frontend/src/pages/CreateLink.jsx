import { useState, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../api/axios';

export default function CreateLink() {
    const [originalUrl, setOriginalUrl] = useState('');
    const [customCode, setCustomCode] = useState('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [createdLink, setCreatedLink] = useState(null);
    const [copied, setCopied] = useState(false);
    const navigate = useNavigate();

    useEffect(() => {
        const token = localStorage.getItem('token');
        if (!token || token === 'undefined' || token === 'null') {
            navigate('/login');
        }
    }, [navigate]);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');

        let url = originalUrl.trim();
        if (!url) {
            setError('Vui lòng nhập đường link gốc cần rút gọn.');
            return;
        }

        // Tự động thêm https:// nếu người dùng quên nhập protocol
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
        };

        setLoading(true);

        try {
            const res = await api.post('/api/links', payload);
            setCreatedLink(res.data);
            setOriginalUrl('');
            setCustomCode('');
        } catch (err) {
            const data = err.response?.data;
            const status = err.response?.status;
            const message = typeof data === 'string' ? data : data?.message;
            if (status === 401 || status === 403) {
                setError(`Lỗi xác thực (${status}): Token không hợp lệ hoặc đã hết hạn. Hãy thử bấm Đăng xuất rồi đăng nhập lại.`);
            } else {
                setError(message || 'Tạo link thất bại. Vui lòng kiểm tra lại!');
            }
        } finally {
            setLoading(false);
        }
    };

    const getShortUrl = (link) => {
        if (!link) return '';
        return `${window.location.origin}/r/${link.shortCode}`;
    };

    const handleCopy = async () => {
        if (!createdLink) return;
        const shortUrl = getShortUrl(createdLink);
        try {
            await navigator.clipboard.writeText(shortUrl);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        } catch {
            alert('Không thể sao chép tự động. Vui lòng copy thủ công.');
        }
    };

    return (
        <div className="min-h-screen bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
            <div className="max-w-2xl mx-auto">
                {/* Thanh điều hướng quay lại */}
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
                        <h1 className="text-2xl font-bold text-gray-900">Tạo Link Rút Gọn Mới</h1>
                        <p className="mt-1 text-sm text-gray-500">
                            Dán đường link dài của bạn vào đây để tạo link ngắn gọn, dễ chia sẻ.
                        </p>
                    </div>

                    {error && (
                        <div
                            role="alert"
                            className="mb-6 bg-red-50 border-l-4 border-red-400 p-4 text-red-700 text-sm rounded-r"
                        >
                            {error}
                        </div>
                    )}

                    {/* Hiển thị kết quả thành công nếu vừa tạo xong */}
                    {createdLink && (
                        <div className="mb-8 p-5 bg-green-50 border border-green-200 rounded-lg">
                            <div className="flex items-center justify-between mb-3">
                                <span className="text-sm font-semibold text-green-800 flex items-center gap-1.5">
                                    <svg className="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7" />
                                    </svg>
                                    Tạo link rút gọn thành công!
                                </span>
                                <span className="text-xs text-gray-500">Mã: {createdLink.shortCode}</span>
                            </div>

                            <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 mt-2">
                                <input
                                    type="text"
                                    readOnly
                                    value={getShortUrl(createdLink)}
                                    className="flex-1 px-3 py-2 bg-white border border-green-300 rounded-md text-blue-600 font-medium text-sm focus:outline-none"
                                />
                                <button
                                    onClick={handleCopy}
                                    className="px-4 py-2 text-sm font-medium text-white bg-green-600 rounded-md hover:bg-green-700 transition"
                                >
                                    {copied ? '✓ Đã copy!' : 'Sao chép'}
                                </button>
                            </div>

                            <p className="mt-3 text-xs text-gray-600 truncate" title={createdLink.originalUrl}>
                                <span className="font-medium">URL gốc:</span> {createdLink.originalUrl}
                            </p>

                            <div className="mt-4 pt-3 border-t border-green-200 flex items-center justify-between">
                                <button
                                    onClick={() => setCreatedLink(null)}
                                    className="text-xs font-medium text-green-700 hover:text-green-800"
                                >
                                    + Tạo thêm link khác
                                </button>
                                <Link
                                    to="/dashboard"
                                    className="text-xs font-medium text-blue-600 hover:underline"
                                >
                                    Xem tất cả link của bạn →
                                </Link>
                            </div>
                        </div>
                    )}

                    <form onSubmit={handleSubmit} className="space-y-6" noValidate>
                        {/* URL gốc */}
                        <div>
                            <label htmlFor="originalUrl" className="block text-sm font-medium text-gray-700 mb-1">
                                Đường dẫn gốc (Long URL) <span className="text-red-500">*</span>
                            </label>
                            <input
                                id="originalUrl"
                                type="url"
                                required
                                placeholder="https://example.com/duong-dan-rat-dai-can-rut-gon"
                                value={originalUrl}
                                onChange={(e) => {
                                    setOriginalUrl(e.target.value);
                                    if (error) setError('');
                                }}
                                className="block w-full px-3 py-2.5 border border-gray-300 rounded-lg shadow-sm placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 text-sm"
                            />
                            <p className="mt-1 text-xs text-gray-500">
                                Nhập đường link đầy đủ mà bạn muốn người dùng truy cập tới.
                            </p>
                        </div>

                        {/* Custom Code */}
                        <div>
                            <label htmlFor="customCode" className="block text-sm font-medium text-gray-700 mb-1">
                                Mã rút gọn tùy chỉnh (Custom Alias) <span className="text-xs text-gray-400">(Tùy chọn)</span>
                            </label>
                            <div className="flex rounded-lg shadow-sm">
                                <span className="inline-flex items-center px-3 rounded-l-lg border border-r-0 border-gray-300 bg-gray-50 text-gray-500 text-sm">
                                    {import.meta.env.VITE_API_URL || 'http://localhost:8080'}/r/
                                </span>
                                <input
                                    id="customCode"
                                    type="text"
                                    placeholder="ma-tuy-chinh"
                                    value={customCode}
                                    onChange={(e) => {
                                        setCustomCode(e.target.value);
                                        if (error) setError('');
                                    }}
                                    className="flex-1 min-w-0 block w-full px-3 py-2.5 rounded-none rounded-r-lg border border-gray-300 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 text-sm"
                                />
                            </div>
                            <p className="mt-1 text-xs text-gray-500">
                                Nếu để trống, hệ thống sẽ tự sinh ngẫu nhiên mã gồm 6 ký tự.
                            </p>
                        </div>

                        {/* Nút hành động */}
                        <div className="flex items-center justify-end gap-3 pt-4 border-t border-gray-100">
                            <Link
                                to="/dashboard"
                                className="px-4 py-2 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 transition"
                            >
                                Hủy
                            </Link>
                            <button
                                type="submit"
                                disabled={loading}
                                className="px-5 py-2 border border-transparent rounded-lg shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition"
                            >
                                {loading ? 'Đang tạo...' : 'Tạo Link'}
                            </button>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    );
}
