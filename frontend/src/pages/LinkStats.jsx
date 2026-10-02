import { useEffect, useState, useMemo } from 'react';
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

    useEffect(() => {
        const token = localStorage.getItem('token');
        if (!token || token === 'undefined' || token === 'null') {
            navigate('/login');
            return;
        }
        fetchStats();
    }, [shortCode, navigate]);

    const fetchStats = async () => {
        setLoading(true);
        setError('');
        try {
            const res = await api.get(`/api/links/${shortCode}/stats`);
            setStats(res.data);
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
        }
    };

    const shortUrl = stats ? `http://localhost:8080/r/${stats.shortCode}` : '';

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
        return new Date(isoString).toLocaleString('vi-VN', {
            day: '2-digit',
            month: '2-digit',
            year: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
    };

    const formatShortDate = (dateStr) => {
        if (!dateStr) return '';
        const parts = dateStr.split('-');
        if (parts.length === 3) {
            return `${parts[2]}/${parts[1]}`;
        }
        return dateStr;
    };

    // Helper phân tích User-Agent thành tên trình duyệt ngắn gọn
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

    const isExpired = stats?.expiresAt ? new Date(stats.expiresAt) < new Date() : false;

    // Tính toán số liệu biểu đồ theo ngày
    const chartData = useMemo(() => {
        return stats?.dailyClicks || [];
    }, [stats]);

    const maxClicks = useMemo(() => {
        if (!chartData.length) return 0;
        return Math.max(...chartData.map((d) => d.clicks), 1);
    }, [chartData]);

    const peakDay = useMemo(() => {
        if (!chartData.length) return null;
        return [...chartData].sort((a, b) => b.clicks - a.clicks)[0];
    }, [chartData]);

    const averageClicks = useMemo(() => {
        if (!chartData.length) return 0;
        const total = chartData.reduce((acc, curr) => acc + curr.clicks, 0);
        return (total / chartData.length).toFixed(1);
    }, [chartData]);

    // Thống kê thiết bị
    const deviceEntries = useMemo(() => {
        if (!stats?.deviceStats) return [];
        return Object.entries(stats.deviceStats).sort((a, b) => b[1] - a[1]);
    }, [stats]);

    const totalDeviceClicks = useMemo(() => {
        return deviceEntries.reduce((acc, curr) => acc + curr[1], 0) || 1;
    }, [deviceEntries]);

    // Thống kê nguồn (Referrers)
    const referrerEntries = useMemo(() => {
        if (!stats?.referrerStats) return [];
        return Object.entries(stats.referrerStats).sort((a, b) => b[1] - a[1]);
    }, [stats]);

    // Thống kê quốc gia (Countries)
    const countryEntries = useMemo(() => {
        if (!stats?.countryStats) return [];
        return Object.entries(stats.countryStats).sort((a, b) => b[1] - a[1]);
    }, [stats]);

    // Danh sách click gần nhất
    const recentClicks = useMemo(() => {
        return stats?.recentClicks || [];
    }, [stats]);

    // Tạo tọa độ đường thẳng SVG cho Area/Line Chart
    const svgPoints = useMemo(() => {
        if (chartData.length === 0) return { path: '', area: '' };
        const width = 700;
        const height = 220;
        const padding = 40;
        const effectiveWidth = width - padding * 2;
        const effectiveHeight = height - padding * 2;

        const points = chartData.map((item, idx) => {
            const x = chartData.length === 1
                ? width / 2
                : padding + (idx / (chartData.length - 1)) * effectiveWidth;
            const y = height - padding - (item.clicks / maxClicks) * effectiveHeight;
            return { x, y, ...item };
        });

        const lineCmd = points.reduce((acc, pt, i) => `${acc} ${i === 0 ? 'M' : 'L'} ${pt.x} ${pt.y}`, '');
        const areaCmd = `${lineCmd} L ${points[points.length - 1].x} ${height - padding} L ${points[0].x} ${height - padding} Z`;

        return { points, lineCmd, areaCmd, width, height, padding };
    }, [chartData, maxClicks]);

    return (
        <div className="min-h-screen bg-slate-50 py-10 px-4 sm:px-6 lg:px-8 font-sans">
            <div className="max-w-5xl mx-auto">
                {/* Thanh điều hướng quay lại */}
                <div className="mb-6 flex items-center justify-between">
                    <Link
                        to="/dashboard"
                        className="inline-flex items-center text-sm font-medium text-slate-600 hover:text-indigo-600 transition-colors gap-1.5"
                    >
                        <span className="text-lg">←</span> Quay lại danh sách link
                    </Link>

                    <button
                        onClick={fetchStats}
                        className="text-xs px-3 py-1.5 bg-white border border-slate-200 rounded-lg text-slate-600 hover:bg-slate-100 shadow-sm flex items-center gap-1.5 transition-colors"
                    >
                        🔄 Làm mới dữ liệu
                    </button>
                </div>

                {error && (
                    <div className="mb-6 bg-rose-50 border-l-4 border-rose-500 p-4 rounded-r-lg text-rose-700 text-sm">
                        {error}
                    </div>
                )}

                {loading ? (
                    <div className="flex flex-col items-center justify-center py-24">
                        <div className="w-12 h-12 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin"></div>
                        <p className="mt-4 text-slate-500 text-sm">Đang tải và phân tích dữ liệu...</p>
                    </div>
                ) : !stats ? (
                    <div className="text-center py-16 bg-white rounded-2xl shadow-sm border border-slate-200">
                        <p className="text-slate-500">Không có dữ liệu thống kê cho mã rút gọn này.</p>
                    </div>
                ) : (
                    <div className="space-y-6">
                        {/* Header Thông tin link */}
                        <div className="bg-white rounded-2xl p-6 sm:p-8 shadow-sm border border-slate-200/80">
                            <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
                                <div className="space-y-1.5 min-w-0 flex-1">
                                    <div className="flex items-center gap-2 flex-wrap">
                                        <span className="px-2.5 py-0.5 text-xs font-semibold uppercase tracking-wider rounded-md bg-indigo-50 text-indigo-700 border border-indigo-100">
                                            /{stats.shortCode}
                                        </span>
                                        {isExpired ? (
                                            <span className="px-2.5 py-0.5 text-xs font-medium bg-rose-100 text-rose-700 rounded-full">
                                                Đã hết hạn
                                            </span>
                                        ) : (
                                            <span className="px-2.5 py-0.5 text-xs font-medium bg-emerald-100 text-emerald-700 rounded-full flex items-center gap-1">
                                                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
                                                Đang hoạt động
                                            </span>
                                        )}
                                    </div>

                                    <h1 className="text-xl sm:text-2xl font-bold text-slate-900 break-all">
                                        <a
                                            href={shortUrl}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                            className="text-indigo-600 hover:text-indigo-700 hover:underline inline-flex items-center gap-1.5"
                                        >
                                            {shortUrl}
                                            <span className="text-sm">↗</span>
                                        </a>
                                    </h1>

                                    <p className="text-sm text-slate-500 truncate" title={stats.originalUrl}>
                                        <span className="font-medium text-slate-700">Link gốc:</span> {stats.originalUrl}
                                    </p>
                                </div>

                                <div className="flex items-center gap-2 shrink-0">
                                    <button
                                        onClick={handleCopy}
                                        className={`px-4 py-2 text-sm font-medium rounded-xl shadow-sm transition-all flex items-center gap-1.5 ${
                                            copied
                                                ? 'bg-emerald-600 text-white'
                                                : 'bg-indigo-600 text-white hover:bg-indigo-700'
                                        }`}
                                    >
                                        {copied ? '✓ Đã sao chép!' : '📋 Sao chép link'}
                                    </button>
                                </div>
                            </div>

                            {/* Meta info row */}
                            <div className="mt-6 pt-5 border-t border-slate-100 flex flex-wrap gap-x-8 gap-y-2 text-xs text-slate-500">
                                <div>
                                    <span className="text-slate-400">Ngày tạo:</span>{' '}
                                    <strong className="text-slate-700 font-medium">{formatDate(stats.createdAt)}</strong>
                                </div>
                                <div>
                                    <span className="text-slate-400">Hạn sử dụng:</span>{' '}
                                    <strong className={`font-medium ${isExpired ? 'text-rose-600' : 'text-slate-700'}`}>
                                        {stats.expiresAt ? formatDate(stats.expiresAt) : 'Vĩnh viễn'}
                                        {isExpired && ' (Đã hết hạn)'}
                                    </strong>
                                </div>
                            </div>
                        </div>

                        {/* Banner thông báo nếu link đã hết hạn */}
                        {isExpired && (
                            <div className="flex items-start gap-3 p-4 rounded-2xl bg-amber-50/80 border border-amber-200/70 text-amber-900 shadow-sm">
                                <span className="text-xl shrink-0 mt-0.5">⚠️</span>
                                <div className="text-sm">
                                    <p className="font-semibold text-amber-900">
                                        Link này đã hết hạn sử dụng ({formatDate(stats.expiresAt)})
                                    </p>
                                    <p className="text-amber-700/90 text-xs mt-0.5 leading-relaxed">
                                        Người dùng truy cập link rút gọn sẽ nhận được thông báo hết hạn. Toàn bộ dữ liệu phân tích và lịch sử lượt nhấp trước đây vẫn được lưu trữ đầy đủ bên dưới.
                                    </p>
                                </div>
                            </div>
                        )}

                        {/* Thẻ thống kê nhanh (Metric Cards) */}
                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                            {/* Card 1: Tổng lượt click */}
                            <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-sm relative overflow-hidden">
                                <div className="flex items-center justify-between">
                                    <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                        Tổng lượt click
                                    </span>
                                    <span className="p-2 rounded-xl bg-indigo-50 text-indigo-600 text-lg">👆</span>
                                </div>
                                <div className="mt-3">
                                    <span className="text-3xl font-extrabold text-slate-900 tracking-tight">
                                        {stats.totalClicks.toLocaleString('vi-VN')}
                                    </span>
                                    <span className="text-xs text-slate-400 ml-1">lượt</span>
                                </div>
                                <div className="mt-2 text-xs text-emerald-600 font-medium">
                                    Tất cả các lần chuyển hướng
                                </div>
                            </div>

                            {/* Card 2: Ngày cao điểm */}
                            <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-sm">
                                <div className="flex items-center justify-between">
                                    <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                        Ngày cao điểm
                                    </span>
                                    <span className="p-2 rounded-xl bg-amber-50 text-amber-600 text-lg">🔥</span>
                                </div>
                                <div className="mt-3">
                                    <span className="text-2xl font-bold text-slate-900">
                                        {peakDay ? peakDay.clicks : 0}
                                    </span>
                                    <span className="text-xs text-slate-400 ml-1">click</span>
                                </div>
                                <div className="mt-2 text-xs text-slate-500 truncate">
                                    {peakDay ? `Vào ngày: ${peakDay.date}` : 'Chưa có dữ liệu'}
                                </div>
                            </div>

                            {/* Card 3: Trung bình mỗi ngày */}
                            <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-sm">
                                <div className="flex items-center justify-between">
                                    <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                        Trung bình / ngày
                                    </span>
                                    <span className="p-2 rounded-xl bg-blue-50 text-blue-600 text-lg">📈</span>
                                </div>
                                <div className="mt-3">
                                    <span className="text-3xl font-bold text-slate-900">{averageClicks}</span>
                                    <span className="text-xs text-slate-400 ml-1">click/ngày</span>
                                </div>
                                <div className="mt-2 text-xs text-slate-500">
                                    Trên {chartData.length} mốc ngày ghi nhận
                                </div>
                            </div>

                            {/* Card 4: Trạng thái hiện tại */}
                            <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-sm">
                                <div className="flex items-center justify-between">
                                    <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                                        Trạng thái
                                    </span>
                                    <span className="p-2 rounded-xl bg-slate-50 text-slate-600 text-lg">⚡</span>
                                </div>
                                <div className="mt-3">
                                    <span className={`text-xl font-bold ${isExpired ? 'text-rose-600' : 'text-emerald-600'}`}>
                                        {isExpired ? 'Đã khoá' : 'Hoạt động'}
                                    </span>
                                </div>
                                <div className="mt-2 text-xs text-slate-500">
                                    {isExpired ? 'Link đã quá thời hạn' : 'Sẵn sàng chuyển hướng'}
                                </div>
                            </div>
                        </div>

                        {/* Biểu đồ truy cập (Chart Section) */}
                        <div className="bg-white rounded-2xl p-6 sm:p-8 shadow-sm border border-slate-200/80">
                            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
                                <div>
                                    <h2 className="text-lg font-bold text-slate-900">Biểu đồ lượt click theo ngày</h2>
                                    <p className="text-xs text-slate-500 mt-0.5">
                                        Theo dõi xu hướng tương tác người dùng theo thời gian
                                    </p>
                                </div>

                                {chartData.length > 0 && (
                                    <div className="inline-flex rounded-xl bg-slate-100 p-1 self-start sm:self-auto">
                                        <button
                                            onClick={() => setChartType('bar')}
                                            className={`px-3 py-1 text-xs font-semibold rounded-lg transition-all ${
                                                chartType === 'bar'
                                                    ? 'bg-white text-indigo-700 shadow-sm'
                                                    : 'text-slate-600 hover:text-slate-900'
                                            }`}
                                        >
                                            📊 Cột (Bar)
                                        </button>
                                        <button
                                            onClick={() => setChartType('line')}
                                            className={`px-3 py-1 text-xs font-semibold rounded-lg transition-all ${
                                                chartType === 'line'
                                                    ? 'bg-white text-indigo-700 shadow-sm'
                                                    : 'text-slate-600 hover:text-slate-900'
                                            }`}
                                        >
                                            📈 Đường (Area)
                                        </button>
                                    </div>
                                )}
                            </div>

                            {chartData.length === 0 ? (
                                <div className="text-center py-14 px-4 bg-slate-50/60 rounded-xl border border-dashed border-slate-200">
                                    <div className="text-4xl mb-3">🎯</div>
                                    <h3 className="text-sm font-semibold text-slate-800">Chưa có lượt click nào</h3>
                                    <p className="text-xs text-slate-500 mt-1 max-w-sm mx-auto">
                                        Link vừa tạo hoặc chưa được chia sẻ. Bạn có thể mở thử link này để kiểm tra hệ thống ghi nhận click ngay!
                                    </p>
                                    <a
                                        href={shortUrl}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        className="mt-4 inline-flex items-center px-4 py-2 text-xs font-medium text-indigo-600 bg-indigo-50 rounded-lg hover:bg-indigo-100 transition-colors"
                                    >
                                        Mở link kiểm tra ngay →
                                    </a>
                                </div>
                            ) : chartType === 'bar' ? (
                                <div className="space-y-4">
                                    <div className="h-64 flex items-end gap-2 sm:gap-4 pt-8 pb-2 px-2 overflow-x-auto border-b border-slate-200">
                                        {chartData.map((item, idx) => {
                                            const heightPercent = Math.max((item.clicks / maxClicks) * 100, 8);
                                            const isHovered = hoveredData?.date === item.date;

                                            return (
                                                <div
                                                    key={idx}
                                                    className="flex-1 min-w-[48px] max-w-[80px] h-full flex flex-col justify-end items-center group relative cursor-pointer"
                                                    onMouseEnter={() => setHoveredData(item)}
                                                    onMouseLeave={() => setHoveredData(null)}
                                                >
                                                    {isHovered && (
                                                        <div className="absolute -top-10 z-10 bg-slate-900 text-white text-xs px-2.5 py-1 rounded-md shadow-lg whitespace-nowrap pointer-events-none transition-all">
                                                            <strong>{item.clicks}</strong> lượt ({item.date})
                                                        </div>
                                                    )}

                                                    <span className="text-[11px] font-semibold text-slate-600 mb-1.5 opacity-80 group-hover:opacity-100">
                                                        {item.clicks}
                                                    </span>
                                                    <div
                                                        style={{ height: `${heightPercent}%` }}
                                                        className={`w-full rounded-t-lg transition-all duration-300 ${
                                                            isHovered
                                                                ? 'bg-gradient-to-t from-indigo-600 to-indigo-400 shadow-md'
                                                                : 'bg-gradient-to-t from-indigo-500/80 to-indigo-400/80 hover:from-indigo-600 hover:to-indigo-400'
                                                        }`}
                                                    />
                                                </div>
                                            );
                                        })}
                                    </div>

                                    <div className="flex gap-2 sm:gap-4 px-2 overflow-x-auto">
                                        {chartData.map((item, idx) => (
                                            <div
                                                key={idx}
                                                className="flex-1 min-w-[48px] max-w-[80px] text-center text-[11px] font-medium text-slate-500 truncate"
                                                title={item.date}
                                            >
                                                {formatShortDate(item.date)}
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            ) : (
                                <div className="space-y-2">
                                    <div className="relative w-full overflow-x-auto">
                                        <svg
                                            viewBox={`0 0 ${svgPoints.width} ${svgPoints.height}`}
                                            className="w-full h-64 text-indigo-500 overflow-visible"
                                        >
                                            <defs>
                                                <linearGradient id="areaGrad" x1="0" y1="0" x2="0" y2="1">
                                                    <stop offset="0%" stopColor="rgb(99, 102, 241)" stopOpacity="0.3" />
                                                    <stop offset="100%" stopColor="rgb(99, 102, 241)" stopOpacity="0.0" />
                                                </linearGradient>
                                            </defs>

                                            <line
                                                x1={svgPoints.padding}
                                                y1={svgPoints.height - svgPoints.padding}
                                                x2={svgPoints.width - svgPoints.padding}
                                                y2={svgPoints.height - svgPoints.padding}
                                                stroke="#E2E8F0"
                                                strokeWidth="1"
                                            />
                                            <line
                                                x1={svgPoints.padding}
                                                y1={svgPoints.height / 2}
                                                x2={svgPoints.width - svgPoints.padding}
                                                y2={svgPoints.height / 2}
                                                stroke="#F1F5F9"
                                                strokeDasharray="4 4"
                                                strokeWidth="1"
                                            />

                                            <path d={svgPoints.areaCmd} fill="url(#areaGrad)" />
                                            <path
                                                d={svgPoints.lineCmd}
                                                fill="none"
                                                stroke="currentColor"
                                                strokeWidth="3"
                                                strokeLinecap="round"
                                                strokeLinejoin="round"
                                            />

                                            {svgPoints.points.map((pt, i) => (
                                                <g key={i}>
                                                    <circle
                                                        cx={pt.x}
                                                        cy={pt.y}
                                                        r="5"
                                                        className="fill-white stroke-indigo-600 stroke-[3] hover:r-7 transition-all cursor-pointer"
                                                        onMouseEnter={() => setHoveredData(pt)}
                                                        onMouseLeave={() => setHoveredData(null)}
                                                    />
                                                </g>
                                            ))}
                                        </svg>
                                    </div>

                                    <div className="h-6 text-center text-xs text-slate-500">
                                        {hoveredData ? (
                                            <span className="font-semibold text-indigo-600">
                                                Ngày {hoveredData.date}: {hoveredData.clicks} lượt click
                                            </span>
                                        ) : (
                                            <span>Rê chuột vào các điểm tròn trên biểu đồ để xem chi tiết</span>
                                        )}
                                    </div>
                                </div>
                            )}
                        </div>

                        {/* Phân tích thiết bị, nguồn truy cập & quốc gia (Breakdown Cards) */}
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                            {/* 1. Thiết bị (Device Types) */}
                            <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200/80 flex flex-col justify-between">
                                <div>
                                    <div className="flex items-center justify-between mb-4">
                                        <h3 className="text-base font-bold text-slate-900 flex items-center gap-2">
                                            <span>📱</span> Thiết bị
                                        </h3>
                                        <span className="text-xs text-slate-400">Tỷ lệ %</span>
                                    </div>

                                    {deviceEntries.length === 0 ? (
                                        <p className="text-xs text-slate-400 py-6 text-center">Chưa có dữ liệu thiết bị</p>
                                    ) : (
                                        <div className="space-y-3">
                                            {deviceEntries.map(([device, count]) => {
                                                const percent = Math.round((count / totalDeviceClicks) * 100);
                                                const isMobile = device.toLowerCase().includes('mobile');
                                                return (
                                                    <div key={device} className="space-y-1">
                                                        <div className="flex justify-between text-xs">
                                                            <span className="font-medium text-slate-700 flex items-center gap-1.5">
                                                                {isMobile ? '📱' : '💻'} {device}
                                                            </span>
                                                            <span className="text-slate-500 font-semibold">{percent}% ({count})</span>
                                                        </div>
                                                        <div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden">
                                                            <div
                                                                className={`h-2 rounded-full ${isMobile ? 'bg-indigo-500' : 'bg-blue-500'}`}
                                                                style={{ width: `${percent}%` }}
                                                            />
                                                        </div>
                                                    </div>
                                                );
                                            })}
                                        </div>
                                    )}
                                </div>
                            </div>

                            {/* 2. Nguồn truy cập (Referrers) */}
                            <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200/80">
                                <div className="flex items-center justify-between mb-4">
                                    <h3 className="text-base font-bold text-slate-900 flex items-center gap-2">
                                        <span>🌐</span> Nguồn (Referrer)
                                    </h3>
                                    <span className="text-xs text-slate-400">Top nguồn</span>
                                </div>

                                {referrerEntries.length === 0 ? (
                                    <p className="text-xs text-slate-400 py-6 text-center">Chưa có dữ liệu nguồn</p>
                                ) : (
                                    <div className="space-y-2.5">
                                        {referrerEntries.slice(0, 5).map(([ref, count]) => (
                                            <div key={ref} className="flex items-center justify-between text-xs p-2 rounded-lg bg-slate-50">
                                                <span className="font-medium text-slate-700 truncate max-w-[170px]" title={ref}>
                                                    {ref}
                                                </span>
                                                <span className="px-2 py-0.5 rounded-md bg-white border border-slate-200 text-slate-700 font-bold">
                                                    {count}
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>

                            {/* 3. Quốc gia (Countries) */}
                            <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200/80">
                                <div className="flex items-center justify-between mb-4">
                                    <h3 className="text-base font-bold text-slate-900 flex items-center gap-2">
                                        <span>📍</span> Quốc gia
                                    </h3>
                                    <span className="text-xs text-slate-400">Vị trí</span>
                                </div>

                                {countryEntries.length === 0 ? (
                                    <p className="text-xs text-slate-400 py-6 text-center">Chưa có dữ liệu vị trí</p>
                                ) : (
                                    <div className="space-y-2.5">
                                        {countryEntries.slice(0, 5).map(([country, count]) => (
                                            <div key={country} className="flex items-center justify-between text-xs p-2 rounded-lg bg-slate-50">
                                                <span className="font-medium text-slate-700 flex items-center gap-1.5">
                                                    🇻🇳 {country}
                                                </span>
                                                <span className="px-2 py-0.5 rounded-md bg-white border border-slate-200 text-slate-700 font-bold">
                                                    {count} click
                                                </span>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* Bảng Nhật ký truy cập gần nhất (Recent Clicks Log) */}
                        <div className="bg-white rounded-2xl shadow-sm border border-slate-200/80 overflow-hidden">
                            <div className="p-6 border-b border-slate-100 flex items-center justify-between flex-wrap gap-2">
                                <div>
                                    <h3 className="text-base font-bold text-slate-900 flex items-center gap-2">
                                        <span>📋</span> Nhật ký các lượt click gần nhất
                                    </h3>
                                    <p className="text-xs text-slate-500 mt-0.5">
                                        Hiển thị chi tiết IP, thiết bị, hệ điều hành và nguồn truy cập
                                    </p>
                                </div>
                                <span className="text-xs font-semibold px-2.5 py-1 rounded-full bg-slate-100 text-slate-600">
                                    {recentClicks.length} lượt mới nhất
                                </span>
                            </div>

                            {recentClicks.length === 0 ? (
                                <div className="text-center py-12 text-slate-400 text-xs">
                                    Chưa có lượt click nào được ghi nhận.
                                </div>
                            ) : (
                                <div className="overflow-x-auto">
                                    <table className="w-full text-left text-xs text-slate-600">
                                        <thead className="bg-slate-50/80 text-slate-500 font-semibold border-b border-slate-100 uppercase tracking-wider text-[11px]">
                                            <tr>
                                                <th className="py-3.5 px-4 sm:px-6">Thời gian</th>
                                                <th className="py-3.5 px-4">Thiết bị</th>
                                                <th className="py-3.5 px-4">Địa chỉ IP</th>
                                                <th className="py-3.5 px-4">Quốc gia</th>
                                                <th className="py-3.5 px-4">Nguồn (Referrer)</th>
                                                <th className="py-3.5 px-4 sm:px-6">Trình duyệt & HĐH</th>
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-slate-100">
                                            {recentClicks.map((click, idx) => {
                                                const isMobile = click.deviceType?.toLowerCase().includes('mobile');
                                                const browserName = parseBrowser(click.userAgent);
                                                const osName = parseOS(click.userAgent);

                                                return (
                                                    <tr key={idx} className="hover:bg-slate-50/60 transition-colors">
                                                        {/* Thời gian */}
                                                        <td className="py-3.5 px-4 sm:px-6 font-medium text-slate-800 whitespace-nowrap">
                                                            {formatDate(click.clickedAt)}
                                                        </td>

                                                        {/* Thiết bị */}
                                                        <td className="py-3.5 px-4 whitespace-nowrap">
                                                            <span
                                                                className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-semibold ${
                                                                    isMobile
                                                                        ? 'bg-indigo-50 text-indigo-700 border border-indigo-100'
                                                                        : 'bg-blue-50 text-blue-700 border border-blue-100'
                                                                }`}
                                                            >
                                                                {isMobile ? '📱 Mobile' : '💻 Desktop'}
                                                            </span>
                                                        </td>

                                                        {/* Địa chỉ IP */}
                                                        <td className="py-3.5 px-4 font-mono text-[11px] text-slate-700 whitespace-nowrap">
                                                            {click.ipAddress || '127.0.0.1'}
                                                        </td>

                                                        {/* Quốc gia */}
                                                        <td className="py-3.5 px-4 whitespace-nowrap">
                                                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-slate-100 text-slate-700 font-medium">
                                                                🇻🇳 {click.country || 'VN'}
                                                            </span>
                                                        </td>

                                                        {/* Referrer */}
                                                        <td className="py-3.5 px-4 max-w-[180px] truncate" title={click.referrer}>
                                                            <span className="text-slate-700 font-medium">
                                                                {click.referrer || 'Direct'}
                                                            </span>
                                                        </td>

                                                        {/* Trình duyệt & HĐH */}
                                                        <td className="py-3.5 px-4 sm:px-6 max-w-[200px] truncate" title={click.userAgent}>
                                                            <span className="font-medium text-slate-800">{browserName}</span>
                                                            {osName && <span className="text-slate-400 ml-1">/ {osName}</span>}
                                                        </td>
                                                    </tr>
                                                );
                                            })}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}
