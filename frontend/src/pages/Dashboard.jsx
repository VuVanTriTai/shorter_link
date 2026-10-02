import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import api from '../api/axios';

export default function Dashboard() {
    const [links, setLinks] = useState([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [lastUpdated, setLastUpdated] = useState(null);
    const [error, setError] = useState('');
    const [deletingId, setDeletingId] = useState(null);
    const [copiedId, setCopiedId] = useState(null);
    const [qrModal, setQrModal] = useState(null); // { shortCode, shortUrl, dataUri }
    const [qrLoading, setQrLoading] = useState(false);
    const navigate = useNavigate();
    const username = localStorage.getItem('username') || 'Người dùng';

    const fetchLinks = async (isInitial = false) => {
        if (isInitial) setLoading(true);
        else setRefreshing(true);

        try {
            const res = await api.get('/api/links');
            const data = Array.isArray(res.data) ? res.data : [];
            const nowStr = new Date().toLocaleTimeString('vi-VN');
            setLinks(data);
            setLastUpdated(nowStr);
            setError('');
            console.log(`[Dashboard] OK lúc ${nowStr} — ${data.length} link`, data.map(l => `${l.shortCode}: ${l.clickCount} clicks`));
        } catch (err) {
            console.error('[Dashboard] fetchLinks error:', err?.response?.status, err?.message);
            const data = err.response?.data;
            const status = err.response?.status;
            const message = typeof data === 'string' ? data : data?.message;
            if (status === 401 || status === 403) {
                setError('Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.');
            } else {
                setError(message || `Lỗi ${status ?? 'mạng'}: Không thể tải danh sách link.`);
            }
        } finally {
            // Luôn luôn reset trạng thái loading dù có lỗi hay không
            setLoading(false);
            setRefreshing(false);
        }
    };

    useEffect(() => {
        const token = localStorage.getItem('token');
        if (!token || token === 'undefined' || token === 'null') {
            navigate('/login');
            return;
        }
        fetchLinks(true);
    }, [navigate]);

    const handleLogout = async () => {
        try {
            await api.post('/api/auth/logout');
        } catch (ignored) {
            // ignore network error
        } finally {
            localStorage.removeItem('token');
            localStorage.removeItem('username');
            navigate('/login');
        }
    };

    const getShortUrl = (link) => {
        return link.fullShortUrl || link.fullShortCode || `http://localhost:8080/r/${link.shortCode}`;
    };

    const handleDelete = async (id) => {
        const confirmed = window.confirm('Bạn có chắc muốn xoá liên kết này?');
        if (!confirmed) return;

        setDeletingId(id);
        try {
            await api.delete(`/api/links/${id}`);
            setLinks((prev) => prev.filter((link) => link.id !== id));
        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            alert(message || 'Xoá link thất bại. Vui lòng thử lại.');
        } finally {
            setDeletingId(null);
        }
    };

    const handleCopy = async (link) => {
        const shortUrl = getShortUrl(link);
        try {
            await navigator.clipboard.writeText(shortUrl);
            setCopiedId(link.id);
            setTimeout(() => setCopiedId(null), 2000);
        } catch {
            alert('Không thể sao chép. Vui lòng copy thủ công.');
        }
    };

    const handleShowQr = async (link) => {
        setQrLoading(true);
        setQrModal({ shortCode: link.shortCode, shortUrl: getShortUrl(link), dataUri: null });
        try {
            const res = await api.get(`/api/qr/${link.shortCode}/base64`);
            setQrModal(res.data);
        } catch (err) {
            alert('Không thể tạo QR code. Vui lòng thử lại.');
            setQrModal(null);
        } finally {
            setQrLoading(false);
        }
    };

    const handleDownloadQr = () => {
        if (!qrModal?.dataUri) return;
        const a = document.createElement('a');
        a.href = qrModal.dataUri;
        a.download = `qr-${qrModal.shortCode}.png`;
        a.click();
    };

    // Backend trả về LocalDateTime dạng "2026-10-01T23:00:00" (KHÔNG có suffix Z/+07:00)
    // Nếu dùng new Date("2026-10-01T23:00:00") → browser hiểu là LOCAL time → đúng
    // Nếu dùng new Date("2026-10-01T16:00:00Z") → browser chuyển sang local +7h → sai
    const formatDate = (isoString) => {
        if (!isoString) return '—';
        // Đảm bảo chuỗi không có Z ở cuối để browser parse là local time
        const localStr = isoString.replace('Z', '');
        return new Date(localStr).toLocaleString('vi-VN', {
            day: '2-digit',
            month: '2-digit',
            year: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
        });
    };

    const isExpired = (expiresAt) => {
        if (!expiresAt) return false;
        const localStr = expiresAt.replace('Z', '');
        return new Date(localStr) < new Date();
    };

    return (
        <>
        <div className="min-h-screen bg-gray-50 py-10 px-4 sm:px-6 lg:px-8">
            <div className="max-w-5xl mx-auto">
                {/* Header thanh điều hướng người dùng */}
                <div className="flex items-center justify-between mb-8 pb-4 border-b border-gray-200">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-full bg-blue-600 text-white flex items-center justify-center font-bold text-lg shadow">
                            {username.charAt(0).toUpperCase()}
                        </div>
                        <div>
                            <span className="text-xs text-gray-500 font-medium">Tài khoản</span>
                            <h2 className="text-base font-semibold text-gray-800">{username}</h2>
                        </div>
                    </div>
                    <button
                        onClick={handleLogout}
                        className="px-3.5 py-1.5 text-sm font-medium text-red-600 bg-white border border-red-200 rounded-lg hover:bg-red-50 transition shadow-sm"
                    >
                        Đăng xuất
                    </button>
                </div>

                {/* Tiêu đề trang & Các nút điều khiển */}
                <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
                    <div>
                        <h1 className="text-2xl font-bold text-gray-900">Liên kết của tôi</h1>
                        <p className="text-sm text-gray-500 mt-0.5">
                            Quản lý, theo dõi thống kê và chỉnh sửa các link rút gọn của bạn
                        </p>
                    </div>

                    <div className="flex items-center gap-3 flex-wrap">
                        {/* Nút Làm mới thủ công */}
                        <div className="flex items-center bg-white border border-gray-200 rounded-lg p-1 shadow-sm">
                            <button
                                onClick={() => fetchLinks(false)}
                                disabled={refreshing}
                                className={`px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-100 rounded flex items-center gap-1.5 transition ${refreshing ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
                                title="Làm mới danh sách liên kết"
                            >
                                <span className={`inline-block ${refreshing ? 'animate-spin' : ''}`}>🔄</span>
                                {refreshing ? 'Đang cập nhật...' : 'Làm mới'}
                            </button>
                            {lastUpdated && (
                                <span className="text-[11px] text-gray-400 pl-2.5 pr-2 border-l border-gray-200 hidden sm:inline" title="Thời gian lấy dữ liệu gần nhất">
                                    Cập nhật lúc: {lastUpdated}
                                </span>
                            )}
                        </div>

                        <Link
                            to="/links/new"
                            className="inline-flex items-center px-4 py-2 border border-transparent rounded-lg shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 transition"
                        >
                            + Tạo liên kết mới
                        </Link>
                    </div>
                </div>

                {error && (
                    <div role="alert" className="mb-6 bg-red-50 border-l-4 border-red-500 p-4 text-red-700 text-sm rounded shadow-sm">
                        {error}
                    </div>
                )}

                {loading ? (
                    <div className="text-center py-20 bg-white rounded-xl shadow-sm border border-gray-100">
                        <div className="inline-block animate-spin text-2xl mb-3">⏳</div>
                        <div className="text-gray-500 font-medium">Đang tải danh sách liên kết...</div>
                    </div>
                ) : links.length === 0 ? (
                    <div className="text-center py-16 bg-white rounded-xl shadow-sm border border-gray-100 p-8">
                        <div className="text-4xl mb-3">🔗</div>
                        <h3 className="text-base font-semibold text-gray-800">Bạn chưa có liên kết nào</h3>
                        <p className="text-sm text-gray-500 mt-1 max-w-sm mx-auto">
                            Bắt đầu tạo liên kết rút gọn đầu tiên của bạn để chia sẻ và theo dõi lượt truy cập.
                        </p>
                        <Link
                            to="/links/new"
                            className="mt-4 inline-flex items-center px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition shadow"
                        >
                            + Tạo liên kết ngay
                        </Link>
                    </div>
                ) : (
                    <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
                        <ul className="divide-y divide-gray-100">
                            {links.map((link) => {
                                const shortUrl = getShortUrl(link);
                                const expired = isExpired(link.expiresAt);
                                return (
                                    <li key={link.id} className="p-5 sm:p-6 hover:bg-gray-50/70 transition">
                                        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
                                            {/* Link info */}
                                            <div className="min-w-0 flex-1">
                                                <div className="flex items-center gap-2 flex-wrap">
                                                    <a
                                                        href={shortUrl}
                                                        target="_blank"
                                                        rel="noopener noreferrer"
                                                        className="text-blue-600 font-semibold hover:underline break-all text-base"
                                                    >
                                                        {shortUrl}
                                                    </a>

                                                    {expired && (
                                                        <span className="px-2 py-0.5 text-xs font-semibold bg-red-100 text-red-700 rounded-md">
                                                            Đã hết hạn
                                                        </span>
                                                    )}
                                                </div>

                                                <p className="text-sm text-gray-500 truncate mt-1.5" title={link.originalUrl}>
                                                    <span className="text-gray-400">Đích:</span> {link.originalUrl}
                                                </p>

                                                <div className="flex flex-wrap items-center gap-x-5 gap-y-1.5 mt-2.5 text-xs text-gray-500 font-medium">
                                                    <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-blue-50 text-blue-700 font-semibold border border-blue-100">
                                                        👆 {link.clickCount ?? 0} lượt nhấp
                                                    </span>
                                                    <span>📅 Tạo: {formatDate(link.createdAt)}</span>
                                                    {link.expiresAt && (
                                                        <span className={expired ? 'text-red-500 font-semibold' : ''}>
                                                            ⏳ Hết hạn: {formatDate(link.expiresAt)}
                                                        </span>
                                                    )}
                                                </div>
                                            </div>

                                            {/* Action Buttons */}
                                            <div className="flex items-center gap-2 shrink-0 flex-wrap sm:flex-nowrap pt-2 lg:pt-0 border-t lg:border-t-0 border-gray-100">
                                                <Link
                                                    to={`/links/${link.shortCode}/stats`}
                                                    className="px-3 py-1.5 text-xs sm:text-sm font-medium text-indigo-700 bg-indigo-50 border border-indigo-100 rounded-lg hover:bg-indigo-100 transition flex items-center gap-1"
                                                >
                                                    📊 Thống kê
                                                </Link>
                                                <button
                                                    onClick={() => handleCopy(link)}
                                                    className="px-3 py-1.5 text-xs sm:text-sm font-medium text-gray-700 bg-gray-100 border border-gray-200 rounded-lg hover:bg-gray-200 transition flex items-center gap-1"
                                                >
                                                    {copiedId === link.id ? '✓ Đã sao chép' : '📋 Sao chép'}
                                                </button>
                                                <Link
                                                    to={`/links/${link.shortCode}/edit`}
                                                    className="px-3 py-1.5 text-xs sm:text-sm font-medium text-blue-700 bg-blue-50 border border-blue-100 rounded-lg hover:bg-blue-100 transition flex items-center gap-1"
                                                >
                                                    ✏️ Chỉnh sửa
                                                </Link>
                                                <button
                                                    onClick={() => handleShowQr(link)}
                                                    className="px-3 py-1.5 text-xs sm:text-sm font-medium text-purple-700 bg-purple-50 border border-purple-100 rounded-lg hover:bg-purple-100 transition flex items-center gap-1"
                                                >
                                                    📱 QR Code
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(link.id)}
                                                    disabled={deletingId === link.id}
                                                    className="px-3 py-1.5 text-xs sm:text-sm font-medium text-red-700 bg-red-50 border border-red-100 rounded-lg hover:bg-red-100 disabled:opacity-50 transition flex items-center gap-1"
                                                >
                                                    {deletingId === link.id ? 'Đang xoá...' : '🗑️ Xoá'}
                                                </button>
                                            </div>
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    </div>
                )}
            </div>
        </div>

        {/* QR Code Modal */}
        {qrModal && (
            <div
                className="fixed inset-0 z-50 flex items-center justify-center p-4"
                style={{ backgroundColor: 'rgba(0,0,0,0.55)', backdropFilter: 'blur(4px)' }}
                onClick={(e) => { if (e.target === e.currentTarget) setQrModal(null); }}
            >
                <div className="bg-white rounded-2xl shadow-2xl w-full max-w-sm overflow-hidden">
                    {/* Modal header */}
                    <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
                        <div>
                            <h3 className="text-base font-bold text-gray-900">📱 QR Code</h3>
                            <p className="text-xs text-gray-500 mt-0.5 truncate max-w-[220px]">{qrModal.shortUrl}</p>
                        </div>
                        <button
                            onClick={() => setQrModal(null)}
                            className="text-gray-400 hover:text-gray-600 text-xl leading-none w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 transition"
                        >
                            ✕
                        </button>
                    </div>

                    {/* QR image area */}
                    <div className="flex flex-col items-center py-8 px-6 gap-4">
                        {qrLoading || !qrModal.dataUri ? (
                            <div className="w-52 h-52 flex items-center justify-center">
                                <div className="text-3xl animate-spin">⏳</div>
                            </div>
                        ) : (
                            <>
                                <img
                                    src={qrModal.dataUri}
                                    alt={`QR Code cho ${qrModal.shortCode}`}
                                    className="w-52 h-52 rounded-xl border-4 border-gray-100 shadow-inner"
                                />
                                <p className="text-xs text-gray-400 text-center">
                                    Quét mã để truy cập liên kết rút gọn
                                </p>
                            </>
                        )}
                    </div>

                    {/* Modal footer */}
                    <div className="flex gap-2 px-5 pb-5">
                        <button
                            onClick={handleDownloadQr}
                            disabled={!qrModal.dataUri || qrLoading}
                            className="flex-1 py-2.5 text-sm font-semibold text-white bg-blue-600 rounded-xl hover:bg-blue-700 disabled:opacity-50 transition shadow"
                        >
                            ⬇️ Tải về PNG
                        </button>
                        <button
                            onClick={() => setQrModal(null)}
                            className="flex-1 py-2.5 text-sm font-medium text-gray-700 bg-gray-100 rounded-xl hover:bg-gray-200 transition"
                        >
                            Đóng
                        </button>
                    </div>
                </div>
            </div>
        )}
        </>
    );
}