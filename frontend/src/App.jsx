import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Register from './pages/Register';
import Dashboard from './pages/Dashboard';
import CreateLink from './pages/CreateLink';
import EditLink from './pages/EditLink';
import LinkStats from './pages/LinkStats';
import ProtectLink from './pages/ProtectLink';
import AdminDashboard from './pages/admin/AdminDashboard';
import AdminUsers from './pages/admin/AdminUsers';
import AdminUserDetail from './pages/admin/AdminUserDetail';
import AdminLinks from './pages/admin/AdminLinks';

function App() {
  return (
      <BrowserRouter>
        <Routes>
          {/* Tự động chuyển hướng từ trang chủ về trang Đăng nhập */}
          <Route path="/" element={<Navigate to="/login" replace />} />

          {/* Màn hình Đăng nhập và Đăng ký */}
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/links/new" element={<CreateLink />} />
          <Route path="/links/:shortCode/edit" element={<EditLink />} />
          <Route path="/links/:shortCode/stats" element={<LinkStats />} />
          <Route path="/protect/:shortCode" element={<ProtectLink />} />

          {/* Admin Panel */}
          <Route path="/admin" element={<AdminDashboard />} />
          <Route path="/admin/users" element={<AdminUsers />} />
          <Route path="/admin/users/:id" element={<AdminUserDetail />} />
          <Route path="/admin/links" element={<AdminLinks />} />
        </Routes>
      </BrowserRouter>
  );
}

export default App;