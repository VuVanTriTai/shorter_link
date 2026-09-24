import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Register from './pages/Register';
import Dashboard from './pages/Dashboard';
import CreateLink from './pages/CreateLink';
import LinkStats from './pages/LinkStats';

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
          <Route path="/links/:shortCode/stats" element={<LinkStats />} />
        </Routes>
      </BrowserRouter>
  );
}

export default App;