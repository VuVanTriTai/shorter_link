import { useEffect, useState, useMemo, useRef } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import api from '../api/axios';

export default function LinkStats() {
    const { shortCode } = useParams();
    const navigate = useNavigate();

    const [stats, setStats] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [copied, setCopied] = useState(false);
    const [chartType, setChartType] = useState('bar'); // 'bar' | 'line'
    const [hoveredData, setHoveredData] = useState(null);

    // Quản lý phân trang & cuộn xem thêm cho danh sách click
    const [clicks, setClicks] = useState([]);
    const [clicksPage, setClicksPage] = useState(0);
    const [hasMoreClicks, setHasMoreClicks] = useState(false);
    const [loadingMoreClicks, setLoadingMoreClicks] = useState(false);
    const [totalClicksCount, setTotalClicksCount] = useState(0);
    const scrollContainerRef = useRef(null);
    const isFetchingRef = useRef(false);

    useEffect(() => {
        const token = localStorage.getItem('token');
        if (!token || token === 'undefined' || token === 'null') {
            navigate('/login');
            return;
        }
        fetchStats();
    }, [shortCode, navigate]);

    const fetchStats = async () => {
        if (isFetchingRef.current) return;
        isFetchingRef.current = true;
        setLoading(true);
        setError('');
        try {
            const [statsRes, clicksRes] = await Promise.all([
                api.get(`/api/links/${shortCode}/stats`),
                api.get(`/api/links/${shortCode}/clicks?page=0&size=20`),
            ]);
            setStats(statsRes.data);
            const content = clicksRes.data?.content || [];
            setClicks(content);
            setClicksPage(0);
            setHasMoreClicks(!clicksRes.data?.last && content.length > 0);
            setTotalClicksCount(clicksRes.data?.totalElements ?? statsRes.data.totalClicks);
        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            if (err.response?.status === 401 || err.response?.status === 403) {
                setError('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
            } else if (err.response?.status === 404) {
                setError('Không tìm thấy thông tin thống kê cho link này.');
            } else {
                setError(message || 'Không thể tải thống kê. Vui lòng thử lại sau.');
            }
        } finally {
            setLoading(false);
            isFetchingRef.current = false;
        }
    };

    const loadMoreClicks = async () => {
        if (loadingMoreClicks || !hasMoreClicks) return;
        setLoadingMoreClicks(true);
        const nextPage = clicksPage + 1;
        try {
            const res = await api.get(`/api/links/${shortCode}/clicks?page=${nextPage}&size=20`);
            const newContent = res.data?.content || [];
            setClicks((prev) => [...prev, ...newContent]);
            setClicksPage(nextPage);
            setHasMoreClicks(!res.data?.last && newContent.length > 0);
            if (res.data?.totalElements !== undefined) {
                setTotalClicksCount(res.data.totalElements);
            }
        } catch (err) {
            console.error('Lỗi khi tải thêm lượt click:', err);
        } finally {
            setLoadingMoreClicks(false);
        }
    };

    const handleScrollLoad = (e) => {
        const { scrollTop, scrollHeight, clientHeight } = e.currentTarget;
        if (scrollHeight - scrollTop - clientHeight < 60) {
            if (hasMoreClicks && !loadingMoreClicks) loadMoreClicks();
        }
    };

    const shortUrl = stats
        ? `${import.meta.env.VITE_API_URL || 'http://localhost:8080'}/r/${stats.shortCode}`
        : '';

    const handleCopy = async () => {
        if (!shortUrl) return;
        try {
            await navigator.clipboard.writeText(shortUrl);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        } catch {
            alert('Không thể sao chép. Hãy copy thủ công.');
        }
    };

    const formatDate = (isoString) => {
        if (!isoString) return '—';
        const localStr = isoString.replace ? isoString.replace('Z', '') : isoString;
        return new Date(localStr).toLocaleString('vi-VN', {
            day: '2-digit', month: '2-digit', year: 'numeric',
            hour: '2-digit', minute: '2-digit', second: '2-digit',
        });
    };

    const formatShortDate = (dateStr) => {
        if (!dateStr) return '';
        const parts = dateStr.split('-');
        return parts.length === 3 ? `${parts[2]}/${parts[1]}` : dateStr;
    };

    const parseBrowser = (ua) => {
        if (!ua) return 'Khác';
        if (ua.includes('Edg/')) return 'Edge';
        if (ua.includes('Chrome/')) return 'Chrome';
        if (ua.includes('Firefox/')) return 'Firefox';
        if (ua.includes('Safari/') && !ua.includes('Chrome/')) return 'Safari';
        if (ua.includes('OPR/') || ua.includes('Opera/')) return 'Opera';
        return 'Khác';
    };

    const parseOS = (ua) => {
        if (!ua) return '';
        if (ua.includes('Windows')) return 'Windows';
        if (ua.includes('Macintosh') || ua.includes('Mac OS')) return 'macOS';
        if (ua.includes('Android')) return 'Android';
        if (ua.includes('iPhone') || ua.includes('iPad')) return 'iOS';
        if (ua.includes('Linux')) return 'Linux';
        return '';
    };

    // BUG FIX: Kiểm tra CẢ active lẫn expiresAt
    const isExpired = stats?.expiresAt ? new Date(stats.expiresAt.replace ? stats.expiresAt.replace('Z','') : stats.expiresAt) < new Date() : false;
    const isActive = stats?.active === true;
    // Trạng thái tổng hợp: inactive > expired > active
    const linkStatus = !isActive ? 'inactive' : isExpired ? 'expired' : 'active';

    const chartData = useMemo(() => stats?.dailyClicks || [], [stats]);
    const maxClicks = useMemo(() => chartData.length ? Math.max(...chartData.map(d => d.clicks), 1) : 0, [chartData]);
    const peakDay = useMemo(() => chartData.length ? [...chartData].sort((a, b) => b.clicks - a.clicks)[0] : null, [chartData]);
    const averageClicks = useMemo(() => {
        if (!chartData.length) return '0';
        return (chartData.reduce((acc, c) => acc + c.clicks, 0) / chartData.length).toFixed(1);
    }, [chartData]);

    const deviceEntries = useMemo(() => {
        if (!stats?.deviceStats) return [];
        return Object.entries(stats.deviceStats).sort((a, b) => b[1] - a[1]);
    }, [stats]);
    const totalDeviceClicks = useMemo(() => deviceEntries.reduce((acc, c) => acc + c[1], 0) || 1, [deviceEntries]);

    const referrerEntries = useMemo(() => {
        if (!stats?.referrerStats) return [];
        return Object.entries(stats.referrerStats).sort((a, b) => b[1] - a[1]);
    }, [stats]);

    const countryEntries = useMemo(() => {
        if (!stats?.countryStats) return [];
        return Object.entries(stats.countryStats).sort((a, b) => b[1] - a[1]);
    }, [stats]);

    const svgPoints = useMemo(() => {
        if (!chartData.length) return { path: '', area: '', points: [] };
        const W = 700, H = 200, P = 36;
        const ew = W - P * 2, eh = H - P * 2;
        const points = chartData.map((item, idx) => ({
            x: chartData.length === 1 ? W / 2 : P + (idx / (chartData.length - 1)) * ew,
            y: H - P - (item.clicks / maxClicks) * eh,
            ...item,
        }));
        const lineCmd = points.reduce((acc, pt, i) => `${acc} ${i === 0 ? 'M' : 'L'} ${pt.x} ${pt.y}`, '');
        const areaCmd = `${lineCmd} L ${points[points.length - 1].x} ${H - P} L ${points[0].x} ${H - P} Z`;
        return { points, lineCmd, areaCmd, W, H, P };
    }, [chartData, maxClicks]);

    // ─── Status config ───────────────────────────────────
    const statusConfig = {
        active:   { label: 'Hoạt động',  sub: 'Sẵn sàng chuyển hướng', dot: 'pulse-green',   pill: 'badge-active'   },
        inactive: { label: 'Đang tắt',   sub: 'Liên kết bị vô hiệu hoá', dot: 'dot-amber',  pill: 'badge-paused'   },
        expired:  { label: 'Đã hết hạn', sub: 'Quá thời hạn sử dụng',  dot: 'dot-red',     pill: 'badge-expired'  },
    };
    const sc = statusConfig[linkStatus];

    return (
        <div className="stats-page">
            {/* ── TOP NAV ─────────────────────────────────── */}
            <div className="stats-topnav">
                <div className="stats-topnav-inner">
                    <Link to="/dashboard" className="back-link">
                        <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
                        </svg>
                        Quay lại
                    </Link>
                    <div className="topnav-right">
                        <span className="stats-breadcrumb">Thống kê /{shortCode}</span>
                        <button onClick={fetchStats} disabled={loading} className="btn-refresh-stats">
                            <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5"
                                className={loading ? 'spin' : ''}>
                                <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                            </svg>
                            {loading ? 'Đang tải...' : 'Làm mới'}
                        </button>
                    </div>
                </div>
            </div>

            <div className="stats-container">
                {/* ── ERROR ───────────────────────────────── */}
                {error && (
                    <div className="error-banner">
                        <svg width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4.5c-.77-.833-2.694-.833-3.464 0L3.34 16.5c-.77.833.192 2.5 1.732 2.5z" />
                        </svg>
                        {error}
                    </div>
                )}

                {/* ── LOADING ─────────────────────────────── */}
                {loading && !stats ? (
                    <div className="empty-state">
                        <div className="loading-spinner"></div>
                        <h3>Đang phân tích dữ liệu...</h3>
                        <p>Vui lòng chờ trong giây lát</p>
                    </div>
                ) : !stats ? (
                    <div className="empty-state">
                        <div className="empty-icon">
                            <svg width="44" height="44" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                            </svg>
                        </div>
                        <h3>Không có dữ liệu</h3>
                        <p>Không tìm thấy thống kê cho liên kết này.</p>
                    </div>
                ) : (
                    <div className="stats-body">

                        {/* ── HERO LINK CARD ──────────────────── */}
                        <div className="link-hero-card">
                            <div className="link-hero-top">
                                <div className="link-hero-info">
                                    {/* Pills row */}
                                    <div className="hero-pills">
                                        <span className="code-pill">/{stats.shortCode}</span>
                                        <span className={`badge ${sc.pill}`}>
                                            <span className={`badge-dot ${sc.dot === 'pulse-green' ? '' : ''}`}
                                                style={{
                                                    animation: linkStatus === 'active' ? 'pulse-green 2s infinite' : 'none',
                                                    background: linkStatus === 'active' ? 'var(--accent-green)' :
                                                                linkStatus === 'inactive' ? 'var(--accent-amber)' : 'var(--accent-red)'
                                                }}
                                            />
                                            {sc.label}
                                        </span>
                                    </div>

                                    {/* Short URL */}
                                    <div className="hero-url-row">
                                        <a href={shortUrl} target="_blank" rel="noopener noreferrer" className="hero-shorturl">
                                            {shortUrl}
                                            <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                                            </svg>
                                        </a>
                                    </div>

                                    {/* Original URL */}
                                    <p className="hero-original" title={stats.originalUrl}>
                                        <svg width="13" height="13" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
                                        </svg>
                                        {stats.originalUrl}
                                    </p>
                                </div>

                                {/* Copy button */}
                                <button onClick={handleCopy} className={`btn-copy ${copied ? 'copied' : ''}`}>
                                    {copied ? (
                                        <svg width="15" height="15" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                                        </svg>
                                    ) : (
                                        <svg width="15" height="15" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                                        </svg>
                                    )}
                                    {copied ? 'Đã sao chép!' : 'Sao chép link'}
                                </button>
                            </div>

                            {/* Meta row */}
                            <div className="hero-meta">
                                <span>
                                    <svg width="13" height="13" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
                                    </svg>
                                    Tạo: <strong>{formatDate(stats.createdAt)}</strong>
                                </span>
                                <span>
                                    <svg width="13" height="13" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                                    </svg>
                                    Hết hạn: <strong className={isExpired ? 'text-red' : ''}>
                                        {stats.expiresAt ? formatDate(stats.expiresAt) : 'Vĩnh viễn'}
                                    </strong>
                                </span>
                            </div>

                            {/* Warning banners */}
                            {linkStatus === 'inactive' && (
                                <div className="stats-warning amber">
                                    <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" />
                                    </svg>
                                    <span>Liên kết đang <strong>bị tắt</strong> — người truy cập sẽ không được chuyển hướng. Quay lại Dashboard để bật lại.</span>
                                </div>
                            )}
                            {linkStatus === 'expired' && (
                                <div className="stats-warning red">
                                    <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                        <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4.5c-.77-.833-2.694-.833-3.464 0L3.34 16.5c-.77.833.192 2.5 1.732 2.5z" />
                                    </svg>
                                    <span>Liên kết đã <strong>hết hạn</strong> ({formatDate(stats.expiresAt)}). Dữ liệu thống kê vẫn được lưu đầy đủ.</span>
                                </div>
                            )}
                        </div>

                        {/* ── METRIC CARDS ────────────────────── */}
                        <div className="metrics-grid">
                            {/* Total clicks */}
                            <div className="metric-card">
                                <div className="metric-header">
                                    <span className="metric-label">Tổng lượt nhấp</span>
                                    <div className="metric-icon indigo">
                                        <svg width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5" />
                                        </svg>
                                    </div>
                                </div>
                                <div className="metric-value">{stats.totalClicks.toLocaleString('vi-VN')}</div>
                                <div className="metric-sub green">Tất cả lần chuyển hướng</div>
                            </div>

                            {/* Peak day */}
                            <div className="metric-card">
                                <div className="metric-header">
                                    <span className="metric-label">Ngày cao điểm</span>
                                    <div className="metric-icon amber">
                                        <svg width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M17.657 18.657A8 8 0 016.343 7.343S7 9 9 10c0-2 .5-5 2.986-7C14 5 16.09 5.777 17.656 7.343A7.975 7.975 0 0120 13a7.975 7.975 0 01-2.343 5.657z" />
                                        </svg>
                                    </div>
                                </div>
                                <div className="metric-value">{peakDay ? peakDay.clicks.toLocaleString('vi-VN') : '—'}</div>
                                <div className="metric-sub muted">{peakDay ? peakDay.date : 'Chưa có dữ liệu'}</div>
                            </div>

                            {/* Average / day */}
                            <div className="metric-card">
                                <div className="metric-header">
                                    <span className="metric-label">Trung bình / ngày</span>
                                    <div className="metric-icon blue">
                                        <svg width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                                        </svg>
                                    </div>
                                </div>
                                <div className="metric-value">{averageClicks}</div>
                                <div className="metric-sub muted">Trên {chartData.length} ngày ghi nhận</div>
                            </div>

                            {/* Status */}
                            <div className={`metric-card status-card-${linkStatus}`}>
                                <div className="metric-header">
                                    <span className="metric-label">Trạng thái</span>
                                    <div className={`metric-icon ${linkStatus === 'active' ? 'green' : linkStatus === 'inactive' ? 'amber' : 'red'}`}>
                                        <svg width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            {linkStatus === 'active' ? (
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                                            ) : linkStatus === 'inactive' ? (
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" />
                                            ) : (
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4.5c-.77-.833-2.694-.833-3.464 0L3.34 16.5c-.77.833.192 2.5 1.732 2.5z" />
                                            )}
                                        </svg>
                                    </div>
                                </div>
                                <div className={`metric-status-label ${linkStatus}`}>{sc.label}</div>
                                <div className="metric-sub muted">{sc.sub}</div>
                            </div>
                        </div>

                        {/* ── CHART ───────────────────────────── */}
                        <div className="stats-section">
                            <div className="section-header">
                                <div>
                                    <h2 className="section-title">Biểu đồ lượt click theo ngày</h2>
                                    <p className="section-sub">Xu hướng tương tác theo thời gian</p>
                                </div>
                                {chartData.length > 0 && (
                                    <div className="chart-toggle">
                                        <button
                                            onClick={() => setChartType('bar')}
                                            className={`chart-toggle-btn ${chartType === 'bar' ? 'active' : ''}`}
                                        >
                                            <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                                            </svg>
                                            Cột
                                        </button>
                                        <button
                                            onClick={() => setChartType('line')}
                                            className={`chart-toggle-btn ${chartType === 'line' ? 'active' : ''}`}
                                        >
                                            <svg width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                <path strokeLinecap="round" strokeLinejoin="round" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                                            </svg>
                                            Đường
                                        </button>
                                    </div>
                                )}
                            </div>

                            {chartData.length === 0 ? (
                                <div className="chart-empty">
                                    <div className="empty-icon" style={{ width: 64, height: 64 }}>
                                        <svg width="36" height="36" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                                        </svg>
                                    </div>
                                    <p>Chưa có lượt click nào được ghi nhận</p>
                                    <a href={shortUrl} target="_blank" rel="noopener noreferrer" className="chart-test-link">
                                        Mở link để kiểm tra →
                                    </a>
                                </div>
                            ) : chartType === 'bar' ? (
                                <div className="bar-chart-wrap">
                                    <div className="bar-chart">
                                        {chartData.map((item, idx) => {
                                            const h = Math.max((item.clicks / maxClicks) * 100, 6);
                                            const hov = hoveredData?.date === item.date;
                                            return (
                                                <div
                                                    key={idx}
                                                    className="bar-col"
                                                    onMouseEnter={() => setHoveredData(item)}
                                                    onMouseLeave={() => setHoveredData(null)}
                                                >
                                                    {hov && (
                                                        <div className="bar-tooltip">
                                                            <strong>{item.clicks}</strong> lượt<br />
                                                            <span>{item.date}</span>
                                                        </div>
                                                    )}
                                                    <span className="bar-count">{item.clicks}</span>
                                                    <div
                                                        className={`bar-fill ${hov ? 'hovered' : ''}`}
                                                        style={{ height: `${h}%` }}
                                                    />
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div className="bar-labels">
                                        {chartData.map((item, idx) => (
                                            <div key={idx} className="bar-label" title={item.date}>
                                                {formatShortDate(item.date)}
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            ) : (
                                <div className="line-chart-wrap">
                                    <svg viewBox={`0 0 ${svgPoints.W} ${svgPoints.H}`} className="line-chart-svg">
                                        <defs>
                                            <linearGradient id="areaGrad" x1="0" y1="0" x2="0" y2="1">
                                                <stop offset="0%" stopColor="rgb(99,102,241)" stopOpacity="0.25" />
                                                <stop offset="100%" stopColor="rgb(99,102,241)" stopOpacity="0" />
                                            </linearGradient>
                                        </defs>
                                        <line x1={svgPoints.P} y1={svgPoints.H - svgPoints.P}
                                              x2={svgPoints.W - svgPoints.P} y2={svgPoints.H - svgPoints.P}
                                              stroke="#E2E8F0" strokeWidth="1" />
                                        <line x1={svgPoints.P} y1={(svgPoints.H) / 2}
                                              x2={svgPoints.W - svgPoints.P} y2={(svgPoints.H) / 2}
                                              stroke="#F1F5F9" strokeDasharray="4 4" strokeWidth="1" />
                                        <path d={svgPoints.areaCmd} fill="url(#areaGrad)" />
                                        <path d={svgPoints.lineCmd} fill="none" stroke="#6366f1" strokeWidth="2.5"
                                              strokeLinecap="round" strokeLinejoin="round" />
                                        {svgPoints.points.map((pt, i) => (
                                            <circle key={i} cx={pt.x} cy={pt.y} r="4.5"
                                                fill="white" stroke="#6366f1" strokeWidth="2.5"
                                                style={{ cursor: 'pointer' }}
                                                onMouseEnter={() => setHoveredData(pt)}
                                                onMouseLeave={() => setHoveredData(null)}
                                            />
                                        ))}
                                    </svg>
                                    <div className="line-chart-hint">
                                        {hoveredData ? (
                                            <span className="line-chart-hovered">
                                                Ngày {hoveredData.date}: <strong>{hoveredData.clicks}</strong> lượt click
                                            </span>
                                        ) : (
                                            <span>Rê chuột vào điểm tròn để xem chi tiết</span>
                                        )}
                                    </div>
                                </div>
                            )}
                        </div>

                        {/* ── BREAKDOWN PANELS ────────────────── */}
                        <div className="breakdown-grid">
                            {/* Thiết bị */}
                            <div className="breakdown-card">
                                <div className="breakdown-header">
                                    <div className="breakdown-icon indigo">
                                        <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                                        </svg>
                                    </div>
                                    <h3 className="breakdown-title">Thiết bị</h3>
                                    <span className="breakdown-badge">Tỷ lệ %</span>
                                </div>
                                {deviceEntries.length === 0 ? (
                                    <p className="breakdown-empty">Chưa có dữ liệu</p>
                                ) : (
                                    <div className="breakdown-list">
                                        {deviceEntries.map(([device, count]) => {
                                            const pct = Math.round((count / totalDeviceClicks) * 100);
                                            const mob = device.toLowerCase().includes('mobile');
                                            return (
                                                <div key={device} className="breakdown-item">
                                                    <div className="breakdown-item-top">
                                                        <span className="breakdown-item-name">
                                                            {mob ? (
                                                                <svg width="12" height="12" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
                                                                </svg>
                                                            ) : (
                                                                <svg width="12" height="12" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                                                                </svg>
                                                            )}
                                                            {device}
                                                        </span>
                                                        <span className="breakdown-item-count">{pct}% ({count})</span>
                                                    </div>
                                                    <div className="breakdown-bar-bg">
                                                        <div className={`breakdown-bar-fill ${mob ? 'indigo' : 'blue'}`} style={{ width: `${pct}%` }} />
                                                    </div>
                                                </div>
                                            );
                                        })}
                                    </div>
                                )}
                            </div>

                            {/* Nguồn */}
                            <div className="breakdown-card">
                                <div className="breakdown-header">
                                    <div className="breakdown-icon purple">
                                        <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                                        </svg>
                                    </div>
                                    <h3 className="breakdown-title">Nguồn (Referrer)</h3>
                                    <span className="breakdown-badge">Top 5</span>
                                </div>
                                {referrerEntries.length === 0 ? (
                                    <p className="breakdown-empty">Chưa có dữ liệu</p>
                                ) : (
                                    <div className="breakdown-list">
                                        {referrerEntries.slice(0, 5).map(([ref, count]) => (
                                            <div key={ref} className="referrer-row">
                                                <span className="referrer-name" title={ref}>{ref}</span>
                                                <span className="referrer-count">{count}</span>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>

                            {/* Quốc gia */}
                            <div className="breakdown-card">
                                <div className="breakdown-header">
                                    <div className="breakdown-icon green">
                                        <svg width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
                                            <path strokeLinecap="round" strokeLinejoin="round" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
                                        </svg>
                                    </div>
                                    <h3 className="breakdown-title">Quốc gia</h3>
                                    <span className="breakdown-badge">Top 5</span>
                                </div>
                                {countryEntries.length === 0 ? (
                                    <p className="breakdown-empty">Chưa có dữ liệu</p>
                                ) : (
                                    <div className="breakdown-list">
                                        {countryEntries.slice(0, 5).map(([country, count]) => (
                                            <div key={country} className="referrer-row">
                                                <span className="referrer-name">🌏 {country}</span>
                                                <span className="referrer-count">{count} click</span>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* ── CLICK LOG TABLE ─────────────────── */}
                        <div className="stats-section" style={{ padding: 0, overflow: 'hidden' }}>
                            <div className="log-header">
                                <div>
                                    <h2 className="section-title">Nhật ký click</h2>
                                    <p className="section-sub">Chi tiết IP, thiết bị, nguồn truy cập</p>
                                </div>
                                <span className="log-count-badge">
                                    {clicks.length} / {totalClicksCount} lượt
                                </span>
                            </div>

                            {clicks.length === 0 ? (
                                <div className="log-empty">Chưa có lượt click nào được ghi nhận.</div>
                            ) : (
                                <>
                                    <div
                                        ref={scrollContainerRef}
                                        onScroll={handleScrollLoad}
                                        className="log-scroll"
                                    >
                                        <table className="log-table">
                                            <thead>
                                                <tr>
                                                    <th>Thời gian</th>
                                                    <th>Thiết bị</th>
                                                    <th>IP</th>
                                                    <th>Quốc gia</th>
                                                    <th>Nguồn</th>
                                                    <th>Trình duyệt</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {clicks.map((click, idx) => {
                                                    const mob = click.deviceType?.toLowerCase().includes('mobile');
                                                    const browser = parseBrowser(click.userAgent);
                                                    const os = parseOS(click.userAgent);
                                                    return (
                                                        <tr key={idx}>
                                                            <td className="log-cell-time">{formatDate(click.clickedAt)}</td>
                                                            <td>
                                                                <span className={`device-badge ${mob ? 'mobile' : 'desktop'}`}>
                                                                    {mob ? (
                                                                        <svg width="11" height="11" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                                            <path strokeLinecap="round" strokeLinejoin="round" d="M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z" />
                                                                        </svg>
                                                                    ) : (
                                                                        <svg width="11" height="11" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                                                                            <path strokeLinecap="round" strokeLinejoin="round" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                                                                        </svg>
                                                                    )}
                                                                    {mob ? 'Mobile' : 'Desktop'}
                                                                </span>
                                                            </td>
                                                            <td className="log-cell-ip">{click.ipAddress || '—'}</td>
                                                            <td>
                                                                <span className="country-badge">🌏 {click.country || 'VN'}</span>
                                                            </td>
                                                            <td className="log-cell-ref" title={click.referrer}>
                                                                {click.referrer || 'Direct'}
                                                            </td>
                                                            <td>
                                                                <span className="log-cell-browser">
                                                                    {browser}
                                                                    {os && <span className="log-cell-os"> / {os}</span>}
                                                                </span>
                                                            </td>
                                                        </tr>
                                                    );
                                                })}
                                            </tbody>
                                        </table>
                                    </div>
                                    <div className="log-footer">
                                        <span>Hiển thị <strong>{clicks.length}</strong> / <strong>{totalClicksCount}</strong> lượt click</span>
                                        {loadingMoreClicks ? (
                                            <span className="log-loading">
                                                <div className="btn-spinner" style={{ width: 14, height: 14 }}></div>
                                                Đang tải thêm...
                                            </span>
                                        ) : hasMoreClicks ? (
                                            <button onClick={loadMoreClicks} className="log-load-more">
                                                Tải thêm →
                                            </button>
                                        ) : (
                                            <span className="log-done">
                                                <svg width="13" height="13" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                                                </svg>
                                                Đã hiển thị hết
                                            </span>
                                        )}
                                    </div>
                                </>
                            )}
                        </div>

                    </div>
                )}
            </div>
        </div>
    );
}
