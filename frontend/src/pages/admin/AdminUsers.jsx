import { useEffect, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../../api/axios';

export default function AdminUsers() {
    const [users, setUsers] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [search, setSearch] = useState('');
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [actionLoading, setActionLoading] = useState(null);
    const navigate = useNavigate();

    useEffect(() => {
        const role = localStorage.getItem('role');
        if (role !== 'ROLE_ADMIN') { navigate('/dashboard'); return; }
    }, [navigate]);

    useEffect(() => {
        fetchUsers();
    }, [page, search]);

    const fetchUsers = async () => {
        setLoading(true);
        try {
            const params = { page, size: 10 };
            if (search.trim()) params.search = search.trim();
            const res = await api.get('/api/admin/users', { params });
            setUsers(res.data.content || []);
            setTotalPages(res.data.totalPages || 1);
        } catch (err) {
            setError(err.response?.data?.message || 'Không thể tải danh sách user');
        } finally {
            setLoading(false);
        }
    };

    const handleSearch = (e) => {
        setSearch(e.target.value);
        setPage(0);
    };

    const handleToggleLock = async (userId) => {
        setActionLoading(userId);
        try {
            await api.patch(`/api/admin/users/${userId}/lock`);
            fetchUsers();
        } catch (err) {
            alert(err.response?.data?.message || 'Lỗi khi khoá/mở khoá');
        } finally {
            setActionLoading(null);
        }
    };

    const handleDelete = async (userId, username) => {
        if (!window.confirm(`Bạn có chắc muốn xoá tài khoản "${username}"?\n(Soft-delete: data link vẫn được giữ lại)`)) return;
        setActionLoading(userId);
        try {
            await api.delete(`/api/admin/users/${userId}`);
            fetchUsers();
        } catch (err) {
            alert(err.response?.data?.message || 'Lỗi khi xoá tài khoản');
        } finally {
            setActionLoading(null);
        }
    };

    const formatDate = (d) => {
        if (!d) return '—';
        return new Date(d.replace('Z', '')).toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });
    };

    return (
        <div style={styles.page}>
            <div style={styles.header}>
                <div style={styles.headerInner}>
                    <div>
                        <h1 style={styles.title}>👥 Quản lý User</h1>
                        <p style={styles.subtitle}>Xem, khoá, mở khoá và quản lý tài khoản người dùng</p>
                    </div>
                    <button onClick={() => navigate('/dashboard')} style={styles.backBtn}>← Dashboard</button>
                </div>
            </div>

            <div style={styles.navTabs}>
                <Link to="/admin" style={styles.tab}>📊 Tổng quan</Link>
                <Link to="/admin/users" style={{...styles.tab, ...styles.tabActive}}>👥 Quản lý User</Link>
                <Link to="/admin/links" style={styles.tab}>🔗 Quản lý Link</Link>
            </div>

            <div style={styles.content}>
                {/* Search */}
                <div style={styles.searchBar}>
                    <input
                        type="text"
                        placeholder="🔍 Tìm kiếm theo username..."
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
                                <th style={styles.th}>Username</th>
                                <th style={styles.th}>Email</th>
                                <th style={styles.th}>Role</th>
                                <th style={styles.th}>Provider</th>
                                <th style={styles.th}>Số link</th>
                                <th style={styles.th}>Ngày tạo</th>
                                <th style={styles.th}>Trạng thái</th>
                                <th style={styles.th}>Hành động</th>
                            </tr>
                        </thead>
                        <tbody>
                            {loading ? (
                                <tr><td colSpan={9} style={{...styles.td, textAlign: 'center', color: '#64748b'}}>Đang tải...</td></tr>
                            ) : users.length === 0 ? (
                                <tr><td colSpan={9} style={{...styles.td, textAlign: 'center', color: '#64748b'}}>Không tìm thấy user nào</td></tr>
                            ) : users.map(user => (
                                <tr key={user.id} style={styles.tr}>
                                    <td style={styles.td}>{user.id}</td>
                                    <td style={styles.td}>
                                        <Link to={`/admin/users/${user.id}`} style={{ color: '#60a5fa', textDecoration: 'none', fontWeight: 600 }}>
                                            {user.username}
                                        </Link>
                                    </td>
                                    <td style={{...styles.td, color: '#94a3b8', fontSize: '0.8rem'}}>{user.email || '—'}</td>
                                    <td style={styles.td}>
                                        <span style={{
                                            ...styles.badge,
                                            background: user.roles === 'ROLE_ADMIN' ? '#f59e0b22' : '#3b82f622',
                                            color: user.roles === 'ROLE_ADMIN' ? '#f59e0b' : '#3b82f6'
                                        }}>
                                            {user.roles === 'ROLE_ADMIN' ? '👑 Admin' : '👤 User'}
                                        </span>
                                    </td>
                                    <td style={styles.td}>
                                        <span style={{...styles.badge, background: '#1e293b', color: '#94a3b8'}}>
                                            {user.authProvider}
                                        </span>
                                    </td>
                                    <td style={{...styles.td, textAlign: 'center', fontWeight: 600}}>{user.linkCount}</td>
                                    <td style={{...styles.td, fontSize: '0.8rem', color: '#94a3b8'}}>{formatDate(user.createdAt)}</td>
                                    <td style={styles.td}>
                                        {user.locked ? (
                                            <span style={{...styles.badge, background: '#ef444422', color: '#ef4444'}}>🔒 Đã khoá</span>
                                        ) : (
                                            <span style={{...styles.badge, background: '#10b98122', color: '#10b981'}}>✅ Hoạt động</span>
                                        )}
                                    </td>
                                    <td style={styles.td}>
                                        {user.roles !== 'ROLE_ADMIN' && (
                                            <div style={{ display: 'flex', gap: '0.5rem' }}>
                                                <button
                                                    onClick={() => handleToggleLock(user.id)}
                                                    disabled={actionLoading === user.id}
                                                    style={{
                                                        ...styles.actionBtn,
                                                        background: user.locked ? '#10b98122' : '#f59e0b22',
                                                        color: user.locked ? '#10b981' : '#f59e0b'
                                                    }}
                                                    title={user.locked ? 'Mở khoá' : 'Khoá'}
                                                >
                                                    {user.locked ? '🔓' : '🔒'}
                                                </button>
                                                <button
                                                    onClick={() => navigate(`/admin/users/${user.id}`)}
                                                    style={{...styles.actionBtn, background: '#3b82f622', color: '#3b82f6'}}
                                                    title="Xem chi tiết"
                                                >
                                                    👁
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(user.id, user.username)}
                                                    disabled={actionLoading === user.id}
                                                    style={{...styles.actionBtn, background: '#ef444422', color: '#ef4444'}}
                                                    title="Xoá tài khoản"
                                                >
                                                    🗑
                                                </button>
                                            </div>
                                        )}
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
    actionBtn: { border: 'none', padding: '0.375rem 0.625rem', borderRadius: 6, cursor: 'pointer', fontSize: '0.875rem', transition: 'all 0.2s' },
    pagination: { display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '1rem', marginTop: '1rem' },
    pageBtn: { background: '#1e293b', border: '1px solid #334155', color: '#94a3b8', padding: '0.5rem 1rem', borderRadius: 8, cursor: 'pointer', fontSize: '0.875rem' },
};
