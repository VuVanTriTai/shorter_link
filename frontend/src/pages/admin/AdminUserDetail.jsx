import { useEffect, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import api from '../../api/axios';

export default function AdminUserDetail() {
    const { id } = useParams();
    const [user, setUser] = useState(null);
    const [links, setLinks] = useState([]);
    const [loading, setLoading] = useState(true);
    const [linksLoading, setLinksLoading] = useState(true);
    const [linksPage, setLinksPage] = useState(0);
    const [linksTotalPages, setLinksTotalPages] = useState(1);
    const [actionLoading, setActionLoading] = useState(false);
    const navigate = useNavigate();

    useEffect(() => {
        const role = localStorage.getItem('role');
        if (role !== 'ROLE_ADMIN') { navigate('/dashboard'); return; }
        fetchUser();
    }, [id, navigate]);

    useEffect(() => {
        if (user) fetchUserLinks();
    }, [user, linksPage]);

    const fetchUser = async () => {
        try {
            const res = await api.get(`/api/admin/users/${id}`);
            setUser(res.data);
        } catch (err) {
            alert(err.response?.data?.message || 'Không thể tải thông tin user');
            navigate('/admin/users');
        } finally {
            setLoading(false);
        }
    };

    const fetchUserLinks = async () => {
        setLinksLoading(true);
        try {
            const res = await api.get('/api/admin/links', { params: { userId: id, page: linksPage, size: 10 } });
            setLinks(res.data.content || []);
            setLinksTotalPages(res.data.totalPages || 1);
        } catch { /* ignore */ } finally {
            setLinksLoading(false);
        }
    };

    const handleToggleLock = async () => {
        setActionLoading(true);
        try {
            const res = await api.patch(`/api/admin/users/${id}/lock`);
            setUser(res.data);
        } catch (err) {
            alert(err.response?.data?.message || 'Lỗi');
        } finally {
            setActionLoading(false);
        }
    };

    const handleDelete = async () => {
        if (!window.confirm(`Xoá tài khoản "${user.username}"?\n(Soft-delete: data link vẫn được giữ lại)`)) return;
        setActionLoading(true);
        try {
            await api.delete(`/api/admin/users/${id}`);
            navigate('/admin/users');
        } catch (err) {
            alert(err.response?.data?.message || 'Lỗi');
            setActionLoading(false);
        }
    };

    const handleToggleLinkActive = async (linkId) => {
        try {
            await api.patch(`/api/admin/links/${linkId}/active`);
            fetchUserLinks();
        } catch (err) {
            alert(err.response?.data?.message || 'Lỗi');
        }
    };

    const formatDate = (d) => {
        if (!d) return '—';
        return new Date(d.replace('Z', '')).toLocaleString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    };

    if (loading) return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', background: '#0f172a', color: '#94a3b8' }}>
            Đang tải...
        </div>
    );

    return (
        <div style={styles.page}>
            <div style={styles.header}>
                <div style={styles.headerInner}>
                    <div>
                        <h1 style={styles.title}>👤 Chi tiết User</h1>
                        <p style={styles.subtitle}>Thông tin chi tiết và link của user</p>
                    </div>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                        <button onClick={() => navigate('/admin/users')} style={styles.backBtn}>← Quản lý User</button>
                    </div>
                </div>
            </div>

            <div style={styles.content}>
                {/* User Info Card */}
                <div style={styles.infoCard}>
                    <div style={styles.infoHeader}>
                        <div style={styles.avatar}>{user.username?.charAt(0).toUpperCase()}</div>
                        <div>
                            <h2 style={{ margin: 0, fontSize: '1.5rem', color: '#f8fafc' }}>{user.displayName || user.username}</h2>
                            <p style={{ margin: '0.25rem 0 0', color: '#94a3b8', fontSize: '0.875rem' }}>@{user.username}</p>
                        </div>
                        <div style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                            <span style={{
                                ...styles.badge,
                                background: user.roles === 'ROLE_ADMIN' ? '#f59e0b22' : '#3b82f622',
                                color: user.roles === 'ROLE_ADMIN' ? '#f59e0b' : '#3b82f6'
                            }}>
                                {user.roles === 'ROLE_ADMIN' ? '👑 Admin' : '👤 User'}
                            </span>
                            <span style={{
                                ...styles.badge,
                                background: user.locked ? '#ef444422' : '#10b98122',
                                color: user.locked ? '#ef4444' : '#10b981'
                            }}>
                                {user.locked ? '🔒 Đã khoá' : '✅ Hoạt động'}
                            </span>
                        </div>
                    </div>

                    <div style={styles.infoGrid}>
                        <div style={styles.infoItem}>
                            <span style={styles.infoLabel}>Email</span>
                            <span style={styles.infoValue}>{user.email || '—'}</span>
                        </div>
                        <div style={styles.infoItem}>
                            <span style={styles.infoLabel}>Provider</span>
                            <span style={styles.infoValue}>{user.authProvider}</span>
                        </div>
                        <div style={styles.infoItem}>
                            <span style={styles.infoLabel}>Ngày tạo</span>
                            <span style={styles.infoValue}>{formatDate(user.createdAt)}</span>
                        </div>
                        <div style={styles.infoItem}>
                            <span style={styles.infoLabel}>Số link đã tạo</span>
                            <span style={{...styles.infoValue, color: '#3b82f6', fontWeight: 700, fontSize: '1.25rem'}}>{user.linkCount}</span>
                        </div>
                    </div>

                    {/* Actions */}
                    {user.roles !== 'ROLE_ADMIN' && (
                        <div style={{ display: 'flex', gap: '0.75rem', marginTop: '1.5rem', paddingTop: '1.5rem', borderTop: '1px solid #334155' }}>
                            <button onClick={handleToggleLock} disabled={actionLoading} style={{
                                ...styles.actionBtn,
                                background: user.locked ? '#10b981' : '#f59e0b',
                            }}>
                                {user.locked ? '🔓 Mở khoá tài khoản' : '🔒 Khoá tài khoản'}
                            </button>
                            <button onClick={handleDelete} disabled={actionLoading} style={{
                                ...styles.actionBtn,
                                background: '#ef4444',
                            }}>
                                🗑 Xoá tài khoản
                            </button>
                        </div>
                    )}
                </div>

                {/* User's Links */}
                <div style={styles.section}>
                    <h3 style={styles.sectionTitle}>🔗 Link của user ({user.linkCount})</h3>
                    <div style={styles.tableWrapper}>
                        <table style={styles.table}>
                            <thead>
                                <tr>
                                    <th style={styles.th}>Short Code</th>
                                    <th style={styles.th}>URL gốc</th>
                                    <th style={styles.th}>Lượt click</th>
                                    <th style={styles.th}>Trạng thái</th>
                                    <th style={styles.th}>Ngày tạo</th>
                                    <th style={styles.th}>Hành động</th>
                                </tr>
                            </thead>
                            <tbody>
                                {linksLoading ? (
                                    <tr><td colSpan={6} style={{...styles.td, textAlign: 'center', color: '#64748b'}}>Đang tải...</td></tr>
                                ) : links.length === 0 ? (
                                    <tr><td colSpan={6} style={{...styles.td, textAlign: 'center', color: '#64748b'}}>User chưa có link nào</td></tr>
                                ) : links.map(link => (
                                    <tr key={link.id}>
                                        <td style={styles.td}><code style={styles.code}>/r/{link.shortCode}</code></td>
                                        <td style={{...styles.td, maxWidth: '250px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>
                                            <a href={link.originalUrl} target="_blank" rel="noopener noreferrer" style={{ color: '#60a5fa', textDecoration: 'none' }}>
                                                {link.originalUrl}
                                            </a>
                                        </td>
                                        <td style={{...styles.td, fontWeight: 600, color: '#3b82f6'}}>{link.clickCount}</td>
                                        <td style={styles.td}>
                                            <span style={{
                                                ...styles.badge,
                                                background: link.active ? '#10b98122' : '#ef444422',
                                                color: link.active ? '#10b981' : '#ef4444'
                                            }}>
                                                {link.active ? '✅ Active' : '❌ Inactive'}
                                            </span>
                                        </td>
                                        <td style={{...styles.td, fontSize: '0.8rem', color: '#94a3b8'}}>{formatDate(link.createdAt)}</td>
                                        <td style={styles.td}>
                                            <button
                                                onClick={() => handleToggleLinkActive(link.id)}
                                                style={{
                                                    border: 'none', padding: '0.375rem 0.625rem', borderRadius: 6, cursor: 'pointer', fontSize: '0.8rem',
                                                    background: link.active ? '#f59e0b22' : '#10b98122',
                                                    color: link.active ? '#f59e0b' : '#10b981'
                                                }}
                                            >
                                                {link.active ? '⏸ Tắt' : '▶ Bật'}
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    {linksTotalPages > 1 && (
                        <div style={styles.pagination}>
                            <button onClick={() => setLinksPage(p => Math.max(0, p - 1))} disabled={linksPage === 0} style={styles.pageBtn}>← Trước</button>
                            <span style={{ color: '#94a3b8', fontSize: '0.875rem' }}>Trang {linksPage + 1} / {linksTotalPages}</span>
                            <button onClick={() => setLinksPage(p => Math.min(linksTotalPages - 1, p + 1))} disabled={linksPage >= linksTotalPages - 1} style={styles.pageBtn}>Sau →</button>
                        </div>
                    )}
                </div>
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
    content: { maxWidth: 1200, margin: '0 auto', padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1.5rem' },
    infoCard: { background: '#1e293b', borderRadius: 12, border: '1px solid #334155', padding: '1.5rem' },
    infoHeader: { display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' },
    avatar: { width: 56, height: 56, borderRadius: '50%', background: 'linear-gradient(135deg, #3b82f6, #8b5cf6)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '1.5rem', fontWeight: 800, color: '#fff' },
    infoGrid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '1rem', marginTop: '1.5rem', paddingTop: '1.5rem', borderTop: '1px solid #334155' },
    infoItem: { display: 'flex', flexDirection: 'column', gap: '0.25rem' },
    infoLabel: { color: '#64748b', fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em' },
    infoValue: { color: '#e2e8f0', fontSize: '0.9rem' },
    badge: { padding: '0.25rem 0.625rem', borderRadius: 6, fontSize: '0.75rem', fontWeight: 600, display: 'inline-block' },
    actionBtn: { border: 'none', padding: '0.625rem 1.25rem', borderRadius: 8, cursor: 'pointer', color: '#fff', fontWeight: 600, fontSize: '0.875rem' },
    section: { background: '#1e293b', borderRadius: 12, border: '1px solid #334155', overflow: 'hidden' },
    sectionTitle: { padding: '1rem 1.5rem', margin: 0, fontSize: '1rem', fontWeight: 700, borderBottom: '1px solid #334155' },
    tableWrapper: { overflowX: 'auto' },
    table: { width: '100%', borderCollapse: 'collapse' },
    th: { textAlign: 'left', padding: '0.75rem 1rem', color: '#94a3b8', fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em', borderBottom: '1px solid #334155', background: '#0f172a' },
    td: { padding: '0.75rem 1rem', borderBottom: '1px solid #1e293b44', fontSize: '0.875rem' },
    code: { background: '#0f172a', padding: '0.25rem 0.5rem', borderRadius: 4, fontSize: '0.8rem', color: '#60a5fa' },
    pagination: { display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '1rem', padding: '1rem' },
    pageBtn: { background: '#0f172a', border: '1px solid #334155', color: '#94a3b8', padding: '0.5rem 1rem', borderRadius: 8, cursor: 'pointer', fontSize: '0.875rem' },
};
