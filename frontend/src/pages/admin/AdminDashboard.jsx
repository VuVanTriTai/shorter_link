import { useEffect, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../../api/axios';

export default function AdminDashboard() {
    const [stats, setStats] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const navigate = useNavigate();

    useEffect(() => {
        const role = localStorage.getItem('role');
        if (role !== 'ROLE_ADMIN') {
            navigate('/dashboard');
            return;
        }
        fetchStats();
    }, [navigate]);

    const fetchStats = async () => {
        try {
            const res = await api.get('/api/admin/dashboard');
            setStats(res.data);
        } catch (err) {
            setError(err.response?.data?.message || 'Không thể tải dữ liệu thống kê');
        } finally {
            setLoading(false);
        }
    };

    if (loading) return (
        <div style={styles.loadingContainer}>
            <div style={styles.spinner} />
            <p style={{ color: '#94a3b8' }}>Đang tải thống kê...</p>
        </div>
    );

    if (error) return (
        <div style={styles.errorContainer}>
            <p style={{ color: '#ef4444' }}>{error}</p>
            <button onClick={() => navigate('/dashboard')} style={styles.backBtn}>← Quay lại Dashboard</button>
        </div>
    );

    return (
        <div style={styles.page}>
            {/* Header */}
            <div style={styles.header}>
                <div style={styles.headerInner}>
                    <div style={styles.headerLeft}>
                        <h1 style={styles.title}>🛡️ Admin Panel</h1>
                        <p style={styles.subtitle}>Quản trị hệ thống Smart Link Shortener</p>
                    </div>
                    <button onClick={() => navigate('/dashboard')} style={styles.backBtn}>
                        ← Quay lại Dashboard
                    </button>
                </div>
            </div>

            {/* Navigation Tabs */}
            <div style={styles.navTabs}>
                <Link to="/admin" style={{...styles.tab, ...styles.tabActive}}>📊 Tổng quan</Link>
                <Link to="/admin/users" style={styles.tab}>👥 Quản lý User</Link>
                <Link to="/admin/links" style={styles.tab}>🔗 Quản lý Link</Link>
            </div>

            {/* Stats Cards */}
            <div style={styles.content}>
                <div style={styles.cardsGrid}>
                    <div style={{...styles.card, borderLeft: '4px solid #3b82f6'}}>
                        <div style={styles.cardIcon}>👥</div>
                        <div>
                            <p style={styles.cardLabel}>Tổng User</p>
                            <h3 style={styles.cardValue}>{stats?.totalUsers ?? 0}</h3>
                        </div>
                    </div>
                    <div style={{...styles.card, borderLeft: '4px solid #10b981'}}>
                        <div style={styles.cardIcon}>🔗</div>
                        <div>
                            <p style={styles.cardLabel}>Tổng Link</p>
                            <h3 style={styles.cardValue}>{stats?.totalLinks ?? 0}</h3>
                        </div>
                    </div>
                    <div style={{...styles.card, borderLeft: '4px solid #f59e0b'}}>
                        <div style={styles.cardIcon}>📈</div>
                        <div>
                            <p style={styles.cardLabel}>Tổng Click</p>
                            <h3 style={styles.cardValue}>{stats?.totalClicks?.toLocaleString() ?? 0}</h3>
                        </div>
                    </div>
                    <div style={{...styles.card, borderLeft: '4px solid #8b5cf6'}}>
                        <div style={styles.cardIcon}>🆕</div>
                        <div>
                            <p style={styles.cardLabel}>User mới hôm nay</p>
                            <h3 style={styles.cardValue}>{stats?.newUsersToday ?? 0}</h3>
                            <p style={styles.cardSub}>Tuần này: {stats?.newUsersThisWeek ?? 0}</p>
                        </div>
                    </div>
                </div>

                {/* Top Trending Links */}
                <div style={styles.section}>
                    <h2 style={styles.sectionTitle}>🔥 Top Trending Links</h2>
                    {stats?.topTrendingLinks?.length > 0 ? (
                        <div style={styles.tableWrapper}>
                            <table style={styles.table}>
                                <thead>
                                    <tr>
                                        <th style={styles.th}>#</th>
                                        <th style={styles.th}>Short Code</th>
                                        <th style={styles.th}>URL gốc</th>
                                        <th style={styles.th}>Lượt click</th>
                                        <th style={styles.th}>Chủ sở hữu</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {stats.topTrendingLinks.map((link, index) => (
                                        <tr key={link.id} style={styles.tr}>
                                            <td style={styles.td}>
                                                <span style={{
                                                    ...styles.rankBadge,
                                                    background: index === 0 ? '#f59e0b' : index === 1 ? '#94a3b8' : index === 2 ? '#cd7f32' : '#374151'
                                                }}>
                                                    {index + 1}
                                                </span>
                                            </td>
                                            <td style={styles.td}>
                                                <code style={styles.code}>/r/{link.shortCode}</code>
                                            </td>
                                            <td style={{...styles.td, maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>
                                                {link.originalUrl}
                                            </td>
                                            <td style={{...styles.td, fontWeight: 700, color: '#3b82f6'}}>
                                                {link.clickCount?.toLocaleString()}
                                            </td>
                                            <td style={styles.td}>{link.userName}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    ) : (
                        <p style={{ color: '#64748b', textAlign: 'center', padding: '2rem' }}>Chưa có link nào.</p>
                    )}
                </div>
            </div>
        </div>
    );
}

const styles = {
    page: { minHeight: '100vh', background: '#0f172a', color: '#e2e8f0', fontFamily: "'Inter', sans-serif" },
    loadingContainer: { display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', background: '#0f172a' },
    errorContainer: { display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', background: '#0f172a', gap: '1rem' },
    spinner: { width: 40, height: 40, border: '3px solid #334155', borderTop: '3px solid #3b82f6', borderRadius: '50%', animation: 'spin 1s linear infinite' },
    header: { background: 'linear-gradient(135deg, #1e293b 0%, #0f172a 100%)', borderBottom: '1px solid #1e293b', padding: '1.5rem 0' },
    headerInner: { maxWidth: 1200, margin: '0 auto', padding: '0 1.5rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '1rem' },
    headerLeft: {},
    title: { fontSize: '1.75rem', fontWeight: 800, color: '#f8fafc', margin: 0 },
    subtitle: { color: '#94a3b8', fontSize: '0.875rem', marginTop: '0.25rem' },
    backBtn: { background: '#1e293b', border: '1px solid #334155', color: '#94a3b8', padding: '0.5rem 1rem', borderRadius: 8, cursor: 'pointer', fontSize: '0.875rem', transition: 'all 0.2s' },
    navTabs: { maxWidth: 1200, margin: '0 auto', padding: '1rem 1.5rem 0', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' },
    tab: { padding: '0.625rem 1.25rem', borderRadius: '8px 8px 0 0', background: '#1e293b', color: '#94a3b8', textDecoration: 'none', fontSize: '0.875rem', fontWeight: 500, border: '1px solid #334155', borderBottom: 'none', transition: 'all 0.2s' },
    tabActive: { background: '#334155', color: '#f8fafc', fontWeight: 600 },
    content: { maxWidth: 1200, margin: '0 auto', padding: '1.5rem' },
    cardsGrid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '1rem', marginBottom: '2rem' },
    card: { background: '#1e293b', borderRadius: 12, padding: '1.25rem', display: 'flex', alignItems: 'center', gap: '1rem', border: '1px solid #334155' },
    cardIcon: { fontSize: '2rem', width: 50, height: 50, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#0f172a', borderRadius: 10 },
    cardLabel: { color: '#94a3b8', fontSize: '0.8rem', margin: 0, textTransform: 'uppercase', letterSpacing: '0.05em' },
    cardValue: { color: '#f8fafc', fontSize: '1.75rem', fontWeight: 800, margin: '0.25rem 0 0' },
    cardSub: { color: '#64748b', fontSize: '0.75rem', margin: '0.25rem 0 0' },
    section: { background: '#1e293b', borderRadius: 12, border: '1px solid #334155', overflow: 'hidden' },
    sectionTitle: { padding: '1.25rem 1.5rem', margin: 0, fontSize: '1.1rem', fontWeight: 700, borderBottom: '1px solid #334155' },
    tableWrapper: { overflowX: 'auto' },
    table: { width: '100%', borderCollapse: 'collapse' },
    th: { textAlign: 'left', padding: '0.75rem 1rem', color: '#94a3b8', fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid #334155', background: '#0f172a' },
    td: { padding: '0.75rem 1rem', borderBottom: '1px solid #1e293b', fontSize: '0.875rem' },
    tr: { transition: 'background 0.15s' },
    rankBadge: { display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28, borderRadius: '50%', color: '#fff', fontWeight: 700, fontSize: '0.8rem' },
    code: { background: '#0f172a', padding: '0.25rem 0.5rem', borderRadius: 4, fontSize: '0.8rem', color: '#60a5fa' },
};
