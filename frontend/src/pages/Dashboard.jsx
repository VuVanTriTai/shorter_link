import { useEffect, useState, useRef, useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import api from '../api/axios';
import ChangePasswordModal from '../components/ChangePasswordModal';

const ITEMS_PER_PAGE = 5;

export default function Dashboard() {
    const [links, setLinks] = useState([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [lastUpdated, setLastUpdated] = useState(null);
    const [error, setError] = useState('');
    const [deletingId, setDeletingId] = useState(null);
    const [togglingId, setTogglingId] = useState(null);
    const [copiedId, setCopiedId] = useState(null);
    const [qrModal, setQrModal] = useState(null); // { shortCode, shortUrl, dataUri }
    const [qrLoading, setQrLoading] = useState(false);
    const [settingsOpen, setSettingsOpen] = useState(false);
    const [changePasswordOpen, setChangePasswordOpen] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');
    const [currentPage, setCurrentPage] = useState(1);
    const settingsRef = useRef(null);
    // Ref guard: ngăn 2 fetchLinks chạy đồng thời (race condition)
    const isFetchingRef = useRef(false);
    const navigate = useNavigate();
    const username = localStorage.getItem('username') || 'Người dùng';

    // Đóng dropdown Cài đặt khi click bên ngoài
    useEffect(() => {
        const handleClickOutside = (e) => {
            if (settingsRef.current && !settingsRef.current.contains(e.target)) {
                setSettingsOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    const fetchLinks = async (isInitial = false) => {
        // BUG FIX #2: Dùng ref để chặn concurrent calls — ref update đồng bộ,
        // không phụ thuộc vào chu kỳ render của React state
        if (isFetchingRef.current) return;
        isFetchingRef.current = true;

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
            isFetchingRef.current = false; // Mở khoá để lần sau có thể refresh
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

    // === Filtered & paginated links ===
    const filteredLinks = useMemo(() => {
        if (!searchQuery.trim()) return links;
        const q = searchQuery.toLowerCase();
        return links.filter(
            (link) =>
                link.shortCode?.toLowerCase().includes(q) ||
                link.originalUrl?.toLowerCase().includes(q) ||
                link.fullShortCode?.toLowerCase().includes(q)
        );
    }, [links, searchQuery]);

    const totalPages = Math.max(1, Math.ceil(filteredLinks.length / ITEMS_PER_PAGE));

    // Reset to page 1 when search changes
    useEffect(() => {
        setCurrentPage(1);
    }, [searchQuery]);

    // Clamp currentPage if filteredLinks shrinks (e.g. after deletion)
    useEffect(() => {
        if (currentPage > totalPages) {
            setCurrentPage(totalPages);
        }
    }, [totalPages, currentPage]);

    const paginatedLinks = useMemo(() => {
        const start = (currentPage - 1) * ITEMS_PER_PAGE;
        return filteredLinks.slice(start, start + ITEMS_PER_PAGE);
    }, [filteredLinks, currentPage]);

    // Calculate stats
    const totalClicks = useMemo(() => links.reduce((sum, l) => sum + (l.clickCount ?? 0), 0), [links]);
    const activeCount = useMemo(() => links.filter((l) => l.active !== false).length, [links]);

    const handleLogout = async () => {
        try {
            await api.post('/api/auth/logout');
        } catch (ignored) {
            // ignore network error
        } finally {
            localStorage.removeItem('token');
            localStorage.removeItem('username');
            localStorage.removeItem('role');
            navigate('/login');
        }
    };

    const getShortUrl = (link) => {
        if (!link) return '';
        return `${window.location.origin}/r/${link.shortCode}`;
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

    const handleToggleActive = async (link) => {
        const nextActive = link.active === false; // nếu false -> true, nếu true/undefined -> false
        setTogglingId(link.id);

        // Cập nhật giao diện ngay lập tức (optimistic)
        setLinks((prev) =>
            prev.map((l) => (l.id === link.id ? { ...l, active: nextActive } : l))
        );

        try {
            const res = await api.patch(`/api/links/${link.id}/active`, { active: nextActive });
            if (res.data && typeof res.data.active === 'boolean') {
                // BUG FIX #3: Spread toàn bộ res.data (không chỉ active) để clickCount
                // cũng được đồng bộ từ DB — backend mapToResponse() luôn query click_analytics
                setLinks((prev) =>
                    prev.map((l) => (l.id === link.id ? { ...l, ...res.data } : l))
                );
            }
        } catch (err) {
            // Rollback lại nếu gọi API thất bại
            setLinks((prev) =>
                prev.map((l) => (l.id === link.id ? { ...l, active: link.active !== false } : l))
            );
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            alert(message || 'Không thể đổi trạng thái liên kết. Vui lòng thử lại!');
        } finally {
            setTogglingId(null);
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

    // === Pagination helpers ===
    const getPageNumbers = () => {
        const pages = [];
        const maxVisible = 5;

        if (totalPages <= maxVisible + 2) {
            for (let i = 1; i <= totalPages; i++) pages.push(i);
        } else {
            pages.push(1);
            let start = Math.max(2, currentPage - 1);
            let end = Math.min(totalPages - 1, currentPage + 1);

            if (currentPage <= 3) {
                start = 2;
                end = Math.min(maxVisible, totalPages - 1);
            } else if (currentPage >= totalPages - 2) {
                start = Math.max(2, totalPages - maxVisible + 1);
                end = totalPages - 1;
            }

            if (start > 2) pages.push('...');
            for (let i = start; i <= end; i++) pages.push(i);
            if (end < totalPages - 1) pages.push('...');
            pages.push(totalPages);
        }
        return pages;
    };

    return (
        <>
        <div className="dashboard-page">
            {/* ===== GRADIENT HERO HEADER ===== */}
            <div className="dashboard-header">
                <div className="dashboard-header-inner">
                    <div className="header-top">
                        <div className="user-info">
                            <div className="user-avatar">
                                {username.charAt(0).toUpperCase()}
                            </div>
                            <div>
                                <span className="user-label">Xin chào 👋</span>
                                <h2 className="user-name">{username}</h2>
                            </div>
                        </div>

                        {/* Settings Dropdown */}
                        <div className="settings-wrapper" ref={settingsRef}>
                            <button
                                onClick={() => setSettingsOpen((prev) => !prev)}
                                className={`settings-btn ${settingsOpen ? 'active' : ''}`}
                                aria-expanded={settingsOpen}
                                aria-haspopup="true"
                            >
                                <svg width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                                </svg>
                                <span>Cài đặt</span>
                                <svg
                                    className={`chevron ${settingsOpen ? 'rotated' : ''}`}
                                    width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5"
                                >
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                                </svg>
                            </button>

                            {/* Dropdown Menu */}
                            {settingsOpen && (
                                <div className="settings-dropdown">
                                    <div className="dropdown-header">
                                        <p className="dropdown-label">Tài khoản</p>
                                        <p className="dropdown-username">{username}</p>
                                    </div>
                                    <div className="dropdown-body">
                                        {localStorage.getItem('role') === 'ROLE_ADMIN' && (
                                            <button
                                                onClick={() => {
                                                    setSettingsOpen(false);
                                                    navigate('/admin');
                                                }}
                                                className="dropdown-item"
                                                style={{ color: '#f59e0b' }}
                                            >
                                                <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
                                                </svg>
                                                <span>🛡️ Admin Panel</span>
                                            </button>
                                        )}
                                        <button
                                            onClick={() => {
                                                setSettingsOpen(false);
                                                setChangePasswordOpen(true);
                                            }}
                                            className="dropdown-item"
                                        >
                                            <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" />
                                            </svg>
                                            <span>Đổi mật khẩu</span>
                                        </button>
                                    </div>
                                    <div className="dropdown-divider"></div>
                                    <div className="dropdown-body">
                                        <button
                                            onClick={() => {
                                                setSettingsOpen(false);
                                                handleLogout();
                                            }}
                                            className="dropdown-item danger"
                                        >
                                            <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                                            </svg>
                                            <span>Đăng xuất</span>
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Stats Cards */}
                    <div className="stats-grid">
                        <div className="stat-card">
                            <div className="stat-icon blue">
                                <svg width="22" height="22" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
                                </svg>
                            </div>
                            <div>
                                <p className="stat-value">{links.length}</p>
                                <p className="stat-label">Tổng liên kết</p>
                            </div>
                        </div>
                        <div className="stat-card">
                            <div className="stat-icon green">
                                <svg width="22" height="22" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                                </svg>
                            </div>
                            <div>
                                <p className="stat-value">{activeCount}</p>
                                <p className="stat-label">Đang hoạt động</p>
                            </div>
                        </div>
                        <div className="stat-card">
                            <div className="stat-icon purple">
                                <svg width="22" height="22" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5M7.188 2.239l.777 2.897M5.136 7.965l-2.898-.777M13.95 4.05l-2.122 2.122m-5.657 5.656l-2.12 2.122" />
                                </svg>
                            </div>
                            <div>
                                <p className="stat-value">{totalClicks.toLocaleString('vi-VN')}</p>
                                <p className="stat-label">Tổng lượt nhấp</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* ===== MAIN CONTENT ===== */}
            <div className="dashboard-content">
                {/* Toolbar */}
                <div className="toolbar">
                    <div className="toolbar-left">
                        <h1 className="page-title">
                            <svg width="24" height="24" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
                            </svg>
                            Liên kết của tôi
                        </h1>
                        <p className="page-subtitle">
                            Quản lý, theo dõi thống kê và chỉnh sửa các link rút gọn
                        </p>
                    </div>
                    <div className="toolbar-right">
                        <button
                            onClick={() => fetchLinks(false)}
                            disabled={refreshing || loading}
                            className={`btn-refresh ${refreshing ? 'spinning' : ''}`}
                            title="Làm mới danh sách liên kết"
                        >
                            <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                            </svg>
                            {refreshing ? 'Đang cập nhật...' : 'Làm mới'}
                        </button>
                        {lastUpdated && (
                            <span className="last-updated">
                                <svg width="12" height="12" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                                </svg>
                                {lastUpdated}
                            </span>
                        )}
                        <Link to="/links/new" className="btn-create">
                            <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                            </svg>
                            Tạo liên kết mới
                        </Link>
                    </div>
                </div>

                {/* Search Bar */}
                {links.length > 0 && (
                    <div className="search-bar-wrapper">
                        <div className="search-bar">
                            <svg className="search-icon" width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                            </svg>
                            <input
                                type="text"
                                placeholder="Tìm kiếm theo URL gốc hoặc mã rút gọn..."
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                                className="search-input"
                            />
                            {searchQuery && (
                                <button
                                    onClick={() => setSearchQuery('')}
                                    className="search-clear"
                                    title="Xoá tìm kiếm"
                                >
                                    <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                                    </svg>
                                </button>
                            )}
                        </div>
                        <span className="search-result-count">
                            {filteredLinks.length === links.length
                                ? `${links.length} liên kết`
                                : `${filteredLinks.length} / ${links.length} kết quả`}
                        </span>
                    </div>
                )}

                {/* Error */}
                {error && (
                    <div role="alert" className="error-banner">
                        <svg width="20" height="20" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4.5c-.77-.833-2.694-.833-3.464 0L3.34 16.5c-.77.833.192 2.5 1.732 2.5z" />
                        </svg>
                        <span>{error}</span>
                    </div>
                )}

                {/* Main link list */}
                {loading ? (
                    <div className="empty-state">
                        <div className="loading-spinner"></div>
                        <h3>Đang tải danh sách liên kết...</h3>
                        <p>Vui lòng chờ trong giây lát</p>
                    </div>
                ) : links.length === 0 ? (
                    <div className="empty-state">
                        <div className="empty-icon">
                            <svg width="48" height="48" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
                            </svg>
                        </div>
                        <h3>Bạn chưa có liên kết nào</h3>
                        <p>Bắt đầu tạo liên kết rút gọn đầu tiên để chia sẻ và theo dõi lượt truy cập.</p>
                        <Link to="/links/new" className="btn-create" style={{ marginTop: '8px' }}>
                            <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
                            </svg>
                            Tạo liên kết ngay
                        </Link>
                    </div>
                ) : filteredLinks.length === 0 ? (
                    <div className="empty-state">
                        <div className="empty-icon">
                            <svg width="48" height="48" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                            </svg>
                        </div>
                        <h3>Không tìm thấy kết quả</h3>
                        <p>Không có liên kết nào khớp với từ khoá "{searchQuery}"</p>
                        <button onClick={() => setSearchQuery('')} className="btn-refresh" style={{ marginTop: '8px' }}>
                            Xoá bộ lọc
                        </button>
                    </div>
                ) : (
                    <>
                        <div className="link-list">
                            {paginatedLinks.map((link, index) => {
                                const shortUrl = getShortUrl(link);
                                const expired = isExpired(link.expiresAt);
                                const isActive = link.active !== false;
                                return (
                                    <div
                                        key={link.id}
                                        className={`link-card ${!isActive ? 'inactive' : ''}`}
                                        style={{ animationDelay: `${index * 60}ms` }}
                                    >
                                        {/* Card top strip */}
                                        <div className={`card-strip ${link.banned ? 'banned' : (isActive ? (expired ? 'expired' : 'active') : 'paused')}`}></div>

                                        <div className="card-body">
                                            {/* Link Info */}
                                            <div className="link-info">
                                                <div className="link-url-row">
                                                    <a
                                                        href={shortUrl}
                                                        target="_blank"
                                                        rel="noopener noreferrer"
                                                        className={`link-short-url ${(!isActive || link.banned) ? 'disabled' : ''}`}
                                                        title={link.banned ? 'Liên kết đã bị Quản trị viên khoá do vi phạm' : (isActive ? 'Mở liên kết rút gọn' : 'Liên kết đang tắt')}
                                                    >
                                                        {shortUrl}
                                                    </a>
                                                    {/* Status badge */}
                                                    {link.banned ? (
                                                        <span className="badge badge-banned" style={{ background: '#fee2e2', color: '#991b1b', borderColor: '#fca5a5' }} title="Liên kết đã bị Quản trị viên khoá do vi phạm chính sách">
                                                            <span className="badge-dot" style={{ background: '#dc2626' }}></span>
                                                            🚫 Bị Admin khoá
                                                        </span>
                                                    ) : (
                                                        <>
                                                            {link.hasPassword && (
                                                                <span className="badge badge-password" title="Liên kết có mật khẩu bảo vệ" style={{ background: '#fef3c7', color: '#92400e', borderColor: '#fde68a' }}>
                                                                    <svg width="11" height="11" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5" style={{ display: 'inline', marginRight: '3px', verticalAlign: '-1px' }}>
                                                                        <path strokeLinecap="round" strokeLinejoin="round" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
                                                                    </svg>
                                                                    Mật khẩu
                                                                </span>
                                                            )}
                                                            {isActive ? (
                                                                expired ? (
                                                                    <span className="badge badge-expired">
                                                                        <span className="badge-dot"></span>
                                                                        Hết hạn
                                                                    </span>
                                                                ) : (
                                                                    <span className="badge badge-active">
                                                                        <span className="badge-dot"></span>
                                                                        Hoạt động
                                                                    </span>
                                                                )
                                                            ) : (
                                                                <span className="badge badge-paused">
                                                                    <span className="badge-dot"></span>
                                                                    Tạm dừng
                                                                </span>
                                                            )}
                                                        </>
                                                    )}
                                                </div>

                                                <p className="link-original" title={link.originalUrl}>
                                                    <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                        <path strokeLinecap="round" strokeLinejoin="round" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                                                    </svg>
                                                    {link.originalUrl}
                                                </p>

                                                <div className="link-meta">
                                                    <span className="meta-clicks">
                                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                            <path strokeLinecap="round" strokeLinejoin="round" d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5" />
                                                        </svg>
                                                        {(link.clickCount ?? 0).toLocaleString('vi-VN')} lượt nhấp
                                                    </span>
                                                    <span className="meta-date">
                                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                            <path strokeLinecap="round" strokeLinejoin="round" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
                                                        </svg>
                                                        {formatDate(link.createdAt)}
                                                    </span>
                                                    {link.expiresAt && (
                                                        <span className={`meta-date ${expired ? 'expired-text' : ''}`}>
                                                            <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                                <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                                                            </svg>
                                                            Hết hạn: {formatDate(link.expiresAt)}
                                                        </span>
                                                    )}
                                                </div>
                                            </div>

                                            {/* Action Buttons */}
                                            <div className="link-actions">
                                                <button
                                                    onClick={() => {
                                                        if (link.banned) {
                                                            alert('Link này đã bị Quản trị viên khoá do vi phạm quy định, bạn không thể tự bật lại!');
                                                            return;
                                                        }
                                                        handleToggleActive(link);
                                                    }}
                                                    disabled={togglingId === link.id || link.banned}
                                                    title={link.banned ? 'Liên kết đã bị Quản trị viên khoá' : (isActive ? 'Tạm ngưng liên kết' : 'Kích hoạt lại')}
                                                    className={`action-btn ${link.banned ? 'toggle-banned' : (isActive ? 'toggle-on' : 'toggle-off')}`}
                                                    style={link.banned ? { opacity: 0.6, cursor: 'not-allowed', background: '#fee2e2', color: '#991b1b', borderColor: '#fca5a5' } : {}}
                                                >
                                                    {togglingId === link.id ? (
                                                        <div className="btn-spinner"></div>
                                                    ) : (
                                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                            {link.banned ? (
                                                                <path strokeLinecap="round" strokeLinejoin="round" d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" />
                                                            ) : isActive ? (
                                                                <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                                                            ) : (
                                                                <path strokeLinecap="round" strokeLinejoin="round" d="M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                                                            )}
                                                        </svg>
                                                    )}
                                                    <span>{link.banned ? 'Bị khoá' : (isActive ? 'Bật' : 'Tắt')}</span>
                                                </button>

                                                <Link
                                                    to={`/links/${link.shortCode}/stats`}
                                                    className="action-btn stats"
                                                >
                                                    <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                        <path strokeLinecap="round" strokeLinejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                                                    </svg>
                                                    <span>Thống kê</span>
                                                </Link>

                                                <button
                                                    onClick={() => handleCopy(link)}
                                                    className={`action-btn copy ${copiedId === link.id ? 'copied' : ''}`}
                                                >
                                                    {copiedId === link.id ? (
                                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                                            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                                                        </svg>
                                                    ) : (
                                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                            <path strokeLinecap="round" strokeLinejoin="round" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                                                        </svg>
                                                    )}
                                                    <span>{copiedId === link.id ? 'Đã chép!' : 'Sao chép'}</span>
                                                </button>

                                                <Link
                                                    to={`/links/${link.shortCode}/edit`}
                                                    className="action-btn edit"
                                                >
                                                    <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                        <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                                                    </svg>
                                                    <span>Sửa</span>
                                                </Link>

                                                <button
                                                    onClick={() => handleShowQr(link)}
                                                    className="action-btn qr"
                                                >
                                                    <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                        <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm12 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z" />
                                                    </svg>
                                                    <span>QR</span>
                                                </button>

                                                <button
                                                    onClick={() => handleDelete(link.id)}
                                                    disabled={deletingId === link.id}
                                                    className="action-btn delete"
                                                >
                                                    {deletingId === link.id ? (
                                                        <div className="btn-spinner"></div>
                                                    ) : (
                                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                            <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                                                        </svg>
                                                    )}
                                                    <span>{deletingId === link.id ? '...' : 'Xoá'}</span>
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>

                        {/* ===== PAGINATION ===== */}
                        {totalPages > 1 && (
                            <div className="pagination-wrapper">
                                <div className="pagination-info">
                                    Hiển thị {(currentPage - 1) * ITEMS_PER_PAGE + 1}–{Math.min(currentPage * ITEMS_PER_PAGE, filteredLinks.length)} trong tổng số {filteredLinks.length} liên kết
                                </div>
                                <div className="pagination">
                                    <button
                                        onClick={() => setCurrentPage(1)}
                                        disabled={currentPage === 1}
                                        className="page-btn"
                                        title="Trang đầu"
                                    >
                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
                                        </svg>
                                    </button>
                                    <button
                                        onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                                        disabled={currentPage === 1}
                                        className="page-btn"
                                        title="Trang trước"
                                    >
                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
                                        </svg>
                                    </button>

                                    {getPageNumbers().map((page, i) =>
                                        page === '...' ? (
                                            <span key={`dots-${i}`} className="page-dots">
                                                ···
                                            </span>
                                        ) : (
                                            <button
                                                key={page}
                                                onClick={() => setCurrentPage(page)}
                                                className={`page-btn ${currentPage === page ? 'active' : ''}`}
                                            >
                                                {page}
                                            </button>
                                        )
                                    )}

                                    <button
                                        onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                                        disabled={currentPage === totalPages}
                                        className="page-btn"
                                        title="Trang sau"
                                    >
                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                                        </svg>
                                    </button>
                                    <button
                                        onClick={() => setCurrentPage(totalPages)}
                                        disabled={currentPage === totalPages}
                                        className="page-btn"
                                        title="Trang cuối"
                                    >
                                        <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                                        </svg>
                                    </button>
                                </div>
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>

        {/* QR Code Modal */}
        {qrModal && (
            <div className="modal-overlay" onClick={(e) => { if (e.target === e.currentTarget) setQrModal(null); }}>
                <div className="modal-content qr-modal">
                    {/* Modal header */}
                    <div className="modal-header">
                        <div>
                            <h3 className="modal-title">
                                <svg width="20" height="20" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm12 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z" />
                                </svg>
                                QR Code
                            </h3>
                            <p className="modal-subtitle">{qrModal.shortUrl}</p>
                        </div>
                        <button onClick={() => setQrModal(null)} className="modal-close">
                            <svg width="20" height="20" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                            </svg>
                        </button>
                    </div>

                    {/* QR image area */}
                    <div className="qr-body">
                        {qrLoading || !qrModal.dataUri ? (
                            <div className="qr-loading">
                                <div className="loading-spinner"></div>
                            </div>
                        ) : (
                            <>
                                <img
                                    src={qrModal.dataUri}
                                    alt={`QR Code cho ${qrModal.shortCode}`}
                                    className="qr-image"
                                />
                                <p className="qr-hint">Quét mã để truy cập liên kết rút gọn</p>
                            </>
                        )}
                    </div>

                    {/* Modal footer */}
                    <div className="modal-footer">
                        <button
                            onClick={handleDownloadQr}
                            disabled={!qrModal.dataUri || qrLoading}
                            className="btn-create"
                            style={{ flex: 1 }}
                        >
                            <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                            </svg>
                            Tải về PNG
                        </button>
                        <button
                            onClick={() => setQrModal(null)}
                            className="btn-refresh"
                            style={{ flex: 1 }}
                        >
                            Đóng
                        </button>
                    </div>
                </div>
            </div>
        )}

        {/* Modal Thay đổi mật khẩu */}
        <ChangePasswordModal
            isOpen={changePasswordOpen}
            onClose={() => setChangePasswordOpen(false)}
        />
        </>
    );
}