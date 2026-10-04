import { useState, useEffect } from 'react';
import api from '../api/axios';

export default function ChangePasswordModal({ isOpen, onClose }) {
    const [currentPassword, setCurrentPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [confirmationPassword, setConfirmationPassword] = useState('');

    const [showCurrent, setShowCurrent] = useState(false);
    const [showNew, setShowNew] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);

    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    // Reset state when modal opens/closes
    useEffect(() => {
        if (isOpen) {
            setCurrentPassword('');
            setNewPassword('');
            setConfirmationPassword('');
            setError('');
            setSuccess('');
        }
    }, [isOpen]);

    // Close on Escape key
    useEffect(() => {
        const handleKeyDown = (e) => {
            if (e.key === 'Escape' && isOpen) {
                onClose();
            }
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [isOpen, onClose]);

    if (!isOpen) return null;

    // Calculate password strength
    const getStrength = (pass) => {
        if (!pass) return { score: 0, text: '', color: '' };
        let score = 0;
        if (pass.length >= 6) score += 1;
        if (pass.length >= 10) score += 1;
        if (/[A-Z]/.test(pass) && /[a-z]/.test(pass)) score += 1;
        if (/\d/.test(pass)) score += 1;
        if (/[^A-Za-z0-9]/.test(pass)) score += 1;

        if (score <= 2) return { score: 1, text: 'Yếu', color: 'bg-red-500' };
        if (score <= 3) return { score: 2, text: 'Trung bình', color: 'bg-amber-500' };
        return { score: 3, text: 'Mạnh', color: 'bg-emerald-500' };
    };

    const strength = getStrength(newPassword);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        setSuccess('');

        if (!currentPassword) {
            setError('Vui lòng nhập mật khẩu hiện tại.');
            return;
        }

        if (!newPassword || newPassword.length < 6) {
            setError('Mật khẩu mới phải có tối thiểu 6 ký tự.');
            return;
        }

        if (newPassword === currentPassword) {
            setError('Mật khẩu mới không được trùng với mật khẩu hiện tại.');
            return;
        }

        if (newPassword !== confirmationPassword) {
            setError('Mật khẩu mới và mật khẩu xác nhận không khớp.');
            return;
        }

        setSubmitting(true);

        try {
            const res = await api.post('/api/auth/change-password', {
                currentPassword,
                newPassword,
                confirmationPassword,
            });

            const msg = res.data?.message || 'Đổi mật khẩu thành công!';
            setSuccess(msg);

            // Tự động đóng modal sau 1.5 giây
            setTimeout(() => {
                onClose();
            }, 1500);

        } catch (err) {
            const data = err.response?.data;
            const message = typeof data === 'string' ? data : data?.message;
            setError(message || 'Đổi mật khẩu thất bại. Vui lòng thử lại!');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div
            className="fixed inset-0 z-50 flex items-center justify-center p-4"
            style={{ backgroundColor: 'rgba(15, 23, 42, 0.65)', backdropFilter: 'blur(5px)' }}
            onClick={(e) => {
                if (e.target === e.currentTarget && !submitting) onClose();
            }}
        >
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md overflow-hidden transform transition-all animate-in fade-in zoom-in-95 duration-200">
                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 bg-gradient-to-r from-blue-50/60 to-indigo-50/40">
                    <div className="flex items-center gap-2.5">
                        <div className="w-8 h-8 rounded-lg bg-blue-100 text-blue-600 flex items-center justify-center text-lg">
                            🔑
                        </div>
                        <div>
                            <h3 className="text-base font-bold text-gray-900">Thay đổi mật khẩu</h3>
                            <p className="text-xs text-gray-500">Bảo mật tài khoản của bạn</p>
                        </div>
                    </div>
                    <button
                        onClick={onClose}
                        disabled={submitting}
                        className="text-gray-400 hover:text-gray-600 w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 transition"
                    >
                        ✕
                    </button>
                </div>

                {/* Body Form */}
                <form onSubmit={handleSubmit} className="p-6 space-y-4">
                    {error && (
                        <div className="p-3 bg-red-50 border-l-4 border-red-500 text-red-700 text-xs sm:text-sm rounded flex items-start gap-2">
                            <span>⚠️</span>
                            <span className="flex-1">{error}</span>
                        </div>
                    )}

                    {success && (
                        <div className="p-3 bg-emerald-50 border-l-4 border-emerald-500 text-emerald-800 text-xs sm:text-sm rounded flex items-start gap-2">
                            <span>✅</span>
                            <span className="flex-1">{success}</span>
                        </div>
                    )}

                    {/* Mật khẩu hiện tại */}
                    <div>
                        <label className="block text-xs font-semibold text-gray-700 uppercase tracking-wider mb-1.5">
                            Mật khẩu hiện tại <span className="text-red-500">*</span>
                        </label>
                        <div className="relative">
                            <input
                                type={showCurrent ? 'text' : 'password'}
                                value={currentPassword}
                                onChange={(e) => setCurrentPassword(e.target.value)}
                                placeholder="Nhập mật khẩu đang dùng"
                                required
                                disabled={submitting}
                                className="w-full px-3.5 py-2.5 text-sm border border-gray-300 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none pr-10"
                            />
                            <button
                                type="button"
                                onClick={() => setShowCurrent(!showCurrent)}
                                tabIndex="-1"
                                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 text-sm"
                            >
                                {showCurrent ? '🙈' : '👁️'}
                            </button>
                        </div>
                    </div>

                    {/* Mật khẩu mới */}
                    <div>
                        <label className="block text-xs font-semibold text-gray-700 uppercase tracking-wider mb-1.5">
                            Mật khẩu mới <span className="text-red-500">*</span>
                        </label>
                        <div className="relative">
                            <input
                                type={showNew ? 'text' : 'password'}
                                value={newPassword}
                                onChange={(e) => setNewPassword(e.target.value)}
                                placeholder="Tối thiểu 6 ký tự"
                                required
                                disabled={submitting}
                                className="w-full px-3.5 py-2.5 text-sm border border-gray-300 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none pr-10"
                            />
                            <button
                                type="button"
                                onClick={() => setShowNew(!showNew)}
                                tabIndex="-1"
                                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 text-sm"
                            >
                                {showNew ? '🙈' : '👁️'}
                            </button>
                        </div>

                        {/* Thanh đo độ mạnh mật khẩu */}
                        {newPassword && (
                            <div className="mt-2 space-y-1">
                                <div className="flex gap-1.5 h-1.5">
                                    <div className={`flex-1 rounded-full ${strength.score >= 1 ? strength.color : 'bg-gray-200'}`}></div>
                                    <div className={`flex-1 rounded-full ${strength.score >= 2 ? strength.color : 'bg-gray-200'}`}></div>
                                    <div className={`flex-1 rounded-full ${strength.score >= 3 ? strength.color : 'bg-gray-200'}`}></div>
                                </div>
                                <div className="flex justify-between text-[11px] text-gray-500">
                                    <span>Độ mạnh: <strong className="font-semibold text-gray-700">{strength.text}</strong></span>
                                    <span>Tối thiểu 6 ký tự</span>
                                </div>
                            </div>
                        )}
                    </div>

                    {/* Xác nhận mật khẩu mới */}
                    <div>
                        <label className="block text-xs font-semibold text-gray-700 uppercase tracking-wider mb-1.5">
                            Xác nhận mật khẩu mới <span className="text-red-500">*</span>
                        </label>
                        <div className="relative">
                            <input
                                type={showConfirm ? 'text' : 'password'}
                                value={confirmationPassword}
                                onChange={(e) => setConfirmationPassword(e.target.value)}
                                placeholder="Nhập lại mật khẩu mới"
                                required
                                disabled={submitting}
                                className="w-full px-3.5 py-2.5 text-sm border border-gray-300 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none pr-10"
                            />
                            <button
                                type="button"
                                onClick={() => setShowConfirm(!showConfirm)}
                                tabIndex="-1"
                                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 text-sm"
                            >
                                {showConfirm ? '🙈' : '👁️'}
                            </button>
                        </div>

                        {/* Check match indicator */}
                        {confirmationPassword && (
                            <p className="mt-1 text-xs flex items-center gap-1">
                                {newPassword === confirmationPassword ? (
                                    <span className="text-emerald-600 font-medium">✓ Mật khẩu khớp nhau</span>
                                ) : (
                                    <span className="text-red-500 font-medium">✕ Mật khẩu chưa khớp</span>
                                )}
                            </p>
                        )}
                    </div>

                    {/* Footer Buttons */}
                    <div className="flex items-center justify-end gap-3 pt-4 border-t border-gray-100 mt-6">
                        <button
                            type="button"
                            onClick={onClose}
                            disabled={submitting}
                            className="px-4 py-2 text-sm font-medium text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-xl transition"
                        >
                            Hủy bỏ
                        </button>
                        <button
                            type="submit"
                            disabled={submitting || (newPassword && confirmationPassword && newPassword !== confirmationPassword)}
                            className="px-5 py-2 text-sm font-semibold text-white bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed rounded-xl transition shadow flex items-center gap-2"
                        >
                            {submitting ? (
                                <>
                                    <span className="animate-spin text-xs">⏳</span>
                                    <span>Đang cập nhật...</span>
                                </>
                            ) : (
                                <span>Cập nhật mật khẩu</span>
                            )}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
