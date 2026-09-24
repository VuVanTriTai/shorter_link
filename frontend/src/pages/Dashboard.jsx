import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import api from '../api/axios';

export default function Dashboard() {
    const [links, setLinks] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [deletingId, setDeletingId] = useState(null);
    const [copiedId, setCopiedId] = useState(null);
    const navigate = useNavigate();
    const username = localStorage.getItem('username') || 'Người dùng';

    useEffect(() => {
        const token = localStorage.getItem('token');
        if (!token || token === 'undefined' || token === 'null') {
            navigate('/login');
            return;
        }
        fetchLinks();
    }, [navigate]);

    const fetchLinks = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.get('/api/links');
            setLinks(Array.isArray(res.data) ? res.data : []);
        } catch (err) {
            const data = err.response?.data;
            const status = err.response?.status;
            const message = typeof data === 'string' ? data : data?.message;
            if (status === 401 || status === 403) {
                setError(`Phiên đăng nhập không hợp lệ hoặc đã hết hạn (Mã lỗi: ${status}). Vui lòng bấm nút 'Đăng xuất' rồi đăng nhập lại.`);
            } else {
                setError(message || 'Không thể tải danh sách link. Vui lòng thử lại.');
            }
        } finally {
            setLoading(false);
        }
    };

    const handleLogout = () => {
        localStorage.removeItem('token');
        localStorage.removeItem('username');
        navigate('/login');
    };

    const getShortUrl = (link) => {
        return link.fullShortUrl || link.fullShortCode || `http://localhost:8080/r/${link.shortCode}`;
    };

    const handleDelete = async (id) => {
        const confirmed = window.confirm('Bạn có chắc muốn xoá link này?');
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

    const formatDate = (isoString) => {
        if (!isoString) return '—';
        return new Date(isoString).toLocaleString('vi-VN', {
            day: '2-digit',
            month: '2-digit',
            year: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
        });
    };

    const isExpired = (expiresAt) => {
        if (!expiresAt) return false;
        return new Date(expiresAt) < new Date();
    };

    return (
        <div className="min-h-screen bg-gray-100 py-10 px-4 sm:px-6 lg:px-8">
            <div className="max-w-4xl mx-auto">
                {/* Header thanh điều hướng người dùng */}
                <div className="flex items-center justify-between mb-8 pb-4 border-b border-gray-200">
                    <div>
                        <span className="text-sm text-gray-500">Xin chào,</span>
                        <h2 className="text-lg font-semibold text-gray-800">{username}</h2>
                    </div>
                    <button
                        onClick={handleLogout}
                        className="px-3 py-1.5 text-sm font-medium text-red-600 bg-white border border-red-200 rounded-md hover:bg-red-50"
                    >
                        Đăng xuất
                    </button>
                </div>

                <div className="flex items-center justify-between mb-6">
                    <div>
                        <h1 className="text-2xl font-bold text-gray-900">Link của tôi</h1>
                        <p className="text-sm text-gray-600 mt-1">
                            Quản lý các link rút gọn bạn đã tạo
                        </p>
                    </div>
                    <Link
                        to="/links/new"
                        className="inline-flex items-center px-4 py-2 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700"
                    >
                        + Tạo link mới
                    </Link>
                </div>

                {error && (
                    <div role="alert" className="mb-4 bg-red-50 border-l-4 border-red-400 p-4 text-red-700 text-sm">
                        {error}
                    </div>
                )}

                {loading ? (
                    <div className="text-center py-16 text-gray-500">Đang tải danh sách link...</div>
                ) : links.length === 0 ? (
                    <div className="text-center py-16 bg-white rounded-lg shadow">
                        <p className="text-gray-500">Bạn chưa có link nào.</p>
                        <Link
                            to="/links/new"
                            className="mt-3 inline-block text-sm font-medium text-blue-600 hover:text-blue-500"
                        >
                            Tạo link đầu tiên của bạn →
                        </Link>
                    </div>
                ) : (
                    <div className="bg-white rounded-lg shadow overflow-hidden">
                        <ul className="divide-y divide-gray-200">
                            {links.map((link) => {
                                const shortUrl = getShortUrl(link);
                                return (
                                    <li key={link.id} className="p-4 sm:p-6">
                                        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                                            <div className="min-w-0 flex-1">
                                                <div className="flex items-center gap-2 flex-wrap">
                                                    <a
                                                        href={shortUrl}
                                                        target="_blank"
                                                        rel="noopener noreferrer"
                                                        className="text-blue-600 font-medium hover:underline break-all"
                                                    >
                                                        {shortUrl}
                                                    </a>

                                                    {isExpired(link.expiresAt) && (
                                                        <span className="px-2 py-0.5 text-xs font-medium bg-red-100 text-red-700 rounded-full">
                                                            Đã hết hạn
                                                        </span>
                                                    )}
                                                </div>
                                                <p className="text-sm text-gray-500 truncate mt-1" title={link.originalUrl}>
                                                    {link.originalUrl}
                                                </p>
                                                <div className="flex flex-wrap gap-x-4 gap-y-1 mt-2 text-xs text-gray-400">
                                                    <span>👆 {link.clickCount ?? 0} lượt click</span>
                                                    <span>Tạo lúc: {formatDate(link.createdAt)}</span>
                                                    {link.expiresAt && (
                                                        <span>Hết hạn: {formatDate(link.expiresAt)}</span>
                                                    )}
                                                </div>
                                            </div>

                                            <div className="flex items-center gap-2 shrink-0 flex-wrap sm:flex-nowrap">
                                                <Link
                                                    to={`/links/${link.shortCode}/stats`}
                                                    className="px-3 py-1.5 text-sm font-medium text-indigo-700 bg-indigo-50 rounded-md hover:bg-indigo-100"
                                                >
                                                    📊 Thống kê
                                                </Link>
                                                <button
                                                    onClick={() => handleCopy(link)}
                                                    className="px-3 py-1.5 text-sm font-medium text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200"
                                                >
                                                    {copiedId === link.id ? 'Đã copy!' : 'Copy'}
                                                </button>
                                                <Link
                                                    to={`/links/${link.shortCode}/edit`}
                                                    className="px-3 py-1.5 text-sm font-medium text-blue-700 bg-blue-50 rounded-md hover:bg-blue-100"
                                                >
                                                    Sửa
                                                </Link>
                                                <button
                                                    onClick={() => handleDelete(link.id)}
                                                    disabled={deletingId === link.id}
                                                    className="px-3 py-1.5 text-sm font-medium text-red-700 bg-red-50 rounded-md hover:bg-red-100 disabled:opacity-50"
                                                >
                                                    {deletingId === link.id ? 'Đang xoá...' : 'Xoá'}
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
    );
}