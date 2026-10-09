import { useEffect, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../../api/axios';

export default function AdminLinks() {
    const [links, setLinks] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [search, setSearch] = useState('');
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [totalElements, setTotalElements] = useState(0);
    const [actionLoading, setActionLoading] = useState(null);
    const navigate = useNavigate();

    useEffect(() => {
        const role = localStorage.getItem('role');
        if (role !== 'ROLE_ADMIN') { navigate('/dashboard'); return; }
    }, [navigate]);

    useEffect(() => {
        fetchLinks();
    }, [page, search]);

    const fetchLinks = async () => {
        setLoading(true);
        try {
            const params = { page, size: 10 };
            if (search.trim()) params.search = search.trim();
            const res = await api.get('/api/admin/links', { params });
            setLinks(res.data.content || []);
            setTotalPages(res.data.totalPages || 1);
            setTotalElements(res.data.totalElements || 0);
        } catch (err) {
            setError(err.response?.data?.message || 'Không thể tải danh sách link');
        } finally {
            setLoading(false);
        }
    };

    const handleSearch = (e) => {
        setSearch(e.target.value);
        setPage(0);
    };

    const handleToggleActive = async (linkId) => {
        setActionLoading(linkId);
        try {
            await api.patch(`/api/admin/links/${linkId}/active`);
            fetchLinks();
        } catch (err) {
            alert(err.response?.data?.message || 'Lỗi');
        } finally {
            setActionLoading(null);
        }
    };

    const handleDelete = async (linkId, shortCode) => {
        if (!window.confirm(`Bạn có chắc muốn xoá link "/r/${shortCode}"?\n(Xoá vĩnh viễn, không thể khôi phục)`)) return;
        setActionLoading(linkId);
        try {
            await api.delete(`/api/admin/links/${linkId}`);
            fetchLinks();
        } catch (err) {
            alert(err.response?.data?.message || 'Lỗi');
        } finally {
            setActionLoading(null);
        }
    };

    const formatDate = (d) => {
        if (!d) return '—';
        return new Date(d.replace('Z', '')).toLocaleString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    };

    return (
        <div style={styles.page}>
            <div style={styles.header}>
                <div style={styles.headerInner}>
                    <div>
                        <h1 style={styles.title}>🔗 Quản lý Link</h1>
                        <p style={styles.subtitle}>Xem, deactivate, xoá link toàn hệ thống ({totalElements} link)</p>
                    </div>
                    <button onClick={() => navigate('/dashboard')} style={styles.backBtn}>← Dashboard</button>
                </div>
            </div>

            <div style={styles.navTabs}>
                <Link to="/admin" style={styles.tab}>📊 Tổng quan</Link>
                <Link to="/admin/users" style={styles.tab}>👥 Quản lý User</Link>
                <Link to="/admin/links" style={{...styles.tab, ...styles.tabActive}}>🔗 Quản lý Link</Link>
            </div>

            <div style={styles.content}>
                {/* Search */}
                <div style={styles.searchBar}>
                    <input
                        type="text"
                        placeholder="🔍 Tìm kiếm theo short code hoặc URL gốc..."
                        value={search}
                        onChange={handleSearch}
                        style={styles.searchInput}
                    />
                </div>

                {error && <p style={{ color: '#ef4444', textAlign: 'center' }}>{error}</p>}

                {/* Table */}
                <div style={styles.tableWrapper}>
                    <table style={styles.table}>
                        <thead>
                            <tr>
                                <th style={styles.th}>ID</th>
                                <th style={styles.th}>Short Code</th>
                                <th style={styles.th}>URL gốc</th>
                                <th style={styles.th}>Chủ sở hữu</th>
                                <th style={styles.th}>Click</th>
                                <th style={styles.th}>Trạng thái</th>
                                <th style={styles.th}>Bảo vệ</th>
                                <th style={styles.th}>Ngày tạo</th>
                                <th style={styles.th}>Hành động</th>
                            </tr>
                        </thead>
                        <tbody>
                            {loading ? (
                                <tr><td colSpan={9} style={{...styles.td, textAlign: 'center', color: '#64748b'}}>Đang tải...</td></tr>
                            ) : links.length === 0 ? (
                                <tr><td colSpan={9} style={{...styles.td, textAlign: 'center', color: '#64748b'}}>Không tìm thấy link nào</td></tr>
                            ) : links.map(link => (
                                <tr key={link.id} style={styles.tr}>
                                    <td style={{...styles.td, color: '#64748b'}}>{link.id}</td>
                                    <td style={styles.td}>
                                        <a href={`/r/${link.shortCode}`} target="_blank" rel="noopener noreferrer" style={{ textDecoration: 'none' }}>
                                            <code style={styles.code}>/r/{link.shortCode}</code>
                                        </a>
                                    </td>
                                    <td style={{...styles.td, maxWidth: '220px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>
                                        <a href={link.originalUrl} target="_blank" rel="noopener noreferrer" style={{ color: '#94a3b8', textDecoration: 'none', fontSize: '0.8rem' }}>
                                            {link.originalUrl}
                                        </a>
                                    </td>
                                    <td style={styles.td}>
                                        {link.userId ? (
                                            <Link to={`/admin/users/${link.userId}`} style={{ color: '#60a5fa', textDecoration: 'none', fontWeight: 500 }}>
                                                {link.userName}
                                            </Link>
                                        ) : link.userName}
                                    </td>
                                    <td style={{...styles.td, fontWeight: 700, color: '#3b82f6', textAlign: 'center'}}>
                                        {link.clickCount?.toLocaleString()}
                                    </td>
                                    <td style={styles.td}>
                                        <span style={{
                                            ...styles.badge,
                                            background: link.banned ? '#ef444422' : (link.active ? '#10b98122' : '#f59e0b22'),
                                            color: link.banned ? '#ef4444' : (link.active ? '#10b981' : '#f59e0b')
                                        }}>
                                            {link.banned ? '🚫 Bị khoá (Ban)' : (link.active ? '✅ Hoạt động' : '⏸️ Tạm tắt')}
                                        </span>
                                    </td>
                                    <td style={styles.td}>
                                        {link.hasPassword && <span style={{...styles.badge, background: '#f59e0b22', color: '#f59e0b'}}>🔒</span>}
                                    </td>
                                    <td style={{...styles.td, fontSize: '0.8rem', color: '#94a3b8'}}>{formatDate(link.createdAt)}</td>
                                    <td style={styles.td}>
                                        <div style={{ display: 'flex', gap: '0.5rem' }}>
                                            <button
                                                onClick={() => handleToggleActive(link.id)}
                                                disabled={actionLoading === link.id}
                                                style={{
                                                    ...styles.actionBtn,
                                                    background: link.banned ? '#10b98122' : '#ef444422',
                                                    color: link.banned ? '#10b981' : '#ef4444',
                                                    padding: '4px 8px',
                                                    fontSize: '0.75rem',
                                                    fontWeight: 600
                                                }}
                                                title={link.banned ? 'Mở khoá link (Unban)' : 'Khoá link vi phạm (Ban)'}
                                            >
                                                {link.banned ? '🔓 Mở' : '🚫 Khoá'}
                                            </button>
                                            <button
                                                onClick={() => handleDelete(link.id, link.shortCode)}
                                                disabled={actionLoading === link.id}
                                                style={{...styles.actionBtn, background: '#ef444422', color: '#ef4444'}}
                                                title="Xoá link"
                                            >
                                                🗑
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>

                {/* Pagination */}
                {totalPages > 1 && (
                    <div style={styles.pagination}>
                        <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0} style={styles.pageBtn}>← Trước</button>
                        <span style={{ color: '#94a3b8', fontSize: '0.875rem' }}>Trang {page + 1} / {totalPages}</span>
                        <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1} style={styles.pageBtn}>Sau →</button>
                    </div>
                )}
            </div>
        </div>
    );
}

const styles = {
    page: { minHeight: '100vh', background: '#0f172a', color: '#e2e8f0', fontFamily: "'Inter', sans-serif" },
    header: { background: 'linear-gradient(135deg, #1e293b 0%, #0f172a 100%)', borderBottom: '1px solid #1e293b', padding: '1.5rem 0' },
    headerInner: { maxWidth: 1200, margin: '0 auto', padding: '0 1.5rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '1rem' },
    title: { fontSize: '1.75rem', fontWeight: 800, color: '#f8fafc', margin: 0 },
    subtitle: { color: '#94a3b8', fontSize: '0.875rem', marginTop: '0.25rem' },
    backBtn: { background: '#1e293b', border: '1px solid #334155', color: '#94a3b8', padding: '0.5rem 1rem', borderRadius: 8, cursor: 'pointer', fontSize: '0.875rem' },
    navTabs: { maxWidth: 1200, margin: '0 auto', padding: '1rem 1.5rem 0', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' },
    tab: { padding: '0.625rem 1.25rem', borderRadius: '8px 8px 0 0', background: '#1e293b', color: '#94a3b8', textDecoration: 'none', fontSize: '0.875rem', fontWeight: 500, border: '1px solid #334155', borderBottom: 'none' },
    tabActive: { background: '#334155', color: '#f8fafc', fontWeight: 600 },
    content: { maxWidth: 1200, margin: '0 auto', padding: '1.5rem' },
    searchBar: { marginBottom: '1rem' },
    searchInput: { width: '100%', padding: '0.75rem 1rem', background: '#1e293b', border: '1px solid #334155', borderRadius: 8, color: '#e2e8f0', fontSize: '0.875rem', outline: 'none', boxSizing: 'border-box' },
    tableWrapper: { background: '#1e293b', borderRadius: 12, border: '1px solid #334155', overflowX: 'auto' },
    table: { width: '100%', borderCollapse: 'collapse' },
    th: { textAlign: 'left', padding: '0.75rem 1rem', color: '#94a3b8', fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid #334155', background: '#0f172a' },
    td: { padding: '0.75rem 1rem', borderBottom: '1px solid #1e293b44', fontSize: '0.875rem' },
    tr: { transition: 'background 0.15s' },
    badge: { padding: '0.25rem 0.625rem', borderRadius: 6, fontSize: '0.75rem', fontWeight: 600, display: 'inline-block' },
    code: { background: '#0f172a', padding: '0.25rem 0.5rem', borderRadius: 4, fontSize: '0.8rem', color: '#60a5fa' },
    actionBtn: { border: 'none', padding: '0.375rem 0.625rem', borderRadius: 6, cursor: 'pointer', fontSize: '0.875rem', transition: 'all 0.2s' },
    pagination: { display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '1rem', marginTop: '1rem' },
    pageBtn: { background: '#1e293b', border: '1px solid #334155', color: '#94a3b8', padding: '0.5rem 1rem', borderRadius: 8, cursor: 'pointer', fontSize: '0.875rem' },
};
